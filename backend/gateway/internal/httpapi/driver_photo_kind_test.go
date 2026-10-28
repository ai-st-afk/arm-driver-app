package httpapi

import (
	"bytes"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"arm-driver-app/backend/gateway/internal/config"
)

const (
	testDriverA = "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162"
	testDriverB = "9a4b2d3f-5c8e-4b02-8d22-3f6e7d1b8c42"
)

func postTestAssignment(t *testing.T, api *Server, id string, version int, driverID, name string) {
	t.Helper()
	xmlBody := `<?xml version="1.0" encoding="UTF-8"?>
<Разнарядка>
  <Идентификатор>` + id + `</Идентификатор>
  <Версия>` + string(rune('0'+version)) + `</Версия>
  <Номер>ПрТр-1</Номер>
  <ДатаВыезда>2026-10-28</ДатаВыезда>
  <Статус>Активна</Статус>
  <Водитель><Идентификатор>` + driverID + `</Идентификатор><ФИО>` + name + `</ФИО></Водитель>
  <Машина><Идентификатор>7a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d</Идентификатор></Машина>
</Разнарядка>`
	req := httptest.NewRequest(http.MethodPost, "/api/1c/assignments", strings.NewReader(xmlBody))
	req.Header.Set("Content-Type", "application/xml; charset=utf-8")
	req.Header.Set("X-Auth-Token", "one-c-token")
	rec := httptest.NewRecorder()
	api.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("post assignment status = %d, body = %s", rec.Code, rec.Body.String())
	}
}

func getDriver(api *Server, id string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, "/api/mobile/drivers/"+id, nil)
	req.Header.Set("X-Auth-Token", "mobile-token")
	rec := httptest.NewRecorder()
	api.Routes().ServeHTTP(rec, req)
	return rec
}

func TestDriverNameReturnedFromStoredAssignment(t *testing.T) {
	api := newTestServer(t, config.Config{GatewayToken: "one-c-token", MobileToken: "mobile-token"})
	postTestAssignment(t, api, "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f", 1, testDriverA, "Костенко Павел")

	rec := getDriver(api, testDriverA)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"name":"Костенко Павел"`) {
		t.Fatalf("driver name missing: %s", rec.Body.String())
	}
}

func TestUnknownDriverIsNotFound(t *testing.T) {
	api := newTestServer(t, config.Config{GatewayToken: "one-c-token", MobileToken: "mobile-token"})

	rec := getDriver(api, testDriverA)

	if rec.Code != http.StatusNotFound || !strings.Contains(rec.Body.String(), `"code":"driver_unknown"`) {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
}

// Разнарядку передали водителю B: в её XML уже ФИО B, отдавать его
// прежнему водителю A как «его имя» нельзя.
func TestReassignedAssignmentDoesNotLeakNewDriverName(t *testing.T) {
	api := newTestServer(t, config.Config{GatewayToken: "one-c-token", MobileToken: "mobile-token"})
	postTestAssignment(t, api, "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f", 1, testDriverA, "Костенко Павел")
	postTestAssignment(t, api, "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f", 2, testDriverB, "Иванов Иван")

	rec := getDriver(api, testDriverA)

	if strings.Contains(rec.Body.String(), "Иванов") {
		t.Fatalf("previous driver got the new driver's name: %s", rec.Body.String())
	}
}

func uploadPhoto(t *testing.T, api *Server, fields map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	for k, v := range fields {
		_ = writer.WriteField(k, v)
	}
	part, _ := writer.CreateFormFile("photo", "photo.jpg")
	_, _ = part.Write([]byte("fake-jpeg-bytes"))
	_ = writer.Close()
	req := httptest.NewRequest(http.MethodPost, "/api/mobile/documents", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("X-Auth-Token", "mobile-token")
	rec := httptest.NewRecorder()
	api.Routes().ServeHTTP(rec, req)
	return rec
}

func baseUploadFields() map[string]string {
	return map[string]string{
		"photo_id":      "b3e2648a-9f42-4c18-b994-b03f4a705977",
		"driver_id":     testDriverA,
		"assignment_id": "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f",
		"trip_id":       "e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b",
	}
}

func TestPhotoKindAndEventForwardedToOneC(t *testing.T) {
	var gotKind, gotEvent string
	oneC := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotKind = r.Header.Get("X-Photo-Kind")
		gotEvent = r.Header.Get("X-Event-Id")
		_, _ = w.Write([]byte(`<Результат статус="ok" ид="` + r.Header.Get("X-Photo-Id") + `"/>`))
	}))
	defer oneC.Close()
	api := newTestServer(t, config.Config{MobileToken: "mobile-token", OneCBaseURL: oneC.URL})

	fields := baseUploadFields()
	fields["kind"] = "truck_side"
	fields["event_id"] = "8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31"
	rec := uploadPhoto(t, api, fields)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if gotKind != "truck_side" || gotEvent != "8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31" {
		t.Fatalf("forwarded kind=%q event=%q", gotKind, gotEvent)
	}
}

// Старые версии приложения не шлют вид и событие — фото уходит как раньше,
// без пустых заголовков.
func TestPhotoWithoutKindHasNoExtraHeaders(t *testing.T) {
	var hasKind, hasEvent bool
	oneC := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, hasKind = r.Header["X-Photo-Kind"]
		_, hasEvent = r.Header["X-Event-Id"]
		_, _ = w.Write([]byte(`<Результат статус="ok" ид="` + r.Header.Get("X-Photo-Id") + `"/>`))
	}))
	defer oneC.Close()
	api := newTestServer(t, config.Config{MobileToken: "mobile-token", OneCBaseURL: oneC.URL})

	rec := uploadPhoto(t, api, baseUploadFields())

	if rec.Code != http.StatusOK || hasKind || hasEvent {
		t.Fatalf("status = %d, kind header=%v, event header=%v", rec.Code, hasKind, hasEvent)
	}
}

// Значения попадают в заголовки к 1С — ничего, кроме ожидаемого формата.
func TestPhotoKindWithUnexpectedCharactersRejected(t *testing.T) {
	api := newTestServer(t, config.Config{MobileToken: "mobile-token", OneCBaseURL: "http://127.0.0.1:1"})

	fields := baseUploadFields()
	fields["kind"] = "truck\r\nX-Evil: 1"
	if rec := uploadPhoto(t, api, fields); rec.Code != http.StatusBadRequest {
		t.Fatalf("bad kind status = %d, body = %s", rec.Code, rec.Body.String())
	}

	fields = baseUploadFields()
	fields["event_id"] = "not-a-guid"
	if rec := uploadPhoto(t, api, fields); rec.Code != http.StatusBadRequest {
		t.Fatalf("bad event status = %d, body = %s", rec.Code, rec.Body.String())
	}
}
