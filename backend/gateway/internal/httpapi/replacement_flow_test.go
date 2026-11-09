package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"arm-driver-app/backend/gateway/internal/config"
)

func TestReplacementPacketsAndTripFactThroughGateway(t *testing.T) {
	const oldID = "11111111-1111-4111-8111-111111111111"
	const newID = "22222222-2222-4222-8222-222222222222"
	const driverID = "33333333-3333-4333-8333-333333333333"
	const newTripID = "77777777-7777-4777-8777-777777777777"
	const eventID = "88888888-8888-4888-8888-888888888888"

	var forwarded string
	oneC := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("read event: %v", err)
			return
		}
		forwarded = string(body)
		w.Header().Set("Content-Type", "application/xml; charset=utf-8")
		_, _ = w.Write([]byte(`<Результат статус="ok"><Событие ид="` + eventID + `" принято="true"/></Результат>`))
	}))
	defer oneC.Close()
	api := newTestServer(t, config.Config{
		GatewayToken: "one-c-token", MobileToken: "mobile-token", OneCBaseURL: oneC.URL,
	})

	postAssignment := func(path string, edit func(string) string) {
		t.Helper()
		body, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		raw := string(body)
		if edit != nil {
			raw = edit(raw)
		}
		req := httptest.NewRequest(http.MethodPost, "/api/1c/assignments", strings.NewReader(raw))
		req.Header.Set("X-Auth-Token", "one-c-token")
		req.Header.Set("Content-Type", "application/xml; charset=utf-8")
		rec := httptest.NewRecorder()
		api.Routes().ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("post %s: %d %s", path, rec.Code, rec.Body.String())
		}
	}
	oldXML := "../../../../docs/samples/replacement-old.xml"
	newXML := "../../../../docs/samples/replacement-new.xml"
	postAssignment(oldXML, nil)
	postAssignment(newXML, nil)

	get := httptest.NewRequest(http.MethodGet, "/api/mobile/assignments?driver_id="+driverID, nil)
	get.Header.Set("X-Auth-Token", "mobile-token")
	rec := httptest.NewRecorder()
	api.Routes().ServeHTTP(rec, get)
	if rec.Code != http.StatusOK {
		t.Fatalf("list: %d %s", rec.Code, rec.Body.String())
	}
	var list assignmentsListResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	if len(list.Assignments) != 2 {
		t.Fatalf("want old and new assignments, got %+v", list)
	}
	byID := make(map[string]assignmentResponse)
	for _, a := range list.Assignments {
		byID[a.ID] = a
	}
	for _, tc := range []struct{ id, fixture string }{
		{oldID, "../../../../android/app/src/test/resources/replacement-old.json"},
		{newID, "../../../../android/app/src/test/resources/replacement-new.json"},
	} {
		body, err := os.ReadFile(tc.fixture)
		if err != nil {
			t.Fatal(err)
		}
		var expected assignmentResponse
		if err := json.Unmarshal(body, &expected); err != nil {
			t.Fatal(err)
		}
		actual := byID[tc.id]
		if actual.Version != expected.Version || actual.Status != expected.Status ||
			actual.ShiftStarted != expected.ShiftStarted || actual.KeysIssued != expected.KeysIssued ||
			actual.Driver.ID != expected.Driver.ID || len(actual.Trips) != 1 ||
			actual.Trips[0].ID != expected.Trips[0].ID {
			t.Fatalf("mobile assignment differs from Android fixture: actual=%+v expected=%+v", actual, expected)
		}
		if tc.id == oldID && (actual.Replacement == nil || expected.Replacement == nil || *actual.Replacement != *expected.Replacement) {
			t.Fatalf("old replacement link lost: %+v", actual.Replacement)
		}
		if tc.id == newID && (actual.Basis == nil || expected.Basis == nil || *actual.Basis != *expected.Basis) {
			t.Fatalf("new basis link lost: %+v", actual.Basis)
		}
	}

	postAssignment(newXML, func(raw string) string {
		raw = strings.Replace(raw, "<Версия>2</Версия>", "<Версия>3</Версия>", 1)
		return strings.Replace(raw, "<КлючиВыданы>false</КлючиВыданы>", "<КлючиВыданы>true</КлючиВыданы>", 1)
	})
	events := `{"events":[{"id":"` + eventID + `","type":"ПрибылНаПогрузку","driver_id":"` + driverID + `","assignment_id":"` + newID + `","trip_id":"` + newTripID + `","time":"2026-11-09T08:15:00+03:00"}]}`
	req := httptest.NewRequest(http.MethodPost, "/api/mobile/events", strings.NewReader(events))
	req.Header.Set("X-Auth-Token", "mobile-token")
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	rec = httptest.NewRecorder()
	api.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"accepted":true`) {
		t.Fatalf("trip event response: %d %s", rec.Code, rec.Body.String())
	}
	for _, part := range []string{"<Разнарядка>" + newID + "</Разнарядка>", "<Ездка>" + newTripID + "</Ездка>", "<Тип>ПрибылНаПогрузку</Тип>"} {
		if !strings.Contains(forwarded, part) {
			t.Fatalf("forwarded event missing %q: %s", part, forwarded)
		}
	}
	if strings.Contains(forwarded, "НачалоСмены") {
		t.Fatalf("duplicate start forwarded: %s", forwarded)
	}

	postAssignment("../../../../docs/samples/replacement-loaded.xml", nil)
	get = httptest.NewRequest(http.MethodGet, "/api/mobile/assignments?driver_id="+driverID, nil)
	get.Header.Set("X-Auth-Token", "mobile-token")
	rec = httptest.NewRecorder()
	api.Routes().ServeHTTP(rec, get)
	if rec.Code != http.StatusOK {
		t.Fatalf("loaded list: %d %s", rec.Code, rec.Body.String())
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	for _, a := range list.Assignments {
		if a.ID != newID {
			continue
		}
		trip := a.Trips[0]
		if a.Version != 4 || trip.CargoInVehicle == nil || !*trip.CargoInVehicle ||
			trip.Stages == nil || trip.Stages.Loaded == "" ||
			trip.NextStage == nil || *trip.NextStage != "ПрибылНаРазгрузку" {
			t.Fatalf("loaded replacement fields lost after delivery: %+v", a)
		}
		return
	}
	t.Fatal("loaded replacement missing from mobile list")
}
