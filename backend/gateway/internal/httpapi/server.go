package httpapi

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"path/filepath"
	"runtime/debug"
	"slices"
	"strconv"
	"strings"
	"time"

	"arm-driver-app/backend/gateway/internal/config"
	"arm-driver-app/backend/gateway/internal/push"
	"arm-driver-app/backend/gateway/internal/storage"
)

const maxXMLBody = 4 << 20
const maxDocumentBody = 12 << 20

// Значение <Статус> из контракта 1С. Пустой список ездок отменой не
// считается — только явный статус (инвариант 4 из AGENTS.md).
const statusCancelled = "Отменена"

// Тип события, по которому гасим напоминания: водитель принял разнарядку.
const eventTypeAcknowledged = "Ознакомление"

type Server struct {
	cfg    config.Config
	logger *slog.Logger
	client *http.Client
	store  *storage.Store
	push   push.Sender
}

func NewServer(cfg config.Config, logger *slog.Logger, client *http.Client, store *storage.Store, pushSender push.Sender) *Server {
	return &Server{cfg: cfg, logger: logger, client: client, store: store, push: pushSender}
}

func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("GET /readyz", s.ready)
	mux.HandleFunc("POST /api/1c/assignments", s.receiveAssignment)
	mux.HandleFunc("POST /api/mobile/devices", s.registerDevice)
	mux.HandleFunc("GET /api/mobile/assignments", s.listAssignments)
	mux.HandleFunc("GET /api/mobile/assignments/{id}/xml", s.assignmentByIDXML)
	mux.HandleFunc("GET /api/mobile/assignments/{id}", s.assignmentByID)
	mux.HandleFunc("POST /api/mobile/events", s.receiveEvents)
	mux.HandleFunc("POST /api/mobile/documents", s.uploadDocument)
	return s.recover(s.requestLog(mux))
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	_, _ = w.Write([]byte("ok\n"))
}

func (s *Server) ready(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	status := map[string]any{
		"status":           "ok",
		"one_c_configured": s.cfg.OneCBaseURL != "",
		"fcm_configured":   s.cfg.FCMCredsFile != "",
	}
	_ = json.NewEncoder(w).Encode(status)
}

func (s *Server) receiveAssignment(w http.ResponseWriter, r *http.Request) {
	if !s.authorized1C(r) {
		writeXMLError(w, http.StatusUnauthorized, "неверный X-Auth-Token")
		return
	}

	body, ok := readXMLBody(w, r)
	if !ok {
		return
	}

	var assignment assignmentXML
	if err := xml.Unmarshal(body, &assignment); err != nil {
		writeXMLError(w, http.StatusBadRequest, "некорректный XML: "+err.Error())
		return
	}
	if err := validateAssignment(assignment); err != nil {
		writeXMLError(w, http.StatusBadRequest, err.Error())
		return
	}

	// Существование проверяем ДО сохранения: после SaveAssignment это будет
	// уже не различить. 1С подтвердила, что правка одной ездки (в том числе
	// её отмена) приходит повторной отправкой всей разнарядки той же
	// <Идентификатор> с новой версией — это не новая задача для водителя,
	// а правка уже показанной. Без этого различия любая правка после приёма
	// заново запускала бы напоминания «не принята», хотя водитель её принял.
	_, existingMeta, existsErr := s.store.GetAssignment(assignment.ID)
	isNewAssignment := errors.Is(existsErr, storage.ErrNotFound)
	// 1С: плановая замена экипажа до начала смены — та же разнарядка новой
	// версией с другим <Водитель>. Для нового водителя это новая задача, для
	// прежнего — отмена.
	reassignedFrom := ""
	if existsErr == nil && existingMeta.DriverID != assignment.Driver.ID {
		reassignedFrom = existingMeta.DriverID
	}

	meta, saved, err := s.store.SaveAssignment(assignment.ID, assignment.Version, assignment.Driver.ID, body)
	if err != nil {
		s.logger.ErrorContext(r.Context(), "assignment save failed", "error", err)
		writeXMLError(w, http.StatusInternalServerError, "не удалось сохранить разнарядку для доставки")
		return
	}
	if !saved {
		s.logger.InfoContext(
			r.Context(),
			"older assignment ignored",
			"id", assignment.ID,
			"version", assignment.Version,
			"stored_version", meta.Version,
		)
		writeXML(w, http.StatusOK, resultXML{Status: "ok"})
		return
	}

	// Отменённая разнарядка (в том числе переданная другому водителю) —
	// это не «новая», а предупреждение, и напоминать по ней больше нечего.
	cancelled := strings.EqualFold(assignment.Status, statusCancelled)
	kind := push.KindUpdated
	switch {
	case cancelled:
		kind = push.KindCancelled
	case isNewAssignment, reassignedFrom != "":
		kind = push.KindNew
	}

	switch {
	case cancelled:
		if err := s.store.CancelReminders(assignment.ID); err != nil {
			s.logger.ErrorContext(r.Context(), "cancel reminders failed", "error", err, "assignment", assignment.ID)
		}
	case reassignedFrom != "":
		// Напоминания «не принята» шли прежнему водителю — теперь они для
		// нового, отсчёт с момента передачи.
		if err := s.store.CancelReminders(assignment.ID); err != nil {
			s.logger.ErrorContext(r.Context(), "cancel reminders failed", "error", err, "assignment", assignment.ID)
		}
		s.scheduleReminders(r.Context(), assignment)
	case isNewAssignment:
		// Ревизию уже известной разнарядки напоминаниями не трогаем: если
		// водитель её принял, CancelReminders уже снял их при приёме, и
		// заново заводить клок «не принята» здесь нельзя. Если ещё не принял —
		// исходные напоминания от первой версии продолжают тикать как есть.
		s.scheduleReminders(r.Context(), assignment)
	}

	if err := s.push.SendAssignment(r.Context(), push.AssignmentNotification{
		Kind:         kind,
		AssignmentID: assignment.ID,
		Version:      assignment.Version,
		DriverID:     assignment.Driver.ID,
	}); err != nil {
		s.logger.ErrorContext(r.Context(), "assignment push failed", "error", err, "assignment", assignment.ID)
		writeXMLError(w, http.StatusBadGateway, "разнарядка сохранена, но push не отправлен")
		return
	}

	if reassignedFrom != "" {
		// Прежнему водителю — отмена. Разнарядка у нового уже сохранена и
		// доставлена, поэтому сбой этого пуша приём не валит: прежний
		// водитель всё равно увидит отмену в выдаче при обновлении.
		if err := s.push.SendAssignment(r.Context(), push.AssignmentNotification{
			Kind:         push.KindCancelled,
			AssignmentID: assignment.ID,
			Version:      assignment.Version,
			DriverID:     reassignedFrom,
		}); err != nil {
			s.logger.ErrorContext(r.Context(), "reassign push failed", "error", err, "assignment", assignment.ID, "driver", reassignedFrom)
		}
	}

	s.logger.InfoContext(
		r.Context(),
		"assignment accepted",
		"id", assignment.ID,
		"version", assignment.Version,
		"driver", assignment.Driver.ID,
		"trips", len(assignment.Trips),
	)
	writeXML(w, http.StatusOK, resultXML{Status: "ok"})
}

func (s *Server) scheduleReminders(ctx context.Context, assignment assignmentXML) {
	now := time.Now().UTC()
	reminders := []storage.Reminder{
		{
			AssignmentID: assignment.ID,
			DriverID:     assignment.Driver.ID,
			Version:      assignment.Version,
			Kind:         string(push.KindReminder30),
			DueAt:        now.Add(time.Duration(s.cfg.AssignmentReminderFirstMinutes) * time.Minute),
		},
		{
			AssignmentID: assignment.ID,
			DriverID:     assignment.Driver.ID,
			Version:      assignment.Version,
			Kind:         string(push.KindReminder10),
			DueAt:        now.Add(time.Duration(s.cfg.AssignmentReminderSecondMinutes) * time.Minute),
		},
	}
	if err := s.store.ScheduleReminders(reminders); err != nil {
		// Разнарядка уже сохранена и доставлена — напоминания не повод
		// валить приём, но молчать об ошибке нельзя.
		s.logger.ErrorContext(ctx, "schedule reminders failed", "error", err, "assignment", assignment.ID)
	}
}

// SendDueReminders вызывается фоновым воркером раз в минуту: шлёт
// напоминания, которым пора, и забывает их.
func (s *Server) SendDueReminders(ctx context.Context, now time.Time) {
	due, err := s.store.DueReminders(now)
	if err != nil {
		s.logger.ErrorContext(ctx, "read due reminders failed", "error", err)
		return
	}
	for _, reminder := range due {
		err := s.push.SendAssignment(ctx, push.AssignmentNotification{
			Kind:         push.Kind(reminder.Kind),
			AssignmentID: reminder.AssignmentID,
			Version:      reminder.Version,
			DriverID:     reminder.DriverID,
		})
		if err != nil {
			s.logger.ErrorContext(
				ctx,
				"reminder push failed",
				"error", err,
				"assignment", reminder.AssignmentID,
				"kind", reminder.Kind,
			)
			continue
		}
		s.logger.InfoContext(
			ctx,
			"reminder sent",
			"assignment", reminder.AssignmentID,
			"driver", reminder.DriverID,
			"kind", reminder.Kind,
		)
	}
}

func (s *Server) registerDevice(w http.ResponseWriter, r *http.Request) {
	if !s.authorizedMobile(r) {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "неверный X-Auth-Token")
		return
	}

	var device deviceRequest
	if !readJSONBody(w, r, &device) {
		return
	}
	if err := s.store.SaveDevice(storage.Device{
		DriverID: device.DriverID,
		DeviceID: device.DeviceID,
		FCMToken: device.FCMToken,
	}); err != nil {
		writeJSONError(w, http.StatusBadRequest, "validation_error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// listAssignments отдаёт разнарядки водителя за последние
// cfg.AssignmentListWindowHours часов, свежие первыми. Заменяет прежний
// "current" (одна последняя разнарядка на водителя) — за день у водителя
// может быть несколько разнарядок, и старая не должна прятаться за новой.
func (s *Server) listAssignments(w http.ResponseWriter, r *http.Request) {
	if !s.authorizedMobile(r) {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "неверный X-Auth-Token")
		return
	}
	driverID := r.URL.Query().Get("driver_id")
	if driverID == "" {
		writeJSONError(w, http.StatusBadRequest, "validation_error", "обязателен query-параметр driver_id")
		return
	}

	since := time.Now().UTC().Add(-time.Duration(s.cfg.AssignmentListWindowHours) * time.Hour)
	metas, err := s.store.ListAssignmentsForDriver(driverID, since)
	if err != nil {
		s.logger.ErrorContext(r.Context(), "assignment list read failed", "error", err, "driver", driverID)
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось прочитать разнарядки")
		return
	}

	assignments := make([]assignmentResponse, 0, len(metas))
	for _, meta := range metas {
		raw, _, err := s.store.GetAssignment(meta.ID)
		if err != nil {
			s.logger.ErrorContext(r.Context(), "assignment content read failed", "error", err, "assignment", meta.ID)
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось прочитать разнарядку")
			return
		}
		assignment, ok := parseStoredAssignmentJSON(w, raw)
		if !ok {
			return
		}
		response := assignmentToResponse(assignment)
		if meta.DriverID != driverID {
			markReassigned(&response)
		}
		assignments = append(assignments, response)
	}
	writeJSON(w, http.StatusOK, assignmentsListResponse{Assignments: assignments})
}

func (s *Server) assignmentByID(w http.ResponseWriter, r *http.Request) {
	if !s.authorizedMobile(r) {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "неверный X-Auth-Token")
		return
	}
	id := r.PathValue("id")
	if id == "" {
		writeJSONError(w, http.StatusBadRequest, "validation_error", "пустой идентификатор разнарядки")
		return
	}

	raw, meta, err := s.store.GetAssignment(id)
	if err != nil {
		if errors.Is(err, storage.ErrNotFound) {
			writeJSONError(w, http.StatusNotFound, "not_found", "разнарядка не найдена")
			return
		}
		s.logger.ErrorContext(r.Context(), "assignment read failed", "error", err, "assignment", id)
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось прочитать разнарядку")
		return
	}
	assignment, ok := parseStoredAssignmentJSON(w, raw)
	if !ok {
		return
	}
	response := assignmentToResponse(assignment)
	// driver_id необязателен (старые версии приложения его не шлют): с ним
	// прежний водитель получает разнарядку как отменённую, а не чужую.
	if driverID := r.URL.Query().Get("driver_id"); driverID != "" && meta.DriverID != driverID &&
		slices.Contains(meta.PreviousDriverIDs, driverID) {
		markReassigned(&response)
	}
	writeJSON(w, http.StatusOK, response)
}

const reassignedReason = "Разнарядка передана другому водителю"

func markReassigned(response *assignmentResponse) {
	response.Status = statusCancelled
	response.CancelReason = reassignedReason
}

func (s *Server) assignmentByIDXML(w http.ResponseWriter, r *http.Request) {
	if !s.authorizedMobile(r) {
		writeXMLError(w, http.StatusUnauthorized, "неверный X-Auth-Token")
		return
	}
	id := r.PathValue("id")
	if id == "" {
		writeXMLError(w, http.StatusBadRequest, "пустой идентификатор разнарядки")
		return
	}

	raw, meta, err := s.store.GetAssignment(id)
	if err != nil {
		if errors.Is(err, storage.ErrNotFound) {
			writeXMLError(w, http.StatusNotFound, "разнарядка не найдена")
			return
		}
		s.logger.ErrorContext(r.Context(), "assignment read failed", "error", err, "assignment", id)
		writeXMLError(w, http.StatusInternalServerError, "не удалось прочитать разнарядку")
		return
	}
	writeRawXML(w, http.StatusOK, raw, meta)
}

func (s *Server) receiveEvents(w http.ResponseWriter, r *http.Request) {
	if !s.authorizedMobile(r) {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "неверный X-Auth-Token")
		return
	}

	var req eventsRequest
	if !readJSONBody(w, r, &req) {
		return
	}
	events := eventsRequestToXML(req)
	if err := validateEvents(events); err != nil {
		writeJSONError(w, http.StatusBadRequest, "validation_error", err.Error())
		return
	}

	if s.cfg.OneCBaseURL == "" {
		writeJSONError(w, http.StatusServiceUnavailable, "one_c_not_configured", "ONE_C_BASE_URL не настроен, события не отправлены в 1С")
		return
	}

	body, err := xml.Marshal(events)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось сформировать XML событий")
		return
	}
	body = append([]byte(xml.Header), body...)

	respBody, statusCode, err := s.forwardToOneC(r.Context(), body)
	if err != nil {
		s.logger.ErrorContext(r.Context(), "1c forwarding failed", "error", err)
		writeJSONError(w, http.StatusBadGateway, "one_c_unavailable", "не удалось отправить события в 1С")
		return
	}
	var result resultXML
	if err := xml.Unmarshal(respBody, &result); err != nil {
		s.logger.ErrorContext(r.Context(), "1c response parse failed", "error", err, "status", statusCode)
		writeJSONError(w, http.StatusBadGateway, "bad_one_c_response", "1С вернула некорректный XML-ответ")
		return
	}

	// Водитель принял разнарядку — напоминать больше не о чем. Смотрим на
	// принятые 1С события, а не на сам факт запроса: отбитое событие
	// приёмом не считается.
	accepted := map[string]bool{}
	for _, event := range result.Events {
		if strings.EqualFold(event.Accepted, "true") {
			accepted[event.ID] = true
		}
	}
	for _, event := range req.Events {
		if event.Type == eventTypeAcknowledged && accepted[event.ID] {
			if err := s.store.CancelReminders(event.AssignmentID); err != nil {
				s.logger.ErrorContext(r.Context(), "cancel reminders failed", "error", err, "assignment", event.AssignmentID)
			}
		}
	}

	writeJSON(w, statusCode, resultXMLToJSON(result))
}

// uploadDocument принимает фото подписанного документа с разгрузки.
// В 1С пока не пересылается — контракт события `<Событие>` не рассчитан
// на вложения, а менять его в одностороннем порядке нельзя (AGENTS.md).
// Фото лежит в техническом delivery-cache до момента, когда появится
// согласованный с 1С-командой способ доставки; см. CleanupOldDocuments
// про срок хранения.
func (s *Server) uploadDocument(w http.ResponseWriter, r *http.Request) {
	if !s.authorizedMobile(r) {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "неверный X-Auth-Token")
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxDocumentBody)
	if err := r.ParseMultipartForm(maxDocumentBody); err != nil {
		writeJSONError(w, http.StatusBadRequest, "bad_request", "не удалось прочитать multipart-запрос: "+err.Error())
		return
	}

	photoID := r.FormValue("photo_id")
	driverID := r.FormValue("driver_id")
	assignmentID := r.FormValue("assignment_id")
	tripID := r.FormValue("trip_id")
	if photoID == "" || driverID == "" || tripID == "" {
		writeJSONError(w, http.StatusBadRequest, "validation_error", "обязательны photo_id, driver_id и trip_id")
		return
	}

	file, header, err := r.FormFile("photo")
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "validation_error", "обязателен файл photo")
		return
	}
	defer file.Close()

	data, err := io.ReadAll(io.LimitReader(file, maxDocumentBody))
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "bad_request", "не удалось прочитать файл")
		return
	}

	if len(data) == 0 {
		writeJSONError(w, http.StatusBadRequest, "validation_error", "пустой файл photo")
		return
	}

	ext := strings.ToLower(filepath.Ext(header.Filename))
	contentType := ""
	switch ext {
	case ".jpg", ".jpeg":
		ext = ".jpg"
		contentType = "image/jpeg"
	case ".png":
		contentType = "image/png"
	default:
		writeJSONError(w, http.StatusUnsupportedMediaType, "unsupported_media_type", "ожидается фото JPEG или PNG")
		return
	}

	meta, err := s.store.SaveDocument(photoID, tripID, driverID, assignmentID, ext, data)
	if err != nil {
		s.logger.ErrorContext(r.Context(), "document save failed", "error", err, "trip", tripID)
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось сохранить фото")
		return
	}
	s.logger.InfoContext(r.Context(), "document accepted", "id", meta.ID, "trip", tripID, "driver", driverID, "bytes", len(data))

	if s.cfg.OneCBaseURL == "" {
		writeJSONError(w, http.StatusServiceUnavailable, "one_c_not_configured", "ONE_C_BASE_URL не настроен, фото не отправлено в 1С")
		return
	}

	result, err := s.forwardPhotoToOneC(r.Context(), photoID, assignmentID, tripID, contentType, data)
	if err != nil {
		s.logger.ErrorContext(r.Context(), "1c photo forwarding failed", "error", err, "id", photoID)
		writeJSONError(w, http.StatusBadGateway, "one_c_unavailable", "не удалось отправить фото в 1С")
		return
	}
	if !strings.EqualFold(result.Status, "ok") {
		s.logger.WarnContext(r.Context(), "1c rejected photo", "id", photoID, "error", result.Error)
		writeJSONError(w, http.StatusBadGateway, "one_c_rejected", result.Error)
		return
	}

	if err := s.store.MarkDocumentDelivered(photoID, time.Now().UTC()); err != nil {
		// Фото уже доехало до 1С — не роняем ответ телефону из-за локальной
		// пометки, просто логируем: хуже будет держать файл дольше
		// (pendingTTL вместо deliveredTTL), а не потерять доставку.
		s.logger.ErrorContext(r.Context(), "mark document delivered failed", "error", err, "id", photoID)
	}

	s.logger.InfoContext(r.Context(), "document delivered to 1c", "id", photoID, "trip", tripID)
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "id": photoID})
}

// forwardPhotoToOneC пересылает фото в 1С по отдельному контракту
// (не через XML событий): бинарное тело как есть, метаданные в
// заголовках, идемпотентность по X-Photo-Id. См. docs/backend-api.md.
func (s *Server) forwardPhotoToOneC(
	ctx context.Context,
	photoID, assignmentID, tripID, contentType string,
	data []byte,
) (photoResultXML, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.cfg.OneCPhotoURL, bytes.NewReader(data))
	if err != nil {
		return photoResultXML{}, err
	}
	req.Header.Set("Content-Type", contentType)
	req.Header.Set("X-Photo-Id", photoID)
	req.Header.Set("X-Assignment-Id", assignmentID)
	req.Header.Set("X-Trip-Id", tripID)
	if s.cfg.OneCToken != "" {
		req.Header.Set("X-Auth-Token", s.cfg.OneCToken)
	}
	if s.cfg.OneCUsername != "" || s.cfg.OneCPassword != "" {
		req.SetBasicAuth(s.cfg.OneCUsername, s.cfg.OneCPassword)
	}

	resp, err := s.client.Do(req)
	if err != nil {
		return photoResultXML{}, err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, maxXMLBody))
	if err != nil {
		return photoResultXML{}, err
	}

	var result photoResultXML
	if err := xml.Unmarshal(respBody, &result); err != nil {
		return photoResultXML{}, fmt.Errorf("не удалось разобрать ответ 1С (status %d): %w", resp.StatusCode, err)
	}
	return result, nil
}

func (s *Server) forwardToOneC(ctx context.Context, body []byte) ([]byte, int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.cfg.OneCEventsURL, bytes.NewReader(body))
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/xml; charset=utf-8")
	if s.cfg.OneCToken != "" {
		req.Header.Set("X-Auth-Token", s.cfg.OneCToken)
	}
	if s.cfg.OneCUsername != "" || s.cfg.OneCPassword != "" {
		req.SetBasicAuth(s.cfg.OneCUsername, s.cfg.OneCPassword)
	}

	resp, err := s.client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, maxXMLBody))
	if err != nil {
		return nil, 0, err
	}
	return respBody, resp.StatusCode, nil
}

func (s *Server) authorized1C(r *http.Request) bool {
	if s.cfg.GatewayToken == "" {
		return true
	}
	return r.Header.Get("X-Auth-Token") == s.cfg.GatewayToken
}

func (s *Server) authorizedMobile(r *http.Request) bool {
	token := s.cfg.MobileToken
	if token == "" {
		token = s.cfg.GatewayToken
	}
	if token == "" {
		return true
	}
	return r.Header.Get("X-Auth-Token") == token
}

func (s *Server) requestLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		requestID := r.Header.Get("X-Request-ID")
		if requestID == "" {
			requestID = newRequestID()
		}
		w.Header().Set("X-Request-ID", requestID)

		rw := &responseWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rw, r)
		s.logger.InfoContext(
			r.Context(),
			"http request",
			"request_id", requestID,
			"method", r.Method,
			"path", r.URL.Path,
			"status", rw.status,
			"bytes", rw.bytes,
			"duration", time.Since(start).String(),
			"remote", r.RemoteAddr,
		)
	})
}

func (s *Server) recover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				s.logger.ErrorContext(r.Context(), "panic recovered", "panic", recovered, "stack", string(debug.Stack()))
				writeXMLError(w, http.StatusInternalServerError, "внутренняя ошибка шлюза")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func readXMLBody(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	if !isXMLContent(r.Header.Get("Content-Type")) {
		writeXMLError(w, http.StatusUnsupportedMediaType, "ожидается application/xml; charset=utf-8")
		return nil, false
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxXMLBody))
	if err != nil {
		writeXMLError(w, http.StatusBadRequest, "не удалось прочитать тело запроса")
		return nil, false
	}
	if bytes.HasPrefix(body, []byte{0xEF, 0xBB, 0xBF}) {
		writeXMLError(w, http.StatusBadRequest, "XML должен быть UTF-8 без BOM")
		return nil, false
	}
	return body, true
}

func readJSONBody(w http.ResponseWriter, r *http.Request, dst any) bool {
	if !isJSONContent(r.Header.Get("Content-Type")) {
		writeJSONError(w, http.StatusUnsupportedMediaType, "unsupported_media_type", "ожидается application/json; charset=utf-8")
		return false
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxXMLBody))
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "bad_request", "не удалось прочитать тело запроса")
		return false
	}
	if err := json.Unmarshal(body, dst); err != nil {
		writeJSONError(w, http.StatusBadRequest, "bad_json", "некорректный JSON: "+err.Error())
		return false
	}
	return true
}

func isXMLContent(contentType string) bool {
	mediaType, params, err := mime.ParseMediaType(contentType)
	if err != nil {
		return false
	}
	charset := strings.ToLower(params["charset"])
	if charset != "" && charset != "utf-8" {
		return false
	}
	mediaType = strings.ToLower(strings.TrimSpace(mediaType))
	return mediaType == "application/xml" || mediaType == "text/xml"
}

func isJSONContent(contentType string) bool {
	mediaType, params, err := mime.ParseMediaType(contentType)
	if err != nil {
		return false
	}
	charset := strings.ToLower(params["charset"])
	if charset != "" && charset != "utf-8" {
		return false
	}
	mediaType = strings.ToLower(strings.TrimSpace(mediaType))
	return mediaType == "application/json"
}

func parseStoredAssignmentJSON(w http.ResponseWriter, raw []byte) (assignmentXML, bool) {
	var assignment assignmentXML
	if err := xml.Unmarshal(raw, &assignment); err != nil {
		writeJSONError(w, http.StatusInternalServerError, "bad_cached_assignment", "сохранённая разнарядка не читается как XML")
		return assignmentXML{}, false
	}
	return assignment, true
}

func writeXMLError(w http.ResponseWriter, statusCode int, message string) {
	writeXML(w, statusCode, resultXML{Status: "error", Error: message})
}

func writeXML(w http.ResponseWriter, statusCode int, value resultXML) {
	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	w.WriteHeader(statusCode)
	_, _ = w.Write([]byte(xml.Header))
	_ = xml.NewEncoder(w).Encode(value)
}

func writeJSONError(w http.ResponseWriter, statusCode int, code string, message string) {
	writeJSON(w, statusCode, errorResponse{Code: code, Error: message})
}

func writeJSON(w http.ResponseWriter, statusCode int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(statusCode)
	_ = json.NewEncoder(w).Encode(value)
}

func writeRawXML(w http.ResponseWriter, statusCode int, raw []byte, meta storage.AssignmentMeta) {
	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	w.Header().Set("X-Assignment-ID", meta.ID)
	w.Header().Set("X-Assignment-Version", strconv.Itoa(meta.Version))
	w.WriteHeader(statusCode)
	_, _ = w.Write(raw)
}

type responseWriter struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (w *responseWriter) WriteHeader(statusCode int) {
	w.status = statusCode
	w.ResponseWriter.WriteHeader(statusCode)
}

func (w *responseWriter) Write(data []byte) (int, error) {
	n, err := w.ResponseWriter.Write(data)
	w.bytes += n
	return n, err
}

func newRequestID() string {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return hex.EncodeToString(raw[:])
}
