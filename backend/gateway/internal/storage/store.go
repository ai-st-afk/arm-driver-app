package storage

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"slices"
	"sort"
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
	// Водители, у которых эта разнарядка была раньше (1С: плановая замена
	// экипажа — та же разнарядка новой версией с другим <Водитель>). Им она
	// отдаётся как отменённая, иначе молча пропала бы из их выдачи.
	PreviousDriverIDs []string `json:"previous_driver_ids,omitempty"`
}

type Device struct {
	DriverID  string    `json:"driver_id"`
	DeviceID  string    `json:"device_id"`
	FCMToken  string    `json:"fcm_token"`
	UpdatedAt time.Time `json:"updated_at"`
}

// Reminder — отложенный пуш «разнарядка не принята». 1С присылает разнарядку
// один раз и больше не звонит, а напомнить надо через 30 и 50 минут, поэтому
// расписание держит шлюз. Это не бизнес-истина, а доставка уведомлений —
// та же зона ответственности, что и сам push.
type Reminder struct {
	AssignmentID string    `json:"assignment_id"`
	DriverID     string    `json:"driver_id"`
	Version      int       `json:"version"`
	Kind         string    `json:"kind"`
	DueAt        time.Time `json:"due_at"`
}

type indexFile struct {
	Assignments map[string]AssignmentMeta `json:"assignments"`
	Reminders   map[string]Reminder       `json:"reminders"`
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
		Assignments: map[string]AssignmentMeta{},
		Reminders:   map[string]Reminder{},
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
	// Версия ниже сохранённой — переупорядоченная доставка, игнорируем
	// (инвариант проекта). Версия равна сохранённой и водитель тот же —
	// настоящий безобидный дубль (1С не обязана бить не-content правки),
	// не перезаписываем зря. А вот версия равна, но водитель ДРУГОЙ —
	// не дубль: на практике 1С один раз прислала документ с некорректным
	// <Идентификатор> водителя (не бампнув версию, раз для них это не
	// содержательная правка) и следом переслала с исправленным. Раньше
	// это тоже считалось дублем и тихо игнорировалось — водитель с
	// исправленным GUID пуш получал (SendAssignment строился из свежего
	// тела запроса), а в выдаче по GET разнарядки не было вообще: она
	// оставалась висеть на старом, битом driver_id.
	if existing, ok := idx.Assignments[id]; ok {
		if version < existing.Version {
			return existing, false, nil
		}
		if version == existing.Version && driverID == existing.DriverID {
			return existing, true, nil
		}
	}

	contentPath := filepath.Join("assignments", id+".xml")
	meta := AssignmentMeta{
		ID:          id,
		Version:     version,
		DriverID:    driverID,
		ReceivedAt:  time.Now().UTC(),
		ContentPath: contentPath,
	}
	if existing, ok := idx.Assignments[id]; ok {
		meta.PreviousDriverIDs = previousDrivers(existing, driverID)
	}
	if err := os.WriteFile(filepath.Join(s.dir, contentPath), raw, 0o644); err != nil {
		return AssignmentMeta{}, false, err
	}
	idx.Assignments[id] = meta
	if err := s.saveIndex(idx); err != nil {
		return AssignmentMeta{}, false, err
	}
	return meta, true, nil
}

// ListAssignmentsForDriver возвращает разнарядки водителя, полученные не
// раньше since, свежие первыми. idx.Assignments и так хранит всё бессрочно
// (по id) — здесь просто фильтрация без изменения хранения; окно "since"
// задаёт вызывающий код (см. config.AssignmentListWindowHours).
func (s *Store) ListAssignmentsForDriver(driverID string, since time.Time) ([]AssignmentMeta, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadIndex()
	if err != nil {
		return nil, err
	}

	var metas []AssignmentMeta
	for _, meta := range idx.Assignments {
		belongs := meta.DriverID == driverID || slices.Contains(meta.PreviousDriverIDs, driverID)
		if belongs && !meta.ReceivedAt.Before(since) {
			metas = append(metas, meta)
		}
	}
	sort.Slice(metas, func(i, j int) bool { return metas[i].ReceivedAt.After(metas[j].ReceivedAt) })
	return metas, nil
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

// Если разнарядку вернули водителю, у которого она уже была, он снова
// текущий, а не прежний.
func previousDrivers(existing AssignmentMeta, newDriverID string) []string {
	ids := slices.Clone(existing.PreviousDriverIDs)
	if existing.DriverID != newDriverID && !slices.Contains(ids, existing.DriverID) {
		ids = append(ids, existing.DriverID)
	}
	return slices.DeleteFunc(ids, func(id string) bool { return id == newDriverID })
}

func reminderKey(assignmentID, kind string) string { return assignmentID + ":" + kind }

// ScheduleReminders переписывает расписание напоминаний по разнарядке.
// Повторный приём той же разнарядки (новая версия) отсчёт начинает заново —
// новую версию водитель должен посмотреть и принять так же, как первую.
func (s *Store) ScheduleReminders(reminders []Reminder) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadIndex()
	if err != nil {
		return err
	}
	for _, reminder := range reminders {
		idx.Reminders[reminderKey(reminder.AssignmentID, reminder.Kind)] = reminder
	}
	return s.saveIndex(idx)
}

// DueReminders отдаёт напоминания, которым пора сработать, и сразу убирает
// их из расписания: пуш отправляется ровно один раз, повтор при перезапуске
// контейнера был бы хуже пропуска.
func (s *Store) DueReminders(now time.Time) ([]Reminder, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadIndex()
	if err != nil {
		return nil, err
	}

	var due []Reminder
	for key, reminder := range idx.Reminders {
		if !reminder.DueAt.After(now) {
			due = append(due, reminder)
			delete(idx.Reminders, key)
		}
	}
	if len(due) == 0 {
		return nil, nil
	}
	sort.Slice(due, func(i, j int) bool { return due[i].DueAt.Before(due[j].DueAt) })
	return due, s.saveIndex(idx)
}

// CancelReminders вызывается, когда напоминать уже незачем: водитель принял
// разнарядку или её отменили.
func (s *Store) CancelReminders(assignmentID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadIndex()
	if err != nil {
		return err
	}

	removed := false
	for key, reminder := range idx.Reminders {
		if reminder.AssignmentID == assignmentID {
			delete(idx.Reminders, key)
			removed = true
		}
	}
	if !removed {
		return nil
	}
	return s.saveIndex(idx)
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

// CleanupOldAssignments удаляет разнарядки, полученные раньше ttl. Это
// технический delivery-cache, а не архив: история живёт в 1С, а у нас файлы
// копились бы вечно. Заодно чистятся их напоминания, если такие остались.
func (s *Store) CleanupOldAssignments(now time.Time, ttl time.Duration) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, err := s.loadIndex()
	if err != nil {
		return 0, err
	}

	deleted := 0
	for id, meta := range idx.Assignments {
		if now.Sub(meta.ReceivedAt) <= ttl {
			continue
		}
		if err := os.Remove(filepath.Join(s.dir, meta.ContentPath)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return deleted, err
		}
		delete(idx.Assignments, id)
		for key, reminder := range idx.Reminders {
			if reminder.AssignmentID == id {
				delete(idx.Reminders, key)
			}
		}
		deleted++
	}
	if deleted == 0 {
		return 0, nil
	}
	return deleted, s.saveIndex(idx)
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
	if idx.Reminders == nil {
		idx.Reminders = map[string]Reminder{}
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
