package httpapi

import (
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
)

var (
	guidPattern      = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	photoKindPattern = regexp.MustCompile(`^[a-z_]{1,32}$`)
)

var assignmentLevelEvents = map[string]bool{
	"Ознакомление":   true,
	"НачалоСмены":    true,
	"ОкончаниеСмены": true,
}

var tripLevelEvents = map[string]bool{
	"ПрибылНаПогрузку":  true,
	"ЗагрузилсяВПуть":   true,
	"ПрибылНаРазгрузку": true,
	"Разгрузился":       true,
	"Срыв":              true,
}

func validateAssignment(a assignmentXML) error {
	if a.ID == "" {
		return errors.New("пустой идентификатор разнарядки")
	}
	if a.Version <= 0 {
		return errors.New("версия разнарядки должна быть положительной")
	}
	if a.Driver.ID == "" {
		return errors.New("пустой идентификатор водителя")
	}
	if a.Vehicle.ID == "" {
		return errors.New("пустой идентификатор машины")
	}
	if a.DepartureDay != "" {
		if _, err := time.Parse(time.DateOnly, a.DepartureDay); err != nil {
			return fmt.Errorf("некорректная ДатаВыезда: %w", err)
		}
	}
	for _, flag := range []struct{ name, value, timestamp string }{
		{"КлючиПриняты", a.KeysAccepted, a.KeysAcceptedAt},
		{"СменаЗакрыта", a.ShiftClosed, a.ShiftClosedAt},
	} {
		if flag.value != "" && !strings.EqualFold(flag.value, "true") && !strings.EqualFold(flag.value, "false") {
			return fmt.Errorf("некорректный признак %s: %q", flag.name, flag.value)
		}
		if strings.EqualFold(flag.value, "true") && flag.timestamp == "" {
			return fmt.Errorf("для %s=true не передано время", flag.name)
		}
	}
	for _, value := range []struct {
		name string
		raw  string
	}{
		{name: "ПланВыезда", raw: a.PlanDepart},
		{name: "ПланВозврата", raw: a.PlanReturn},
		{name: "ВремяПриемаКлючей", raw: a.KeysAcceptedAt},
		{name: "ВремяЗакрытияСмены", raw: a.ShiftClosedAt},
	} {
		if value.raw != "" {
			if _, err := time.Parse(time.RFC3339, value.raw); err != nil {
				return fmt.Errorf("некорректное время %s: %w", value.name, err)
			}
		}
	}
	for _, trip := range a.Trips {
		if trip.ID == "" {
			return errors.New("пустой идентификатор ездки")
		}
		if trip.Order <= 0 {
			return fmt.Errorf("ездка %s: порядок должен быть положительным", trip.ID)
		}
		if len([]rune(trip.CancelReason)) > 200 {
			return fmt.Errorf("ездка %s: ПричинаОтмены длиннее 200 символов", trip.ID)
		}
		for _, value := range []struct {
			name string
			raw  string
		}{
			{name: "ПланПогрузки", raw: trip.PlanLoad},
			{name: "ПланРазгрузки", raw: trip.PlanUnload},
		} {
			if value.raw != "" {
				if _, err := time.Parse(time.RFC3339, value.raw); err != nil {
					return fmt.Errorf("ездка %s: некорректное время %s: %w", trip.ID, value.name, err)
				}
			}
		}
	}
	return nil
}

func validateEvents(batch eventsXML) error {
	if len(batch.Events) == 0 {
		return errors.New("пустой список событий")
	}
	for _, event := range batch.Events {
		if event.ID == "" {
			return errors.New("пустой идентификатор события")
		}
		if event.DriverID == "" {
			return fmt.Errorf("событие %s: пустой водитель", event.ID)
		}
		if event.Assignment == "" {
			return fmt.Errorf("событие %s: пустая разнарядка", event.ID)
		}
		if _, err := time.Parse(time.RFC3339, event.Time); err != nil {
			return fmt.Errorf("событие %s: некорректное время: %w", event.ID, err)
		}
		switch {
		case assignmentLevelEvents[event.Type]:
			if event.TripID != "" {
				return fmt.Errorf("событие %s: для %s не заполняется Ездка", event.ID, event.Type)
			}
		case tripLevelEvents[event.Type]:
			if event.TripID == "" {
				return fmt.Errorf("событие %s: для %s обязательна Ездка", event.ID, event.Type)
			}
			if event.Type == "Срыв" && strings.TrimSpace(event.Reason) == "" && strings.TrimSpace(event.Comment) == "" {
				return fmt.Errorf("событие %s: для Срыв обязательна Причина или Комментарий", event.ID)
			}
			if event.Type == "Срыв" && len([]rune(event.Reason)) > 200 {
				return fmt.Errorf("событие %s: Причина длиннее 200 символов", event.ID)
			}
		default:
			return fmt.Errorf("событие %s: неизвестный тип %s", event.ID, event.Type)
		}
	}
	return nil
}
