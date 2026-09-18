package push

import (
	"context"
	"log/slog"

	"arm-driver-app/backend/gateway/internal/storage"
)

type AssignmentNotification struct {
	AssignmentID string
	Version      int
	DriverID     string
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
	)
	return nil
}
