package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"arm-driver-app/backend/gateway/internal/config"
)

func TestWebDisabledWhileMobileRoutesRemain(t *testing.T) {
	api := newTestServer(t, config.Config{MobileToken: "mobile-token"})
	for _, tc := range []struct {
		method string
		path   string
	}{
		{http.MethodGet, "/web"},
		{http.MethodGet, "/web/"},
		{http.MethodGet, "/web/app.js"},
		{http.MethodGet, "/web/sw.js"},
		{http.MethodGet, "/api/web/session"},
		{http.MethodPost, "/api/web/events"},
		{http.MethodPost, "/api/web/documents"},
		{http.MethodPost, "/api/web/pair-driver"},
	} {
		rec := httptest.NewRecorder()
		api.Routes().ServeHTTP(rec, httptest.NewRequest(tc.method, tc.path, nil))
		if rec.Code != http.StatusGone {
			t.Errorf("%s %s = %d, want 410", tc.method, tc.path, rec.Code)
		}
	}
	for _, tc := range []struct {
		path string
		want int
	}{
		{"/healthz", http.StatusOK},
		{"/api/mobile/assignments", http.StatusUnauthorized},
		{"/api/mobile/app/latest", http.StatusUnauthorized},
	} {
		rec := httptest.NewRecorder()
		api.Routes().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, tc.path, nil))
		if rec.Code != tc.want {
			t.Errorf("GET %s = %d, want %d", tc.path, rec.Code, tc.want)
		}
	}
}
