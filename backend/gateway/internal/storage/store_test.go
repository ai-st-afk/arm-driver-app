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

// Regression: 1С один раз прислала разнарядку с некорректным driver_id, не
// бампнув версию, следом переслала ту же версию с исправленным. Раньше
// второй SaveAssignment при version == existing.Version тихо игнорировался
// целиком (в том числе новый driver_id) — разнарядка навсегда оставалась
// привязана к битому GUID и не находилась по GET для настоящего водителя,
// хотя push уходил на него же.
func TestSaveAssignmentSameVersionDifferentDriverUpdatesRecord(t *testing.T) {
	store, err := New(t.TempDir())
	if err != nil {
		t.Fatalf("storage init: %v", err)
	}

	if _, saved, err := store.SaveAssignment("assignment-1", 1, "broken-driver-id", []byte("<Разнарядка>v1-broken</Разнарядка>")); err != nil || !saved {
		t.Fatalf("save first attempt: saved=%v err=%v", saved, err)
	}

	meta, saved, err := store.SaveAssignment("assignment-1", 1, "driver-1", []byte("<Разнарядка>v1-fixed</Разнарядка>"))
	if err != nil {
		t.Fatalf("save corrected attempt: %v", err)
	}
	if !saved {
		t.Fatalf("corrected attempt reported as not saved (treated as stale duplicate)")
	}
	if meta.DriverID != "driver-1" {
		t.Fatalf("stored driver_id = %q, want driver-1 (correction must overwrite the broken one)", meta.DriverID)
	}

	raw, _, err := store.GetAssignment("assignment-1")
	if err != nil {
		t.Fatalf("get assignment: %v", err)
	}
	if string(raw) != "<Разнарядка>v1-fixed</Разнарядка>" {
		t.Fatalf("stored content = %q, want the corrected body", raw)
	}

	found, err := store.ListAssignmentsForDriver("driver-1", time.Now().Add(-time.Hour))
	if err != nil {
		t.Fatalf("list for driver-1: %v", err)
	}
	if len(found) != 1 {
		t.Fatalf("driver-1 sees %d assignments, want 1", len(found))
	}

	// Настоящий дубль (тот же driver_id, та же версия) по-прежнему не
	// перезаписывает содержимое — иначе ScheduleReminders/SendAssignment
	// вызывались бы заново на каждый безобидный повтор.
	if _, saved, err := store.SaveAssignment("assignment-1", 1, "driver-1", []byte("<Разнарядка>should-be-ignored</Разнарядка>")); err != nil || !saved {
		t.Fatalf("save true duplicate: saved=%v err=%v", saved, err)
	}
	raw, _, err = store.GetAssignment("assignment-1")
	if err != nil {
		t.Fatalf("get assignment after duplicate: %v", err)
	}
	if string(raw) != "<Разнарядка>v1-fixed</Разнарядка>" {
		t.Fatalf("true duplicate overwrote content: %q", raw)
	}
}

func TestListAssignmentsForDriverFiltersByWindowAndDriver(t *testing.T) {
	store, err := New(t.TempDir())
	if err != nil {
		t.Fatalf("storage init: %v", err)
	}

	if _, _, err := store.SaveAssignment("assignment-1", 1, "driver-1", []byte("<Разнарядка/>")); err != nil {
		t.Fatalf("save assignment-1: %v", err)
	}
	if _, _, err := store.SaveAssignment("assignment-2", 1, "driver-1", []byte("<Разнарядка/>")); err != nil {
		t.Fatalf("save assignment-2: %v", err)
	}
	if _, _, err := store.SaveAssignment("assignment-3", 1, "driver-2", []byte("<Разнарядка/>")); err != nil {
		t.Fatalf("save assignment-3 (other driver): %v", err)
	}

	// assignment-1 состарить искусственно — как будто получена 3 дня назад,
	// за пределами окна в 48 часов.
	idx, err := store.loadIndex()
	if err != nil {
		t.Fatalf("load index: %v", err)
	}
	old := idx.Assignments["assignment-1"]
	old.ReceivedAt = time.Now().Add(-72 * time.Hour)
	idx.Assignments["assignment-1"] = old
	if err := store.saveIndex(idx); err != nil {
		t.Fatalf("save index: %v", err)
	}

	since := time.Now().Add(-48 * time.Hour)
	metas, err := store.ListAssignmentsForDriver("driver-1", since)
	if err != nil {
		t.Fatalf("list assignments: %v", err)
	}
	if len(metas) != 1 || metas[0].ID != "assignment-2" {
		t.Fatalf("metas = %+v, want only assignment-2 (assignment-1 outside window, assignment-3 other driver)", metas)
	}
}

func TestDueRemindersFireOnceAndCancel(t *testing.T) {
	store, err := New(t.TempDir())
	if err != nil {
		t.Fatalf("storage init: %v", err)
	}

	now := time.Now().UTC()
	if err := store.ScheduleReminders([]Reminder{
		{AssignmentID: "a-1", DriverID: "driver-1", Kind: "assignment_reminder_30", DueAt: now.Add(-time.Minute)},
		{AssignmentID: "a-1", DriverID: "driver-1", Kind: "assignment_reminder_10", DueAt: now.Add(20 * time.Minute)},
	}); err != nil {
		t.Fatalf("schedule reminders: %v", err)
	}

	due, err := store.DueReminders(now)
	if err != nil {
		t.Fatalf("due reminders: %v", err)
	}
	if len(due) != 1 || due[0].Kind != "assignment_reminder_30" {
		t.Fatalf("due = %+v, want only the 30-minute reminder", due)
	}

	// Повторный опрос того же момента ничего не отдаёт: пуш уходит один раз,
	// иначе рестарт контейнера завалил бы водителя дублями.
	again, err := store.DueReminders(now)
	if err != nil {
		t.Fatalf("due reminders (repeat): %v", err)
	}
	if len(again) != 0 {
		t.Fatalf("again = %+v, want nothing", again)
	}

	// Водитель принял разнарядку — оставшееся напоминание снимается.
	if err := store.CancelReminders("a-1"); err != nil {
		t.Fatalf("cancel reminders: %v", err)
	}
	later, err := store.DueReminders(now.Add(time.Hour))
	if err != nil {
		t.Fatalf("due reminders (later): %v", err)
	}
	if len(later) != 0 {
		t.Fatalf("later = %+v, want nothing after cancel", later)
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
