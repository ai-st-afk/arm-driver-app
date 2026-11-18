package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"arm-driver-app/backend/gateway/internal/config"
)

const webTestDriver = "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162"
const webOtherDriver = "e8d9c721-fc51-4dbc-933c-73b8544d67b7"
const webTestAssignment = "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f"

func TestWebPairingAndDriverIsolation(t *testing.T) {
	api := newTestServer(t, config.Config{MobileToken: "mobile-secret", WebAdminToken: "admin-secret"})
	assignment := `<Разнарядка><Идентификатор>` + webTestAssignment + `</Идентификатор><Версия>1</Версия><Номер>36</Номер><ДатаВыезда>2026-11-17</ДатаВыезда><Статус>Назначена</Статус><Водитель><Идентификатор>` + webTestDriver + `</Идентификатор><ФИО>Тестовый водитель</ФИО></Водитель><Машина><Идентификатор>car</Идентификатор></Машина><Ездки></Ездки></Разнарядка>`
	if _, _, err := api.store.SaveAssignment(webTestAssignment, 1, webTestDriver, []byte(assignment)); err != nil {
		t.Fatal(err)
	}
	api.cfg.AssignmentListWindowHours = int((24 * time.Hour).Hours())
	makeRequest := func(method, path, body string, cookie *http.Cookie) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, strings.NewReader(body))
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-Web-Request", "1")
		}
		if cookie != nil {
			req.AddCookie(cookie)
		}
		w := httptest.NewRecorder()
		api.Routes().ServeHTTP(w, req)
		return w
	}
	request := httptest.NewRequest(http.MethodPost, "/api/web/admin/pairings", strings.NewReader(`{"driver_id":"`+webTestDriver+`"}`))
	request.Header.Set("Content-Type", "application/json")
	unauthorized := httptest.NewRecorder()
	api.Routes().ServeHTTP(unauthorized, request)
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("admin endpoint without token = %d", unauthorized.Code)
	}
	request = httptest.NewRequest(http.MethodPost, "/api/web/admin/pairings", strings.NewReader(`{"driver_id":"`+webTestDriver+`"}`))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-Web-Admin-Token", "admin-secret")
	issued := httptest.NewRecorder()
	api.Routes().ServeHTTP(issued, request)
	if issued.Code != http.StatusCreated {
		t.Fatalf("issue %d %s", issued.Code, issued.Body.String())
	}
	var pairing struct {
		Code string `json:"code"`
	}
	if err := json.Unmarshal(issued.Body.Bytes(), &pairing); err != nil {
		t.Fatal(err)
	}
	bound := makeRequest(http.MethodPost, "/api/web/pair", `{"code":"`+pairing.Code+`"}`, nil)
	if bound.Code != http.StatusOK {
		t.Fatalf("pair %d %s", bound.Code, bound.Body.String())
	}
	cookies := bound.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].HttpOnly || !cookies[0].Secure || cookies[0].SameSite != http.SameSiteStrictMode {
		t.Fatalf("cookie: %+v", cookies)
	}
	if replay := makeRequest(http.MethodPost, "/api/web/pair", `{"code":"`+pairing.Code+`"}`, nil); replay.Code != http.StatusUnauthorized {
		t.Fatalf("replay %d", replay.Code)
	}
	if got := makeRequest(http.MethodGet, "/api/web/assignments?driver_id="+webOtherDriver, "", cookies[0]); got.Code != http.StatusOK || !strings.Contains(got.Body.String(), webTestAssignment) {
		t.Fatalf("list %d %s", got.Code, got.Body.String())
	}
	if got := makeRequest(http.MethodGet, "/api/web/assignments/"+webTestAssignment, "", cookies[0]); got.Code != http.StatusOK {
		t.Fatalf("owned read %d %s", got.Code, got.Body.String())
	}
	if got := makeRequest(http.MethodGet, "/api/web/assignments/"+webOtherDriver, "", cookies[0]); got.Code != http.StatusNotFound {
		t.Fatalf("foreign read %d", got.Code)
	}
	event := `{"events":[{"id":"8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31","type":"Ознакомление","driver_id":"` + webOtherDriver + `","assignment_id":"` + webTestAssignment + `","time":"2026-11-17T09:00:00+03:00"}]}`
	if got := makeRequest(http.MethodPost, "/api/web/events", event, cookies[0]); got.Code != http.StatusForbidden {
		t.Fatalf("foreign event %d %s", got.Code, got.Body.String())
	}
}

func TestWebPushEndpointRestrictedToApple(t *testing.T) {
	for _, url := range []string{"http://web.push.apple.com/x", "https://evil.test/x", "https://push.apple.com.evil.test/x", "https://web.push.apple.com:444/x"} {
		if validWebPushEndpoint(url) {
			t.Fatalf("accepted %q", url)
		}
	}
	if !validWebPushEndpoint("https://web.push.apple.com/QwEr") {
		t.Fatal("rejected Apple endpoint")
	}
}

func TestWebEventsPreservePerEventOneCResult(t *testing.T) {
	const tripID = "e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b"
	const acceptedID = "8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31"
	const rejectedID = "b1f32d8c-17d1-43a2-a35d-70b53919fca5"
	var forwarded string
	oneC := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/prtr_driver/events" {
			t.Errorf("1C path = %s", r.URL.Path)
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Error(err)
		}
		forwarded = string(body)
		w.Header().Set("Content-Type", "application/xml; charset=utf-8")
		_, _ = w.Write([]byte(`<Результат статус="ok"><Событие ид="` + acceptedID + `" принято="true"/><Событие ид="` + rejectedID + `" принято="false" ошибка="Рейс не найден"/></Результат>`))
	}))
	defer oneC.Close()
	api := newTestServer(t, config.Config{
		MobileToken: "mobile-secret", OneCBaseURL: oneC.URL,
		OneCEventsURL: oneC.URL + "/prtr_driver/events",
	})
	assignment := `<Разнарядка><Идентификатор>` + webTestAssignment + `</Идентификатор><Версия>1</Версия><Водитель><Идентификатор>` + webTestDriver + `</Идентификатор></Водитель><Ездки><Ездка><Идентификатор>` + tripID + `</Идентификатор></Ездка></Ездки></Разнарядка>`
	if _, _, err := api.store.SaveAssignment(webTestAssignment, 1, webTestDriver, []byte(assignment)); err != nil {
		t.Fatal(err)
	}
	code, err := api.store.CreateWebPairing(webTestDriver, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	pairReq := httptest.NewRequest(http.MethodPost, "/api/web/pair", strings.NewReader(`{"code":"`+code+`"}`))
	pairReq.Header.Set("Content-Type", "application/json")
	pairReq.Header.Set("X-Web-Request", "1")
	pair := httptest.NewRecorder()
	api.Routes().ServeHTTP(pair, pairReq)
	if pair.Code != http.StatusOK {
		t.Fatalf("pair = %d: %s", pair.Code, pair.Body.String())
	}
	payload := `{"events":[` +
		`{"id":"` + acceptedID + `","type":"ПрибылНаПогрузку","driver_id":"` + webTestDriver + `","assignment_id":"` + webTestAssignment + `","trip_id":"` + tripID + `","time":"2026-11-17T07:30:00+03:00"},` +
		`{"id":"` + rejectedID + `","type":"ЗагрузилсяВПуть","driver_id":"` + webTestDriver + `","assignment_id":"` + webTestAssignment + `","trip_id":"` + tripID + `","time":"2026-11-17T07:40:00+03:00"}]}`
	req := httptest.NewRequest(http.MethodPost, "/api/web/events", strings.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Web-Request", "1")
	req.AddCookie(pair.Result().Cookies()[0])
	result := httptest.NewRecorder()
	api.Routes().ServeHTTP(result, req)
	if result.Code != http.StatusOK {
		t.Fatalf("events = %d: %s", result.Code, result.Body.String())
	}
	if !strings.Contains(forwarded, acceptedID) || !strings.Contains(forwarded, rejectedID) {
		t.Fatalf("forwarded XML missing event GUIDs: %s", forwarded)
	}
	var response eventSendResponse
	if err := json.Unmarshal(result.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if len(response.Events) != 2 || !response.Events[0].Accepted || response.Events[1].Accepted || response.Events[1].Error != "Рейс не найден" {
		t.Fatalf("per-event result: %+v", response.Events)
	}
}
