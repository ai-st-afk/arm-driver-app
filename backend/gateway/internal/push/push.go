package push

import (
	"context"
	"log/slog"

	"arm-driver-app/backend/gateway/internal/storage"
)

// Kind — что именно случилось с разнарядкой. Текст уведомления строится по
// нему здесь, а не на телефоне: правка формулировки не должна требовать
// релиза приложения.
type Kind string

const (
	KindNew        Kind = "assignment_new"
	KindUpdated    Kind = "assignment_updated"
	KindReminder30 Kind = "assignment_reminder_30"
	KindReminder10 Kind = "assignment_reminder_10"
	KindCancelled  Kind = "assignment_cancelled"
)

type AssignmentNotification struct {
	Kind         Kind
	AssignmentID string
	Version      int
	DriverID     string
}

func (n AssignmentNotification) Title() string {
	switch n.Kind {
	case KindReminder30, KindReminder10:
		return "Разнарядка не принята"
	case KindCancelled:
		return "Разнарядка отменена"
	case KindUpdated:
		return "Разнарядка изменена"
	default:
		return "Новая разнарядка"
	}
}

func (n AssignmentNotification) Body() string {
	switch n.Kind {
	case KindReminder30:
		return "Вы ещё не приняли разнарядку на завтра. Осталось 30 минут."
	case KindReminder10:
		return "Осталось 10 минут, чтобы принять разнарядку. Иначе её передадут другому водителю."
	case KindCancelled:
		return "Разнарядку передали другому водителю."
	case KindUpdated:
		// Правки бывают на уровне одной ездки (в т.ч. её отмена) — конкретику
		// водитель увидит в приложении, тут не гадаем что именно изменилось.
		return "В разнарядке или маршруте есть изменения. Проверьте в приложении."
	default:
		return "Пришла разнарядка. Откройте приложение и примите её."
	}
}

type Sender interface {
	SendAssignment(ctx context.Context, notification AssignmentNotification) error
}

type DeviceLookup interface {
	GetDevice(driverID string) (storage.Device, error)
}

type LogSender struct {
	logger  *slog.Logger
	devices DeviceLookup
}

func NewLogSender(logger *slog.Logger, devices DeviceLookup) *LogSender {
	return &LogSender{logger: logger, devices: devices}
}

func (s *LogSender) SendAssignment(ctx context.Context, notification AssignmentNotification) error {
	device, err := s.devices.GetDevice(notification.DriverID)
	if err != nil {
		s.logger.WarnContext(
			ctx,
			"assignment push skipped: device token not registered",
			"assignment", notification.AssignmentID,
			"driver", notification.DriverID,
			"kind", string(notification.Kind),
		)
		return nil
	}
	s.logger.InfoContext(
		ctx,
		"assignment push queued",
		"assignment", notification.AssignmentID,
		"version", notification.Version,
		"driver", notification.DriverID,
		"device", device.DeviceID,
		"kind", string(notification.Kind),
		"title", notification.Title(),
	)
	return nil
}
