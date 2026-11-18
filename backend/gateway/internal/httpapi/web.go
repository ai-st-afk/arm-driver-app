package httpapi

import (
	"bytes"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"encoding/xml"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"arm-driver-app/backend/gateway/internal/storage"
)

func (s *Server) webRedirect(w http.ResponseWriter, r *http.Request) {
	http.Redirect(w, r, "web/", http.StatusMovedPermanently)
}

func (s *Server) webStatic(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
	if strings.HasSuffix(r.URL.Path, ".webmanifest") {
		w.Header().Set("Content-Type", "application/manifest+json")
	}
	http.StripPrefix("/web/", http.FileServer(http.Dir(s.cfg.WebDir))).ServeHTTP(w, r)
}

func (s *Server) webAdminAuthorized(r *http.Request) bool {
	provided := r.Header.Get("X-Web-Admin-Token")
	if s.cfg.WebAdminToken == "" || len(provided) != len(s.cfg.WebAdminToken) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(provided), []byte(s.cfg.WebAdminToken)) == 1
}

func (s *Server) webCreatePairing(w http.ResponseWriter, r *http.Request) {
	if !s.webAdminAuthorized(r) {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "нет доступа")
		return
	}
	var body struct {
		DriverID string `json:"driver_id"`
	}
	if !readJSONBody(w, r, &body) {
		return
	}
	if !guidPattern.MatchString(body.DriverID) {
		writeJSONError(w, http.StatusBadRequest, "validation_error", "нужен GUID водителя")
		return
	}
	code, err := s.store.CreateWebPairing(strings.ToLower(body.DriverID), time.Now())
	if err != nil {
		s.logger.Error("web pairing creation failed", "error", err)
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось создать код")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusCreated, map[string]any{"code": code, "expires_in_seconds": 900})
}

func (s *Server) webRevokeDriver(w http.ResponseWriter, r *http.Request) {
	if !s.webAdminAuthorized(r) {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "нет доступа")
		return
	}
	var body struct {
		DriverID string `json:"driver_id"`
	}
	if !readJSONBody(w, r, &body) {
		return
	}
	if !guidPattern.MatchString(body.DriverID) {
		writeJSONError(w, http.StatusBadRequest, "validation_error", "нужен GUID водителя")
		return
	}
	if err := s.store.RevokeWebDriver(strings.ToLower(body.DriverID)); err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось отозвать доступ")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) webCookiePath() string {
	path := s.cfg.WebCookiePath
	if path == "" || path[0] != '/' {
		return "/"
	}
	return path
}

func (s *Server) webCookieName() string {
	if s.cfg.WebCookieName == "" {
		return "arm_driver_web"
	}
	return s.cfg.WebCookieName
}

func webMutationAllowed(r *http.Request) bool {
	// POST с произвольного сайта не сможет добавить этот заголовок без CORS.
	return r.Header.Get("X-Web-Request") == "1"
}

func (s *Server) webPair(w http.ResponseWriter, r *http.Request) {
	if !webMutationAllowed(r) {
		writeJSONError(w, http.StatusForbidden, "forbidden", "запрос отклонён")
		return
	}
	var body struct {
		Code string `json:"code"`
	}
	if !readJSONBody(w, r, &body) {
		return
	}
	if len(body.Code) != 24 {
		writeJSONError(w, http.StatusBadRequest, "invalid_code", "неверный код привязки")
		return
	}
	driverID, token, err := s.store.ConsumeWebPairing(strings.ToLower(body.Code), time.Now())
	if errors.Is(err, storage.ErrInvalidWebCode) {
		writeJSONError(w, http.StatusUnauthorized, "invalid_code", "код недействителен или истёк")
		return
	}
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось привязать телефон")
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name: s.webCookieName(), Value: token, Path: s.webCookiePath(),
		Expires: time.Now().Add(90 * 24 * time.Hour), HttpOnly: true,
		Secure:   !strings.HasPrefix(r.Host, "localhost") && !strings.HasPrefix(r.Host, "127.0.0.1"),
		SameSite: http.SameSiteStrictMode,
	})
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]string{"driver_id": driverID})
}

func (s *Server) webDriver(r *http.Request) (string, bool) {
	cookie, err := r.Cookie(s.webCookieName())
	if err != nil || cookie.Value == "" {
		return "", false
	}
	driver, err := s.store.WebSessionDriver(cookie.Value, time.Now())
	return driver, err == nil
}

func (s *Server) webRequireDriver(w http.ResponseWriter, r *http.Request) (string, bool) {
	driver, ok := s.webDriver(r)
	if !ok {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "требуется привязка телефона")
	}
	return driver, ok
}

func (s *Server) webSessionInfo(w http.ResponseWriter, r *http.Request) {
	driver, ok := s.webRequireDriver(w, r)
	if !ok {
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]string{"driver_id": driver})
}

func (s *Server) webLogout(w http.ResponseWriter, r *http.Request) {
	if !webMutationAllowed(r) {
		writeJSONError(w, http.StatusForbidden, "forbidden", "запрос отклонён")
		return
	}
	if cookie, err := r.Cookie(s.webCookieName()); err == nil {
		_ = s.store.RevokeWebSession(cookie.Value)
	}
	http.SetCookie(w, &http.Cookie{Name: s.webCookieName(), Path: s.webCookiePath(), MaxAge: -1,
		HttpOnly: true, Secure: !strings.HasPrefix(r.Host, "localhost") && !strings.HasPrefix(r.Host, "127.0.0.1"), SameSite: http.SameSiteStrictMode})
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) webMobileRequest(r *http.Request) *http.Request {
	copy := r.Clone(r.Context())
	copy.Header = r.Header.Clone()
	token := s.cfg.MobileToken
	if token == "" {
		token = s.cfg.GatewayToken
	}
	copy.Header.Set("X-Auth-Token", token)
	return copy
}

func (s *Server) webAssignments(w http.ResponseWriter, r *http.Request) {
	driver, ok := s.webRequireDriver(w, r)
	if !ok {
		return
	}
	copy := s.webMobileRequest(r)
	query := copy.URL.Query()
	query.Set("driver_id", driver)
	copy.URL.RawQuery = query.Encode()
	s.listAssignments(w, copy)
}

func (s *Server) webOwnsAssignment(driver, id string) (bool, error) {
	_, meta, err := s.store.GetAssignment(id)
	if errors.Is(err, storage.ErrNotFound) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if meta.DriverID == driver {
		return true, nil
	}
	for _, previous := range meta.PreviousDriverIDs {
		if previous == driver {
			return true, nil
		}
	}
	return false, nil
}

func (s *Server) webAssignment(w http.ResponseWriter, r *http.Request) {
	driver, ok := s.webRequireDriver(w, r)
	if !ok {
		return
	}
	owned, err := s.webOwnsAssignment(driver, r.PathValue("id"))
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось проверить разнарядку")
		return
	}
	if !owned {
		writeJSONError(w, http.StatusNotFound, "not_found", "разнарядка не найдена")
		return
	}
	copy := s.webMobileRequest(r)
	query := copy.URL.Query()
	query.Set("driver_id", driver)
	copy.URL.RawQuery = query.Encode()
	s.assignmentByID(w, copy)
}

func (s *Server) webTripInAssignment(assignmentID, tripID string) bool {
	raw, _, err := s.store.GetAssignment(assignmentID)
	if err != nil {
		return false
	}
	var assignment assignmentXML
	if xml.Unmarshal(raw, &assignment) != nil {
		return false
	}
	for _, trip := range assignment.Trips {
		if trip.ID == tripID {
			return true
		}
	}
	return false
}

func (s *Server) webEvents(w http.ResponseWriter, r *http.Request) {
	driver, ok := s.webRequireDriver(w, r)
	if !ok {
		return
	}
	if !webMutationAllowed(r) {
		writeJSONError(w, http.StatusForbidden, "forbidden", "запрос отклонён")
		return
	}
	var body eventsRequest
	if !readJSONBody(w, r, &body) {
		return
	}
	for _, event := range body.Events {
		owned, err := s.webOwnsAssignment(driver, event.AssignmentID)
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось проверить разнарядку")
			return
		}
		if event.DriverID != driver || !owned || (event.TripID != "" && !s.webTripInAssignment(event.AssignmentID, event.TripID)) {
			writeJSONError(w, http.StatusForbidden, "forbidden", "событие не относится к этому водителю")
			return
		}
	}
	raw, _ := json.Marshal(body)
	copy := s.webMobileRequest(r)
	copy.Body = io.NopCloser(bytes.NewReader(raw))
	s.receiveEvents(w, copy)
}

func (s *Server) webDocument(w http.ResponseWriter, r *http.Request) {
	driver, ok := s.webRequireDriver(w, r)
	if !ok {
		return
	}
	if !webMutationAllowed(r) {
		writeJSONError(w, http.StatusForbidden, "forbidden", "запрос отклонён")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxDocumentBody)
	if err := r.ParseMultipartForm(maxDocumentBody); err != nil {
		writeJSONError(w, http.StatusBadRequest, "bad_request", "не удалось прочитать фото")
		return
	}
	if r.MultipartForm != nil {
		defer r.MultipartForm.RemoveAll()
	}
	assignmentID, tripID := r.FormValue("assignment_id"), r.FormValue("trip_id")
	owned, err := s.webOwnsAssignment(driver, assignmentID)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось проверить разнарядку")
		return
	}
	if r.FormValue("driver_id") != driver || !owned || !s.webTripInAssignment(assignmentID, tripID) {
		writeJSONError(w, http.StatusForbidden, "forbidden", "фото не относится к этому водителю")
		return
	}
	s.uploadDocument(w, s.webMobileRequest(r))
}

func (s *Server) webPushKey(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.webRequireDriver(w, r); !ok {
		return
	}
	if s.cfg.WebPushPublicKey == "" {
		writeJSONError(w, http.StatusServiceUnavailable, "push_unavailable", "уведомления пока не настроены")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]string{"public_key": s.cfg.WebPushPublicKey})
}

type webPushInput struct {
	Endpoint string `json:"endpoint"`
	Keys     struct {
		Auth   string `json:"auth"`
		P256dh string `json:"p256dh"`
	} `json:"keys"`
}

func validWebPushEndpoint(raw string) bool {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme != "https" || parsed.User != nil || parsed.Port() != "" {
		return false
	}
	host := strings.ToLower(parsed.Hostname())
	return strings.HasSuffix(host, ".push.apple.com") && len(raw) < 2048 && parsed.Path != ""
}

func (s *Server) webSavePush(w http.ResponseWriter, r *http.Request) {
	driver, ok := s.webRequireDriver(w, r)
	if !ok {
		return
	}
	if !webMutationAllowed(r) {
		writeJSONError(w, http.StatusForbidden, "forbidden", "запрос отклонён")
		return
	}
	var body webPushInput
	if !readJSONBody(w, r, &body) {
		return
	}
	auth, authErr := base64.RawURLEncoding.DecodeString(body.Keys.Auth)
	p256dh, keyErr := base64.RawURLEncoding.DecodeString(body.Keys.P256dh)
	if !validWebPushEndpoint(body.Endpoint) || authErr != nil || keyErr != nil || len(auth) != 16 || len(p256dh) != 65 {
		writeJSONError(w, http.StatusBadRequest, "validation_error", "некорректная подписка уведомлений")
		return
	}
	if err := s.store.SaveWebPush(storage.WebPushSubscription{DriverID: driver, Endpoint: body.Endpoint, Auth: body.Keys.Auth, P256dh: body.Keys.P256dh}); err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось сохранить подписку")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) webRemovePush(w http.ResponseWriter, r *http.Request) {
	driver, ok := s.webRequireDriver(w, r)
	if !ok {
		return
	}
	if !webMutationAllowed(r) {
		writeJSONError(w, http.StatusForbidden, "forbidden", "запрос отклонён")
		return
	}
	var body struct {
		Endpoint string `json:"endpoint"`
	}
	if !readJSONBody(w, r, &body) {
		return
	}
	items, err := s.store.ListWebPush(driver)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось проверить подписку")
		return
	}
	for _, item := range items {
		if item.Endpoint == body.Endpoint {
			if err := s.store.RemoveWebPush(item.Endpoint); err != nil {
				writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось удалить подписку")
				return
			}
			break
		}
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
