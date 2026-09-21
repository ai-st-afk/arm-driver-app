package storage

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestCleanupOldDocumentsRemovesExpiredOnly(t *testing.T) {
	store, err := New(t.TempDir())
	if err != nil {
		t.Fatalf("storage init: %v", err)
	}

	fresh, err := store.SaveDocument("photo-1", "trip-1", "driver-1", "assignment-1", ".jpg", []byte("fresh"))
	if err != nil {
		t.Fatalf("save fresh document: %v", err)
	}
	stalePending, err := store.SaveDocument("photo-2", "trip-2", "driver-1", "assignment-1", ".jpg", []byte("stale-pending"))
	if err != nil {
		t.Fatalf("save stale pending document: %v", err)
	}
	staleDelivered, err := store.SaveDocument("photo-3", "trip-3", "driver-1", "assignment-1", ".jpg", []byte("stale-delivered"))
	if err != nil {
		t.Fatalf("save stale delivered document: %v", err)
	}

	// stalePending: получено 100 дней назад, доставки нет — должно истечь
	// при pendingTTL=90d. staleDelivered: получено недавно, но доставлено
	// 10 дней назад — должно истечь при deliveredTTL=7d несмотря на то,
	// что pendingTTL тут не при чём.
	idx, err := store.loadDocuments()
	if err != nil {
		t.Fatalf("load documents: %v", err)
	}
	old := idx.Documents[stalePending.ID]
	old.ReceivedAt = time.Now().Add(-100 * 24 * time.Hour)
	idx.Documents[stalePending.ID] = old

	delivered := idx.Documents[staleDelivered.ID]
	deliveredAt := time.Now().Add(-10 * 24 * time.Hour)
	delivered.DeliveredAt = &deliveredAt
	idx.Documents[staleDelivered.ID] = delivered

	if err := store.saveDocuments(idx); err != nil {
		t.Fatalf("save documents: %v", err)
	}

	deleted, err := store.CleanupOldDocuments(time.Now(), 90*24*time.Hour, 7*24*time.Hour)
	if err != nil {
		t.Fatalf("cleanup: %v", err)
	}
	if deleted != 2 {
		t.Fatalf("deleted = %d, want 2", deleted)
	}

	remaining, err := store.loadDocuments()
	if err != nil {
		t.Fatalf("load documents after cleanup: %v", err)
	}
	if _, ok := remaining.Documents[fresh.ID]; !ok {
		t.Fatalf("fresh document was removed, should have stayed")
	}
	if _, ok := remaining.Documents[stalePending.ID]; ok {
		t.Fatalf("stale pending document was not removed")
	}
	if _, ok := remaining.Documents[staleDelivered.ID]; ok {
		t.Fatalf("stale delivered document was not removed")
	}
	if _, err := os.Stat(filepath.Join(store.dir, fresh.ContentPath)); err != nil {
		t.Fatalf("fresh document file missing: %v", err)
	}
	if _, err := os.Stat(filepath.Join(store.dir, stalePending.ContentPath)); !os.IsNotExist(err) {
		t.Fatalf("stale pending document file should be gone, err = %v", err)
	}
}

func TestSaveDocumentRetryKeepsDeliveredAt(t *testing.T) {
	store, err := New(t.TempDir())
	if err != nil {
		t.Fatalf("storage init: %v", err)
	}

	if _, err := store.SaveDocument("photo-1", "trip-1", "driver-1", "assignment-1", ".jpg", []byte("v1")); err != nil {
		t.Fatalf("save document: %v", err)
	}
	if err := store.MarkDocumentDelivered("photo-1", time.Now().UTC()); err != nil {
		t.Fatalf("mark delivered: %v", err)
	}

	// Телефон повторяет отправку того же фото (не знает, что gateway уже
	// успешно переслал его в 1С) — id тот же, содержимое перезаписывается,
	// но факт доставки не должен потеряться.
	retried, err := store.SaveDocument("photo-1", "trip-1", "driver-1", "assignment-1", ".jpg", []byte("v1-retry"))
	if err != nil {
		t.Fatalf("save document (retry): %v", err)
	}
	if retried.DeliveredAt == nil {
		t.Fatalf("DeliveredAt lost after retrying the same photo_id")
	}
}
