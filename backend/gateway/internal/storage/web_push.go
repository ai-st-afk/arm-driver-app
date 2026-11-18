package storage

import (
	"errors"
	"os"
	"path/filepath"
	"time"
)

type WebPushSubscription struct {
	DriverID  string    `json:"driver_id"`
	Endpoint  string    `json:"endpoint"`
	Auth      string    `json:"auth"`
	P256dh    string    `json:"p256dh"`
	UpdatedAt time.Time `json:"updated_at"`
}

func (s *Store) loadWebPush() (map[string]WebPushSubscription, error) {
	items := map[string]WebPushSubscription{}
	err := readJSON(filepath.Join(s.dir, "web-push.json"), &items)
	if errors.Is(err, os.ErrNotExist) {
		return items, nil
	}
	return items, err
}

func (s *Store) saveWebPush(items map[string]WebPushSubscription) error {
	return writePrivateJSON(filepath.Join(s.dir, "web-push.json"), items)
}

func (s *Store) SaveWebPush(item WebPushSubscription) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	items, err := s.loadWebPush()
	if err != nil {
		return err
	}
	item.UpdatedAt = time.Now().UTC()
	items[webHash(item.Endpoint)] = item
	return s.saveWebPush(items)
}

func (s *Store) ListWebPush(driverID string) ([]WebPushSubscription, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	items, err := s.loadWebPush()
	if err != nil {
		return nil, err
	}
	result := []WebPushSubscription{}
	for _, item := range items {
		if item.DriverID == driverID {
			result = append(result, item)
		}
	}
	return result, nil
}

func (s *Store) RemoveWebPush(endpoint string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	items, err := s.loadWebPush()
	if err != nil {
		return err
	}
	delete(items, webHash(endpoint))
	return s.saveWebPush(items)
}

func (s *Store) RevokeWebPushDriver(driverID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	items, err := s.loadWebPush()
	if err != nil {
		return err
	}
	for hash, item := range items {
		if item.DriverID == driverID {
			delete(items, hash)
		}
	}
	return s.saveWebPush(items)
}

// writePrivateJSON хранит ключи браузера и сессии только для процесса шлюза.
func writePrivateJSON(path string, value any) error {
	return writeJSONMode(path, value, 0o600)
}
