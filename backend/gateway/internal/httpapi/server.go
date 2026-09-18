package httpapi

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"path/filepath"
	"runtime/debug"
	"strconv"
	"strings"
	"time"

	"arm-driver-app/backend/gateway/internal/config"
	"arm-driver-app/backend/gateway/internal/push"
	"arm-driver-app/backend/gateway/internal/storage"
)

const maxXMLBody = 4 << 20
const maxDocumentBody = 12 << 20

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
	mux.HandleFunc("GET /api/mobile/assignments/current", s.currentAssignment)
	mux.HandleFunc("GET /api/mobile/assignments/current/xml", s.currentAssignmentXML)
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

	if err := s.push.SendAssignment(r.Context(), push.AssignmentNotification{
		AssignmentID: assignment.ID,
		Version:      assignment.Version,
		DriverID:     assignment.Driver.ID,
	}); err != nil {
		s.logger.ErrorContext(r.Context(), "assignment push failed", "error", err, "assignment", assignment.ID)
		writeXMLError(w, http.StatusBadGateway, "разнарядка сохранена, но push не отправлен")
		return
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

func (s *Server) currentAssignment(w http.ResponseWriter, r *http.Request) {
	if !s.authorizedMobile(r) {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "неверный X-Auth-Token")
		return
	}
	driverID := r.URL.Query().Get("driver_id")
	if driverID == "" {
		writeJSONError(w, http.StatusBadRequest, "validation_error", "обязателен query-параметр driver_id")
		return
	}

	raw, _, err := s.store.GetLatestAssignmentForDriver(driverID)
	if err != nil {
		if errors.Is(err, storage.ErrNotFound) {
			writeJSONError(w, http.StatusNotFound, "not_found", "разнарядка не найдена")
			return
		}
		s.logger.ErrorContext(r.Context(), "assignment read failed", "error", err, "driver", driverID)
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось прочитать разнарядку")
		return
	}
	assignment, ok := parseStoredAssignmentJSON(w, raw)
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, assignmentToResponse(assignment))
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

	raw, _, err := s.store.GetAssignment(id)
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
	writeJSON(w, http.StatusOK, assignmentToResponse(assignment))
}

func (s *Server) currentAssignmentXML(w http.ResponseWriter, r *http.Request) {
	if !s.authorizedMobile(r) {
		writeXMLError(w, http.StatusUnauthorized, "неверный X-Auth-Token")
		return
	}
	driverID := r.URL.Query().Get("driver_id")
	if driverID == "" {
		writeXMLError(w, http.StatusBadRequest, "обязателен query-параметр driver_id")
		return
	}

	raw, meta, err := s.store.GetLatestAssignmentForDriver(driverID)
	if err != nil {
		if errors.Is(err, storage.ErrNotFound) {
			writeXMLError(w, http.StatusNotFound, "разнарядка не найдена")
			return
		}
		s.logger.ErrorContext(r.Context(), "assignment read failed", "error", err, "driver", driverID)
		writeXMLError(w, http.StatusInternalServerError, "не удалось прочитать разнарядку")
		return
	}
	writeRawXML(w, http.StatusOK, raw, meta)
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

	driverID := r.FormValue("driver_id")
	assignmentID := r.FormValue("assignment_id")
	tripID := r.FormValue("trip_id")
	if driverID == "" || tripID == "" {
		writeJSONError(w, http.StatusBadRequest, "validation_error", "обязательны driver_id и trip_id")
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

	ext := strings.ToLower(filepath.Ext(header.Filename))
	if ext != ".jpg" && ext != ".jpeg" && ext != ".png" {
		writeJSONError(w, http.StatusUnsupportedMediaType, "unsupported_media_type", "ожидается фото JPEG или PNG")
		return
	}

	meta, err := s.store.SaveDocument(tripID, driverID, assignmentID, ext, data)
	if err != nil {
		s.logger.ErrorContext(r.Context(), "document save failed", "error", err, "trip", tripID)
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось сохранить фото")
		return
	}

	s.logger.InfoContext(r.Context(), "document accepted", "id", meta.ID, "trip", tripID, "driver", driverID, "bytes", len(data))
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "id": meta.ID})
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
