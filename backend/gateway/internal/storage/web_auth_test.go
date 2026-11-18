package storage

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestWebPairingOneTimeAndRevocable(t *testing.T) {
	store, err := New(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 11, 17, 12, 0, 0, 0, time.UTC)
	code, err := store.CreateWebPairing("driver-1", now)
	if err != nil {
		t.Fatal(err)
	}
	if len(code) != 24 {
		t.Fatalf("code length %d", len(code))
	}
	raw, err := os.ReadFile(filepath.Join(store.dir, "web-auth.json"))
	if err != nil {
		t.Fatal(err)
	}
	if string(raw) == "" || containsToken(string(raw), code) {
		t.Fatal("raw pairing code saved on disk")
	}
	driver, session, err := store.ConsumeWebPairing(code, now.Add(time.Minute))
	if err != nil || driver != "driver-1" {
		t.Fatalf("consume: driver=%q err=%v", driver, err)
	}
	if _, _, err := store.ConsumeWebPairing(code, now.Add(time.Minute)); !errors.Is(err, ErrInvalidWebCode) {
		t.Fatalf("second consume: %v", err)
	}
	if got, err := store.WebSessionDriver(session, now.Add(time.Hour)); err != nil || got != driver {
		t.Fatalf("session: %q %v", got, err)
	}
	if err := store.RevokeWebDriver(driver); err != nil {
		t.Fatal(err)
	}
	if _, err := store.WebSessionDriver(session, now.Add(time.Hour)); !errors.Is(err, ErrInvalidWebSession) {
		t.Fatalf("revoke: %v", err)
	}
}

func TestWebPairingExpires(t *testing.T) {
	store, err := New(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	code, err := store.CreateWebPairing("driver-1", now)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.ConsumeWebPairing(code, now.Add(16*time.Minute)); !errors.Is(err, ErrInvalidWebCode) {
		t.Fatalf("expired code: %v", err)
	}
}

func containsToken(s, token string) bool {
	for i := 0; i+len(token) <= len(s); i++ {
		if s[i:i+len(token)] == token {
			return true
		}
	}
	return false
}
