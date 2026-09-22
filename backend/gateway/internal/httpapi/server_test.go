package httpapi

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"arm-driver-app/backend/gateway/internal/config"
	"arm-driver-app/backend/gateway/internal/push"
	"arm-driver-app/backend/gateway/internal/storage"
)

func TestMobileEventsJSONForwardedToOneCXML(t *testing.T) {
	var forwarded string
	oneC := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, err := io.ReadAll(r.Body)
		if err != nil {
			t.Fatalf("read forwarded body: %v", err)
		}
		forwarded = string(raw)
		w.Header().Set("Content-Type", "application/xml; charset=utf-8")
		_, _ = w.Write([]byte(`<?xml version="1.0" encoding="UTF-8"?><Результат статус="ok"><Событие ид="8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31" принято="true"/></Результат>`))
	}))
	defer oneC.Close()

	api := newTestServer(t, config.Config{
		GatewayToken:  "one-c-token",
		MobileToken:   "mobile-token",
		OneCBaseURL:   oneC.URL,
		OneCEventsURL: oneC.URL + "/prtr_driver/events",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/mobile/events", strings.NewReader(`{
		"events": [{
			"id": "8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31",
			"type": "ПрибылНаПогрузку",
			"driver_id": "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162",
			"assignment_id": "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f",
			"trip_id": "e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b",
			"time": "2026-09-10T07:34:12+03:00"
		}]
	}`))
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("X-Auth-Token", "mobile-token")
	rec := httptest.NewRecorder()

	api.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(forwarded, "<Тип>ПрибылНаПогрузку</Тип>") {
		t.Fatalf("forwarded XML does not contain event type: %s", forwarded)
	}
	if !strings.Contains(rec.Body.String(), `"accepted":true`) {
		t.Fatalf("mobile response does not contain accepted=true: %s", rec.Body.String())
	}
}

func TestAssignmentXMLStoredAndReturnedAsMobileJSON(t *testing.T) {
	api := newTestServer(t, config.Config{
		GatewayToken: "one-c-token",
		MobileToken:  "mobile-token",
	})
	assignment := `<?xml version="1.0" encoding="UTF-8"?>
<Разнарядка>
  <Идентификатор>b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f</Идентификатор>
  <Версия>3</Версия>
  <Номер>ПрТр-000412</Номер>
  <ДатаВыезда>2026-09-10</ДатаВыезда>
  <Статус>Активна</Статус>
  <Водитель><Идентификатор>3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162</Идентификатор><ФИО>Иванов Иван Иванович</ФИО></Водитель>
  <Машина><Идентификатор>7a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d</Идентификатор><Наименование>КАМАЗ 65115</Наименование><ГосНомер>А123ВС43</ГосНомер></Машина>
  <ПланВыезда>2026-09-10T07:00:00+03:00</ПланВыезда>
  <ПланВозврата>2026-09-10T17:30:00+03:00</ПланВозврата>
  <Ездки><Ездка>
    <Идентификатор>e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b</Идентификатор>
    <Порядок>1</Порядок>
    <Статус>Назначена</Статус>
    <ПунктПогрузки><Наименование>Завод ЖБИ</Наименование><Адрес>Киров</Адрес></ПунктПогрузки>
    <ПунктРазгрузки><Наименование>Объект</Наименование><Адрес>Окуни</Адрес></ПунктРазгрузки>
    <ПланПогрузки>2026-09-10T07:30:00+03:00</ПланПогрузки>
    <ПланРазгрузки>2026-09-10T09:00:00+03:00</ПланРазгрузки>
  </Ездка></Ездки>
</Разнарядка>`

	post := httptest.NewRequest(http.MethodPost, "/api/1c/assignments", strings.NewReader(assignment))
	post.Header.Set("Content-Type", "application/xml; charset=utf-8")
	post.Header.Set("X-Auth-Token", "one-c-token")
	postRec := httptest.NewRecorder()
	api.Routes().ServeHTTP(postRec, post)
	if postRec.Code != http.StatusOK {
		t.Fatalf("assignment post status = %d, body = %s", postRec.Code, postRec.Body.String())
	}

	get := httptest.NewRequest(http.MethodGet, "/api/mobile/assignments?driver_id=3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162", nil)
	get.Header.Set("X-Auth-Token", "mobile-token")
	getRec := httptest.NewRecorder()
	api.Routes().ServeHTTP(getRec, get)

	if getRec.Code != http.StatusOK {
		t.Fatalf("assignment get status = %d, body = %s", getRec.Code, getRec.Body.String())
	}
	if !strings.Contains(getRec.Body.String(), `"number":"ПрТр-000412"`) {
		t.Fatalf("mobile JSON does not contain assignment number: %s", getRec.Body.String())
	}
	if !strings.Contains(getRec.Body.String(), `"address":"Окуни"`) {
		t.Fatalf("mobile JSON does not contain short address: %s", getRec.Body.String())
	}
}

func TestListAssignmentsReturnsAllForDriverNotJustLatest(t *testing.T) {
	api := newTestServer(t, config.Config{
		GatewayToken: "one-c-token",
		MobileToken:  "mobile-token",
	})

	post := func(id, number string) {
		t.Helper()
		assignment := `<?xml version="1.0" encoding="UTF-8"?>
<Разнарядка>
  <Идентификатор>` + id + `</Идентификатор>
  <Версия>1</Версия>
  <Номер>` + number + `</Номер>
  <Статус>Активна</Статус>
  <Водитель><Идентификатор>3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162</Идентификатор><ФИО>Иванов Иван Иванович</ФИО></Водитель>
  <Машина><Идентификатор>7a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d</Идентификатор><Наименование>КАМАЗ 65115</Наименование><ГосНомер>А123ВС43</ГосНомер></Машина>
</Разнарядка>`
		req := httptest.NewRequest(http.MethodPost, "/api/1c/assignments", strings.NewReader(assignment))
		req.Header.Set("Content-Type", "application/xml; charset=utf-8")
		req.Header.Set("X-Auth-Token", "one-c-token")
		rec := httptest.NewRecorder()
		api.Routes().ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("assignment post status = %d, body = %s", rec.Code, rec.Body.String())
		}
	}

	// Второй разнарядке для того же водителя раньше некуда было деваться —
	// latest_by_driver перезаписывался, и первая пропадала из ответа.
	post("b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f", "ПрТр-000412")
	post("c2f5g318-0b4d-5e26-9f88-1d7c6b5e4f30", "ПрТр-000413")

	get := httptest.NewRequest(http.MethodGet, "/api/mobile/assignments?driver_id=3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162", nil)
	get.Header.Set("X-Auth-Token", "mobile-token")
	getRec := httptest.NewRecorder()
	api.Routes().ServeHTTP(getRec, get)

	if getRec.Code != http.StatusOK {
		t.Fatalf("assignment list status = %d, body = %s", getRec.Code, getRec.Body.String())
	}
	body := getRec.Body.String()
	if !strings.Contains(body, `"number":"ПрТр-000412"`) || !strings.Contains(body, `"number":"ПрТр-000413"`) {
		t.Fatalf("expected both assignments in list, got: %s", body)
	}
}

// recordingSender — тестовый push.Sender, который запоминает вид каждого
// уведомления вместо реальной отправки в FCM.
type recordingSender struct {
	kinds []push.Kind
}

func (s *recordingSender) SendAssignment(_ context.Context, notification push.AssignmentNotification) error {
	s.kinds = append(s.kinds, notification.Kind)
	return nil
}

func TestAssignmentRevisionAfterAcceptanceDoesNotRestartReminders(t *testing.T) {
	oneC := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/xml; charset=utf-8")
		_, _ = w.Write([]byte(`<?xml version="1.0" encoding="UTF-8"?><Результат статус="ok"><Событие ид="8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31" принято="true"/></Результат>`))
	}))
	defer oneC.Close()

	cfg := config.Config{
		GatewayToken:                    "one-c-token",
		MobileToken:                     "mobile-token",
		OneCBaseURL:                     oneC.URL,
		OneCEventsURL:                   oneC.URL + "/prtr_driver/events",
		AssignmentReminderFirstMinutes:  30,
		AssignmentReminderSecondMinutes: 50,
	}
	cfg.DataDir = t.TempDir()
	store, err := storage.New(cfg.DataDir)
	if err != nil {
		t.Fatalf("storage init: %v", err)
	}
	sender := &recordingSender{}
	api := NewServer(cfg, slog.New(slog.NewTextHandler(io.Discard, nil)), http.DefaultClient, store, sender)

	driverID := "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162"
	assignmentXML := func(version int) string {
		return `<?xml version="1.0" encoding="UTF-8"?>
<Разнарядка>
  <Идентификатор>b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f</Идентификатор>
  <Версия>` + strconv.Itoa(version) + `</Версия>
  <Статус>Активна</Статус>
  <Водитель><Идентификатор>` + driverID + `</Идентификатор><ФИО>Иванов Иван Иванович</ФИО></Водитель>
  <Машина><Идентификатор>7a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d</Идентификатор><Наименование>КАМАЗ 65115</Наименование><ГосНомер>А123ВС43</ГосНомер></Машина>
</Разнарядка>`
	}
	postAssignment := func(version int) {
		t.Helper()
		req := httptest.NewRequest(http.MethodPost, "/api/1c/assignments", strings.NewReader(assignmentXML(version)))
		req.Header.Set("Content-Type", "application/xml; charset=utf-8")
		req.Header.Set("X-Auth-Token", "one-c-token")
		rec := httptest.NewRecorder()
		api.Routes().ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("assignment post (v%d) status = %d, body = %s", version, rec.Code, rec.Body.String())
		}
	}

	// v1: первая разнарядка — напоминания должны быть поставлены.
	postAssignment(1)
	due, err := store.DueReminders(time.Now().UTC().Add(31 * time.Minute))
	if err != nil {
		t.Fatalf("due reminders after v1: %v", err)
	}
	if len(due) == 0 {
		t.Fatalf("expected a reminder scheduled after the first version")
	}

	// Водитель принимает разнарядку — 1С отвечает accepted:true на Ознакомление.
	req := httptest.NewRequest(http.MethodPost, "/api/mobile/events", strings.NewReader(`{
		"events": [{
			"id": "8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31",
			"type": "Ознакомление",
			"driver_id": "`+driverID+`",
			"assignment_id": "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f",
			"time": "2026-09-10T07:34:12+03:00"
		}]
	}`))
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("X-Auth-Token", "mobile-token")
	rec := httptest.NewRecorder()
	api.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("acknowledge status = %d, body = %s", rec.Code, rec.Body.String())
	}

	// v2: 1С правит ездку и присылает ту же разнарядку заново — reminders
	// не должны воскреснуть, водитель её уже принял.
	postAssignment(2)
	due, err = store.DueReminders(time.Now().UTC().Add(31 * time.Minute))
	if err != nil {
		t.Fatalf("due reminders after v2: %v", err)
	}
	if len(due) != 0 {
		t.Fatalf("reminders resurrected after an accepted revision: %+v", due)
	}

	if len(sender.kinds) != 2 || sender.kinds[0] != push.KindNew || sender.kinds[1] != push.KindUpdated {
		t.Fatalf("push kinds = %v, want [assignment_new assignment_updated]", sender.kinds)
	}
}

func TestDocumentUploadForwardedToOneCAndMarkedDelivered(t *testing.T) {
	var gotPhotoID, gotAssignmentID, gotTripID, gotContentType string
	var gotBody []byte
	oneC := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPhotoID = r.Header.Get("X-Photo-Id")
		gotAssignmentID = r.Header.Get("X-Assignment-Id")
		gotTripID = r.Header.Get("X-Trip-Id")
		gotContentType = r.Header.Get("Content-Type")
		gotBody, _ = io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/xml; charset=utf-8")
		_, _ = w.Write([]byte(`<?xml version="1.0" encoding="UTF-8"?><Результат статус="ok" ид="` + gotPhotoID + `"/>`))
	}))
	defer oneC.Close()

	api := newTestServer(t, config.Config{MobileToken: "mobile-token", OneCBaseURL: oneC.URL})

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	_ = writer.WriteField("photo_id", "b3e2648a-9f42-4c18-b994-b03f4a705977")
	_ = writer.WriteField("driver_id", "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162")
	_ = writer.WriteField("assignment_id", "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f")
	_ = writer.WriteField("trip_id", "e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b")
	part, err := writer.CreateFormFile("photo", "накладная.jpg")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write([]byte("fake-jpeg-bytes")); err != nil {
		t.Fatalf("write photo bytes: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/mobile/documents", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("X-Auth-Token", "mobile-token")
	rec := httptest.NewRecorder()

	api.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"status":"ok"`) {
		t.Fatalf("response does not contain status ok: %s", rec.Body.String())
	}
	if gotPhotoID != "b3e2648a-9f42-4c18-b994-b03f4a705977" {
		t.Fatalf("X-Photo-Id forwarded = %q", gotPhotoID)
	}
	if gotAssignmentID != "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f" {
		t.Fatalf("X-Assignment-Id forwarded = %q", gotAssignmentID)
	}
	if gotTripID != "e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b" {
		t.Fatalf("X-Trip-Id forwarded = %q", gotTripID)
	}
	if gotContentType != "image/jpeg" {
		t.Fatalf("Content-Type forwarded = %q", gotContentType)
	}
	if string(gotBody) != "fake-jpeg-bytes" {
		t.Fatalf("body forwarded to 1C = %q, want raw photo bytes", gotBody)
	}

	doc, err := api.store.GetDocument("b3e2648a-9f42-4c18-b994-b03f4a705977")
	if err != nil {
		t.Fatalf("get document: %v", err)
	}
	if doc.DeliveredAt == nil {
		t.Fatalf("DeliveredAt not set after successful 1C forward")
	}
}

func TestDocumentUploadRejectsUnsupportedType(t *testing.T) {
	api := newTestServer(t, config.Config{MobileToken: "mobile-token"})

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	_ = writer.WriteField("photo_id", "b3e2648a-9f42-4c18-b994-b03f4a705977")
	_ = writer.WriteField("driver_id", "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162")
	_ = writer.WriteField("trip_id", "e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b")
	part, err := writer.CreateFormFile("photo", "doc.pdf")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	_, _ = part.Write([]byte("%PDF-1.4"))
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/mobile/documents", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("X-Auth-Token", "mobile-token")
	rec := httptest.NewRecorder()

	api.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
}

func TestDocumentUploadWithoutOneCConfiguredStaysPending(t *testing.T) {
	api := newTestServer(t, config.Config{MobileToken: "mobile-token"})

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	_ = writer.WriteField("photo_id", "b3e2648a-9f42-4c18-b994-b03f4a705977")
	_ = writer.WriteField("driver_id", "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162")
	_ = writer.WriteField("trip_id", "e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b")
	part, err := writer.CreateFormFile("photo", "накладная.jpg")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	_, _ = part.Write([]byte("fake-jpeg-bytes"))
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/mobile/documents", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("X-Auth-Token", "mobile-token")
	rec := httptest.NewRecorder()

	api.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}

	// Локальная копия должна остаться, даже если в 1С не ушло — иначе
	// телефон повторит отправку, а нам нечего будет досылать при
	// появлении ONE_C_BASE_URL.
	doc, err := api.store.GetDocument("b3e2648a-9f42-4c18-b994-b03f4a705977")
	if err != nil {
		t.Fatalf("document should be saved locally even without 1C configured: %v", err)
	}
	if doc.DeliveredAt != nil {
		t.Fatalf("DeliveredAt should not be set without 1C forwarding")
	}
}

func newTestServer(t *testing.T, cfg config.Config) *Server {
	t.Helper()
	cfg.DataDir = t.TempDir()
	if cfg.AssignmentListWindowHours == 0 {
		cfg.AssignmentListWindowHours = 48
	}
	if cfg.OneCEventsURL == "" && cfg.OneCBaseURL != "" {
		cfg.OneCEventsURL = cfg.OneCBaseURL + "/prtr_driver/events"
	}
	if cfg.OneCPhotoURL == "" && cfg.OneCBaseURL != "" {
		cfg.OneCPhotoURL = cfg.OneCBaseURL + "/prtr_driver/photo"
	}
	store, err := storage.New(cfg.DataDir)
	if err != nil {
		t.Fatalf("storage init: %v", err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return NewServer(cfg, logger, http.DefaultClient, store, push.NewLogSender(logger, store))
}
