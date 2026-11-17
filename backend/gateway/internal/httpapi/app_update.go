package httpapi

import (
	"encoding/json"
	"errors"
	"io/fs"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

// Самообновление приложения: телефоны водителей сами берут новую версию со
// шлюза, а не собираются по одному для ручной установки. Публикация —
// tools/release-apk.sh кладёт в RELEASES_DIR файл APK и latest.json.

type releaseManifest struct {
	VersionCode             int    `json:"version_code"`
	VersionName             string `json:"version_name"`
	MinSupportedVersionCode int    `json:"min_supported_version_code"`
	File                    string `json:"file"`
	SHA256                  string `json:"sha256"`
	Size                    int64  `json:"size"`
}

type appVersionResponse struct {
	VersionCode             int    `json:"version_code"`
	VersionName             string `json:"version_name"`
	MinSupportedVersionCode int    `json:"min_supported_version_code"`
	SHA256                  string `json:"sha256"`
	Size                    int64  `json:"size"`
}

var errNoRelease = errors.New("no release published")

// Манифест читается на каждый запрос: выкладка новой версии не требует
// перезапуска шлюза. Имя файла — только из самой папки, без путей.
func (s *Server) loadRelease() (releaseManifest, string, error) {
	raw, err := os.ReadFile(filepath.Join(s.cfg.ReleasesDir, "latest.json"))
	if errors.Is(err, fs.ErrNotExist) {
		return releaseManifest{}, "", errNoRelease
	}
	if err != nil {
		return releaseManifest{}, "", err
	}
	var manifest releaseManifest
	if err := json.Unmarshal(raw, &manifest); err != nil {
		return releaseManifest{}, "", err
	}
	name := filepath.Base(manifest.File)
	if manifest.VersionCode <= 0 || name != manifest.File || name == "." || manifest.SHA256 == "" ||
		manifest.MinSupportedVersionCode < 0 || manifest.MinSupportedVersionCode > manifest.VersionCode {
		return releaseManifest{}, "", errors.New("latest.json: неверная версия, порог обязательного обновления, sha256 или имя файла")
	}
	return manifest, filepath.Join(s.cfg.ReleasesDir, name), nil
}

func (s *Server) latestAppVersion(w http.ResponseWriter, r *http.Request) {
	if !s.authorizedMobile(r) {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "неверный X-Auth-Token")
		return
	}
	manifest, _, err := s.loadRelease()
	if errors.Is(err, errNoRelease) {
		writeJSONError(w, http.StatusNotFound, "no_release", "обновлений пока не публиковали")
		return
	}
	if err != nil {
		s.logger.ErrorContext(r.Context(), "release manifest read failed", "error", err)
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось прочитать сведения об обновлении")
		return
	}
	writeJSON(w, http.StatusOK, appVersionResponse{
		VersionCode:             manifest.VersionCode,
		VersionName:             manifest.VersionName,
		MinSupportedVersionCode: manifest.MinSupportedVersionCode,
		SHA256:                  manifest.SHA256,
		Size:                    manifest.Size,
	})
}

// ServeContent отдаёт Range: на мобильном интернете обрыв посреди
// скачивания не начинает его заново.
func (s *Server) downloadApp(w http.ResponseWriter, r *http.Request) {
	if !s.authorizedMobile(r) {
		writeJSONError(w, http.StatusUnauthorized, "unauthorized", "неверный X-Auth-Token")
		return
	}
	s.serveAppFile(w, r, false)
}

// Постоянная ссылка для первой установки: сканер QR и браузер не умеют
// передать мобильный токен в заголовке. Открыт только APK, не данные 1С.
func (s *Server) publicDownloadApp(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	// Обычный WriteTimeout шлюза — 30 секунд, что мало для APK на слабой сети.
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(10 * time.Minute))
	s.serveAppFile(w, r, true)
}

func (s *Server) serveAppFile(w http.ResponseWriter, r *http.Request, attachment bool) {
	manifest, path, err := s.loadRelease()
	if errors.Is(err, errNoRelease) {
		writeJSONError(w, http.StatusNotFound, "no_release", "обновлений пока не публиковали")
		return
	}
	if err != nil {
		s.logger.ErrorContext(r.Context(), "release manifest read failed", "error", err)
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "не удалось прочитать сведения об обновлении")
		return
	}
	file, err := os.Open(path)
	if err != nil {
		s.logger.ErrorContext(r.Context(), "release file open failed", "error", err, "file", manifest.File)
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "файл обновления недоступен")
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "файл обновления недоступен")
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("ETag", `"`+manifest.SHA256+`"`)
	if attachment {
		w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": manifest.File}))
	}
	http.ServeContent(w, r, manifest.File, info.ModTime(), file)
}
