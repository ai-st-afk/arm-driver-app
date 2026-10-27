package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"arm-driver-app/backend/gateway/internal/config"
)

func TestKeysUpdateRequiresNewVersionAndIsScopedByAssignmentID(t *testing.T) {
	api := newTestServer(t, config.Config{GatewayToken: "one-c-token", MobileToken: "mobile-token"})
	post := func(id string, version int, keys bool) {
		t.Helper()
		body := fmt.Sprintf(`<Разнарядка>
          <Идентификатор>%s</Идентификатор><Версия>%d</Версия><Номер>00000000001</Номер>
          <Статус>Активна</Статус><Водитель><Идентификатор>driver</Идентификатор></Водитель>
          <Машина><Идентификатор>vehicle</Идентификатор></Машина>
          <КлючиВыданы>%t</КлючиВыданы><Ездки/>
        </Разнарядка>`, id, version, keys)
		req := httptest.NewRequest(http.MethodPost, "/api/1c/assignments", strings.NewReader(body))
		req.Header.Set("X-Auth-Token", "one-c-token")
		req.Header.Set("Content-Type", "application/xml; charset=utf-8")
		rec := httptest.NewRecorder()
		api.Routes().ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("post: %d %s", rec.Code, rec.Body.String())
		}
	}
	check := func(id string, version int, keys bool) {
		t.Helper()
		req := httptest.NewRequest(http.MethodGet, "/api/mobile/assignments/"+id+"?driver_id=driver", nil)
		req.Header.Set("X-Auth-Token", "mobile-token")
		rec := httptest.NewRecorder()
		api.Routes().ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("get: %d %s", rec.Code, rec.Body.String())
		}
		var a assignmentResponse
		if err := json.Unmarshal(rec.Body.Bytes(), &a); err != nil {
			t.Fatal(err)
		}
		if a.ID != id || a.Version != version || a.KeysIssued != keys {
			t.Fatalf("id=%s version=%d keys=%v, want %s/%d/%v", a.ID, a.Version, a.KeysIssued, id, version, keys)
		}
	}

	post("first-guid", 1, false)
	post("other-guid", 1, true)
	check("first-guid", 1, false)
	check("other-guid", 1, true)

	// Исправленный XML под тем же GUID/версией не заменяет сохранённый,
	// даже если HTTP 200: это идемпотентный повтор, а не новая редакция.
	post("first-guid", 1, true)
	check("first-guid", 1, false)
	post("first-guid", 2, true)
	check("first-guid", 2, true)

	post("first-guid", 1, false)
	check("first-guid", 2, true)
	check("other-guid", 1, true)
}
