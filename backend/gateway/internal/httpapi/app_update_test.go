package httpapi

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"arm-driver-app/backend/gateway/internal/config"
)

func newReleaseServer(t *testing.T, manifest string, apk []byte) *Server {
	t.Helper()
	dir := t.TempDir()
	if manifest != "" {
		if err := os.WriteFile(filepath.Join(dir, "latest.json"), []byte(manifest), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if apk != nil {
		if err := os.WriteFile(filepath.Join(dir, "arm-driver-0.9.0.apk"), apk, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return newTestServer(t, config.Config{MobileToken: "mobile-token", ReleasesDir: dir})
}

func getApp(api *Server, path string, header http.Header) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, path, nil)
	req.Header.Set("X-Auth-Token", "mobile-token")
	for key, values := range header {
		req.Header[key] = values
	}
	rec := httptest.NewRecorder()
	api.Routes().ServeHTTP(rec, req)
	return rec
}

const testManifest = `{"version_code":37,"version_name":"0.9.0","file":"arm-driver-0.9.0.apk","sha256":"abc123","size":10}`

func TestLatestAppVersionReturnsManifest(t *testing.T) {
	api := newReleaseServer(t, testManifest, []byte("0123456789"))

	rec := getApp(api, "/api/mobile/app/latest", nil)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	body := rec.Body.String()
	for _, want := range []string{`"version_code":37`, `"version_name":"0.9.0"`, `"sha256":"abc123"`, `"size":10`} {
		if !strings.Contains(body, want) {
			t.Fatalf("%s missing in %s", want, body)
		}
	}
	// Имя файла на сервере телефону не нужно — он качает через /download.
	if strings.Contains(body, "arm-driver-0.9.0.apk") {
		t.Fatalf("file name leaked: %s", body)
	}
}

func TestLatestAppVersionReturnsMandatoryThreshold(t *testing.T) {
	manifest := `{"version_code":37,"version_name":"0.9.0","min_supported_version_code":36,"file":"arm-driver-0.9.0.apk","sha256":"abc123","size":10}`
	api := newReleaseServer(t, manifest, []byte("0123456789"))
	rec := getApp(api, "/api/mobile/app/latest", nil)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"min_supported_version_code":36`) {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
}

func TestLatestAppVersionRejectsInvalidMandatoryThreshold(t *testing.T) {
	manifest := `{"version_code":37,"version_name":"0.9.0","min_supported_version_code":38,"file":"arm-driver-0.9.0.apk","sha256":"abc123","size":10}`
	api := newReleaseServer(t, manifest, []byte("0123456789"))
	rec := getApp(api, "/api/mobile/app/latest", nil)
	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
}

func TestLatestAppVersionWithoutReleaseIs404(t *testing.T) {
	api := newReleaseServer(t, "", nil)

	rec := getApp(api, "/api/mobile/app/latest", nil)

	if rec.Code != http.StatusNotFound || !strings.Contains(rec.Body.String(), `"no_release"`) {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
}

func TestAppUpdateRequiresMobileToken(t *testing.T) {
	api := newReleaseServer(t, testManifest, []byte("0123456789"))

	for _, path := range []string{"/api/mobile/app/latest", "/api/mobile/app/download"} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		rec := httptest.NewRecorder()
		api.Routes().ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("%s without token: status = %d", path, rec.Code)
		}
	}
}

func TestDownloadAppServesFileAndResumesFromRange(t *testing.T) {
	api := newReleaseServer(t, testManifest, []byte("0123456789"))

	full := getApp(api, "/api/mobile/app/download", nil)
	if full.Code != http.StatusOK || full.Body.String() != "0123456789" {
		t.Fatalf("full download: status = %d, body = %q", full.Code, full.Body.String())
	}
	if got := full.Header().Get("Content-Type"); got != "application/vnd.android.package-archive" {
		t.Fatalf("content type = %q", got)
	}

	// Докачка после обрыва: телефон просит хвост с 6-го байта.
	part := getApp(api, "/api/mobile/app/download", http.Header{"Range": {"bytes=6-"}})
	if part.Code != http.StatusPartialContent || part.Body.String() != "6789" {
		t.Fatalf("range download: status = %d, body = %q", part.Code, part.Body.String())
	}
}

func TestDownloadAppRejectsPathInManifest(t *testing.T) {
	api := newReleaseServer(t,
		`{"version_code":37,"version_name":"0.9.0","file":"../config.env","sha256":"abc123","size":10}`,
		[]byte("0123456789"))

	rec := getApp(api, "/api/mobile/app/download", nil)

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
}

func TestPublicDownloadAlwaysServesCurrentReleaseWithoutToken(t *testing.T) {
	api := newReleaseServer(t, testManifest, []byte("0123456789"))

	get := func() *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		api.Routes().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/download", nil))
		return rec
	}
	first := get()
	if first.Code != http.StatusOK || first.Body.String() != "0123456789" {
		t.Fatalf("first download: status = %d, body = %q", first.Code, first.Body.String())
	}
	if first.Header().Get("Content-Disposition") != `attachment; filename=arm-driver-0.9.0.apk` {
		t.Fatalf("filename = %q", first.Header().Get("Content-Disposition"))
	}
	if first.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("cache-control = %q", first.Header().Get("Cache-Control"))
	}

	updated := `{"version_code":38,"version_name":"0.9.1","file":"arm-driver-0.9.1.apk","sha256":"def456","size":3}`
	if err := os.WriteFile(filepath.Join(api.cfg.ReleasesDir, "arm-driver-0.9.1.apk"), []byte("new"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(api.cfg.ReleasesDir, "latest.json"), []byte(updated), 0o644); err != nil {
		t.Fatal(err)
	}
	second := get()
	if second.Code != http.StatusOK || second.Body.String() != "new" {
		t.Fatalf("updated download: status = %d, body = %q", second.Code, second.Body.String())
	}
	if second.Header().Get("Content-Disposition") != `attachment; filename=arm-driver-0.9.1.apk` {
		t.Fatalf("updated filename = %q", second.Header().Get("Content-Disposition"))
	}

	protected := httptest.NewRecorder()
	api.Routes().ServeHTTP(protected, httptest.NewRequest(http.MethodGet, "/api/mobile/app/download", nil))
	if protected.Code != http.StatusUnauthorized {
		t.Fatalf("authenticated endpoint without token: status = %d", protected.Code)
	}
}
