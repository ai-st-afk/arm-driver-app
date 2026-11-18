package storage

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"time"
)

type webPairing struct {
	DriverID  string    `json:"driver_id"`
	ExpiresAt time.Time `json:"expires_at"`
}

type webSession struct {
	DriverID  string    `json:"driver_id"`
	ExpiresAt time.Time `json:"expires_at"`
}

type webAuthFile struct {
	Pairings map[string]webPairing `json:"pairings"`
	Sessions map[string]webSession `json:"sessions"`
}

var ErrInvalidWebCode = errors.New("invalid or expired web pairing code")
var ErrInvalidWebSession = errors.New("invalid or expired web session")

func webHash(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func randomWebToken(bytes int) (string, error) {
	raw := make([]byte, bytes)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw), nil
}

func (s *Store) loadWebAuth() (webAuthFile, error) {
	var state webAuthFile
	err := readJSON(filepath.Join(s.dir, "web-auth.json"), &state)
	if errors.Is(err, os.ErrNotExist) {
		return webAuthFile{Pairings: map[string]webPairing{}, Sessions: map[string]webSession{}}, nil
	}
	if err != nil {
		return webAuthFile{}, err
	}
	if state.Pairings == nil {
		state.Pairings = map[string]webPairing{}
	}
	if state.Sessions == nil {
		state.Sessions = map[string]webSession{}
	}
	return state, nil
}

func (s *Store) saveWebAuth(state webAuthFile) error {
	return writePrivateJSON(filepath.Join(s.dir, "web-auth.json"), state)
}

func pruneWebAuth(state *webAuthFile, now time.Time) {
	for hash, item := range state.Pairings {
		if !item.ExpiresAt.After(now) {
			delete(state.Pairings, hash)
		}
	}
	for hash, item := range state.Sessions {
		if !item.ExpiresAt.After(now) {
			delete(state.Sessions, hash)
		}
	}
}

// CreateWebPairing выдаёт одноразовый код на 15 минут. Сохраняется только хэш.
func (s *Store) CreateWebPairing(driverID string, now time.Time) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state, err := s.loadWebAuth()
	if err != nil {
		return "", err
	}
	pruneWebAuth(&state, now)
	code, err := randomWebToken(12)
	if err != nil {
		return "", err
	}
	state.Pairings[webHash(code)] = webPairing{DriverID: driverID, ExpiresAt: now.Add(15 * time.Minute)}
	return code, s.saveWebAuth(state)
}

// ConsumeWebPairing атомарно удаляет код и создаёт сессию на 90 дней.
func (s *Store) ConsumeWebPairing(code string, now time.Time) (driverID, session string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state, err := s.loadWebAuth()
	if err != nil {
		return "", "", err
	}
	pruneWebAuth(&state, now)
	item, ok := state.Pairings[webHash(code)]
	if !ok {
		return "", "", ErrInvalidWebCode
	}
	delete(state.Pairings, webHash(code))
	session, err = randomWebToken(32)
	if err != nil {
		return "", "", err
	}
	state.Sessions[webHash(session)] = webSession{DriverID: item.DriverID, ExpiresAt: now.Add(90 * 24 * time.Hour)}
	if err := s.saveWebAuth(state); err != nil {
		return "", "", err
	}
	return item.DriverID, session, nil
}

func (s *Store) WebSessionDriver(session string, now time.Time) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state, err := s.loadWebAuth()
	if err != nil {
		return "", err
	}
	item, ok := state.Sessions[webHash(session)]
	if !ok || !item.ExpiresAt.After(now) {
		return "", ErrInvalidWebSession
	}
	return item.DriverID, nil
}

func (s *Store) RevokeWebSession(session string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	state, err := s.loadWebAuth()
	if err != nil {
		return err
	}
	delete(state.Sessions, webHash(session))
	return s.saveWebAuth(state)
}

func (s *Store) RevokeWebDriver(driverID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	state, err := s.loadWebAuth()
	if err != nil {
		return err
	}
	for hash, item := range state.Sessions {
		if item.DriverID == driverID {
			delete(state.Sessions, hash)
		}
	}
	for hash, item := range state.Pairings {
		if item.DriverID == driverID {
			delete(state.Pairings, hash)
		}
	}
	if err := s.saveWebAuth(state); err != nil {
		return err
	}
	// Push-подписки тоже отключаем: после отзыва доступа уведомления не нужны.
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
