package storage

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type Store struct {
	dir string
	mu  sync.Mutex
}

type AssignmentMeta struct {
	ID          string    `json:"id"`
	Version     int       `json:"version"`
	DriverID    string    `json:"driver_id"`
	ReceivedAt  time.Time `json:"received_at"`
	ContentPath string    `json:"content_path"`
}

type Device struct {
	DriverID  string    `json:"driver_id"`
	DeviceID  string    `json:"device_id"`
	FCMToken  string    `json:"fcm_token"`
	UpdatedAt time.Time `json:"updated_at"`
}

type indexFile struct {
	Assignments    map[string]AssignmentMeta `json:"assignments"`
	LatestByDriver map[string]string         `json:"latest_by_driver"`
}

// DocumentMeta — фото подписанного документа с разгрузки. Это тоже
// technical delivery-cache, не источник истины: как только фото уедет
// в 1С (когда их команда даст формат приёма), DeliveredAt проставится
// и файл проживёт ещё недолго (см. CleanupOldDocuments) — до этого
// момента 1С своей копии не имеет, поэтому храним дольше.
type DocumentMeta struct {
	ID           string     `json:"id"`
	TripID       string     `json:"trip_id"`
	DriverID     string     `json:"driver_id"`
	AssignmentID string     `json:"assignment_id"`
	ContentPath  string     `json:"content_path"`
	ReceivedAt   time.Time  `json:"received_at"`
	DeliveredAt  *time.Time `json:"delivered_at,omitempty"`
}

type documentsIndexFile struct {
	Documents map[string]DocumentMeta `json:"documents"`
}

func New(dir string) (*Store, error) {
	for _, subdir := range []string{dir, filepath.Join(dir, "assignments"), filepath.Join(dir, "documents")} {
		if err := os.MkdirAll(subdir, 0o755); err != nil {
			return nil, err
		}
	}
	store := &Store{dir: dir}
	if err := store.ensureJSON("index.json", indexFile{
		Assignments:    map[string]AssignmentMeta{},
		LatestByDriver: map[string]string{},
	}); err != nil {
		return nil, err
	}
	if err := store.ensureJSON("devices.json", map[string]Device{}); err != nil {
		return nil, err
	}
	if err := store.ensureJSON("documents.json", documentsIndexFile{
		Documents: map[string]DocumentMeta{},
	}); err != nil {
		return nil, err
	}
	return store, nil
}

func (s *Store) SaveAssignment(id string, version int, driverID string, raw []byte) (AssignmentMeta, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadIndex()
	if err != nil {
		return AssignmentMeta{}, false, err
	}
	if existing, ok := idx.Assignments[id]; ok && version < existing.Version {
		return existing, false, nil
	} else if ok && version == existing.Version {
		return existing, true, nil
	}

	contentPath := filepath.Join("assignments", id+".xml")
	meta := AssignmentMeta{
		ID:          id,
		Version:     version,
		DriverID:    driverID,
		ReceivedAt:  time.Now().UTC(),
		ContentPath: contentPath,
	}
	if err := os.WriteFile(filepath.Join(s.dir, contentPath), raw, 0o644); err != nil {
		return AssignmentMeta{}, false, err
	}
	idx.Assignments[id] = meta
	idx.LatestByDriver[driverID] = id
	if err := s.saveIndex(idx); err != nil {
		return AssignmentMeta{}, false, err
	}
	return meta, true, nil
}

func (s *Store) GetAssignment(id string) ([]byte, AssignmentMeta, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadIndex()
	if err != nil {
		return nil, AssignmentMeta{}, err
	}
	meta, ok := idx.Assignments[id]
	if !ok {
		return nil, AssignmentMeta{}, ErrNotFound
	}
	raw, err := os.ReadFile(filepath.Join(s.dir, meta.ContentPath))
	if err != nil {
		return nil, AssignmentMeta{}, err
	}
	return raw, meta, nil
}

func (s *Store) GetLatestAssignmentForDriver(driverID string) ([]byte, AssignmentMeta, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadIndex()
	if err != nil {
		return nil, AssignmentMeta{}, err
	}
	id, ok := idx.LatestByDriver[driverID]
	if !ok {
		return nil, AssignmentMeta{}, ErrNotFound
	}
	meta, ok := idx.Assignments[id]
	if !ok {
		return nil, AssignmentMeta{}, fmt.Errorf("latest assignment index points to missing assignment %s", id)
	}
	raw, err := os.ReadFile(filepath.Join(s.dir, meta.ContentPath))
	if err != nil {
		return nil, AssignmentMeta{}, err
	}
	return raw, meta, nil
}

func (s *Store) SaveDevice(device Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if device.DriverID == "" {
		return errors.New("пустой водитель")
	}
	if device.FCMToken == "" {
		return errors.New("пустой FCM-токен")
	}
	devices, err := s.loadDevices()
	if err != nil {
		return err
	}
	device.UpdatedAt = time.Now().UTC()
	devices[device.DriverID] = device
	return s.saveDevices(devices)
}

func (s *Store) GetDevice(driverID string) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	devices, err := s.loadDevices()
	if err != nil {
		return Device{}, err
	}
	device, ok := devices[driverID]
	if !ok {
		return Device{}, ErrNotFound
	}
	return device, nil
}

// SaveDocument сохраняет фото документа на диск и возвращает его метаданные.
// id — GUID фото, сгенерированный на телефоне в момент съёмки (тот же
// X-Photo-Id, что уйдёт в 1С — так очередь на телефоне и идемпотентность
// на стороне 1С используют один и тот же идентификатор). Повторная
// загрузка с тем же id (телефон повторяет отправку после обрыва) тихо
// перезаписывает файл и метаданные, не плодит дублей.
// ext — расширение с точкой (например ".jpg"), пришедшее от телефона.
func (s *Store) SaveDocument(id, tripID, driverID, assignmentID, ext string, data []byte) (DocumentMeta, error) {
	if id == "" {
		return DocumentMeta{}, errors.New("пустой идентификатор фото")
	}
	if tripID == "" {
		return DocumentMeta{}, errors.New("пустой идентификатор ездки")
	}
	if driverID == "" {
		return DocumentMeta{}, errors.New("пустой водитель")
	}
	if len(data) == 0 {
		return DocumentMeta{}, errors.New("пустой файл")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadDocuments()
	if err != nil {
		return DocumentMeta{}, err
	}

	contentPath := filepath.Join("documents", id+ext)
	if err := os.WriteFile(filepath.Join(s.dir, contentPath), data, 0o644); err != nil {
		return DocumentMeta{}, err
	}

	// Повтор той же загрузки не должен терять уже проставленный DeliveredAt.
	deliveredAt := idx.Documents[id].DeliveredAt

	meta := DocumentMeta{
		ID:           id,
		TripID:       tripID,
		DriverID:     driverID,
		AssignmentID: assignmentID,
		ContentPath:  contentPath,
		ReceivedAt:   time.Now().UTC(),
		DeliveredAt:  deliveredAt,
	}
	idx.Documents[id] = meta
	if err := s.saveDocuments(idx); err != nil {
		return DocumentMeta{}, err
	}
	return meta, nil
}

// MarkDocumentDelivered проставляет время подтверждённой доставки в 1С —
// с этого момента для файла действует более короткий срок хранения
// (см. CleanupOldDocuments).
func (s *Store) MarkDocumentDelivered(id string, deliveredAt time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadDocuments()
	if err != nil {
		return err
	}
	meta, ok := idx.Documents[id]
	if !ok {
		return ErrNotFound
	}
	meta.DeliveredAt = &deliveredAt
	idx.Documents[id] = meta
	return s.saveDocuments(idx)
}

// GetDocument возвращает метаданные фото по его id (X-Photo-Id).
func (s *Store) GetDocument(id string) (DocumentMeta, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadDocuments()
	if err != nil {
		return DocumentMeta{}, err
	}
	meta, ok := idx.Documents[id]
	if !ok {
		return DocumentMeta{}, ErrNotFound
	}
	return meta, nil
}

// CleanupOldDocuments удаляет фото старше срока хранения: pendingTTL — для
// тех, что ещё не подтверждены доставленными в 1С (DeliveredAt пуст, на
// сегодня это все — доставка в 1С ещё не реализована), deliveredTTL — для
// уже подтверждённых. Возвращает количество удалённых файлов.
func (s *Store) CleanupOldDocuments(now time.Time, pendingTTL, deliveredTTL time.Duration) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadDocuments()
	if err != nil {
		return 0, err
	}

	deleted := 0
	for id, meta := range idx.Documents {
		var expired bool
		if meta.DeliveredAt != nil {
			expired = now.Sub(*meta.DeliveredAt) > deliveredTTL
		} else {
			expired = now.Sub(meta.ReceivedAt) > pendingTTL
		}
		if !expired {
			continue
		}
		if err := os.Remove(filepath.Join(s.dir, meta.ContentPath)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return deleted, err
		}
		delete(idx.Documents, id)
		deleted++
	}
	if deleted == 0 {
		return 0, nil
	}
	return deleted, s.saveDocuments(idx)
}

var ErrNotFound = errors.New("not found")

func (s *Store) ensureJSON(name string, value any) error {
	path := filepath.Join(s.dir, name)
	if _, err := os.Stat(path); err == nil {
		return nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return writeJSON(path, value)
}

func (s *Store) loadIndex() (indexFile, error) {
	var idx indexFile
	if err := readJSON(filepath.Join(s.dir, "index.json"), &idx); err != nil {
		return indexFile{}, err
	}
	if idx.Assignments == nil {
		idx.Assignments = map[string]AssignmentMeta{}
	}
	if idx.LatestByDriver == nil {
		idx.LatestByDriver = map[string]string{}
	}
	return idx, nil
}

func (s *Store) saveIndex(idx indexFile) error {
	return writeJSON(filepath.Join(s.dir, "index.json"), idx)
}

func (s *Store) loadDevices() (map[string]Device, error) {
	devices := map[string]Device{}
	if err := readJSON(filepath.Join(s.dir, "devices.json"), &devices); err != nil {
		return nil, err
	}
	return devices, nil
}

func (s *Store) saveDevices(devices map[string]Device) error {
	return writeJSON(filepath.Join(s.dir, "devices.json"), devices)
}

func (s *Store) loadDocuments() (documentsIndexFile, error) {
	var idx documentsIndexFile
	if err := readJSON(filepath.Join(s.dir, "documents.json"), &idx); err != nil {
		return documentsIndexFile{}, err
	}
	if idx.Documents == nil {
		idx.Documents = map[string]DocumentMeta{}
	}
	return idx, nil
}

func (s *Store) saveDocuments(idx documentsIndexFile) error {
	return writeJSON(filepath.Join(s.dir, "documents.json"), idx)
}

func readJSON(path string, dst any) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, dst)
}

func writeJSON(path string, value any) error {
	tmp := path + ".tmp"
	raw, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	raw = append(raw, '\n')
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
