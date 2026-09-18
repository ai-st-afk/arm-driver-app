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

func New(dir string) (*Store, error) {
	for _, subdir := range []string{dir, filepath.Join(dir, "assignments")} {
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
