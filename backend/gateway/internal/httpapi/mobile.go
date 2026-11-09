package httpapi

import (
	"fmt"
	"strings"
)

func assignmentToResponse(a assignmentXML) assignmentResponse {
	trips := make([]tripResponse, 0, len(a.Trips))
	for _, trip := range a.Trips {
		response := tripResponse{tripXML: trip}
		if trip.CargoRaw != "" {
			loaded := strings.EqualFold(trip.CargoRaw, "true")
			response.CargoInVehicle = &loaded
		}
		trips = append(trips, response)
	}
	return assignmentResponse{
		ID:           a.ID,
		Version:      a.Version,
		Number:       a.Number,
		DepartureDay: a.DepartureDay,
		Status:       a.Status,
		CancelReason: a.CancelReason,
		Driver:       a.Driver,
		Vehicle:      a.Vehicle,
		PlanDepart:   a.PlanDepart,
		PlanReturn:   a.PlanReturn,
		// Старые разнарядки (до этого контракта) не несут элемент вообще —
		// пустая строка, EqualFold с "true" даёт false, как и просил механик.
		KeysIssued:     strings.EqualFold(a.KeysIssued, "true"),
		KeysIssuedAt:   a.KeysIssuedAt,
		KeysAccepted:   strings.EqualFold(a.KeysAccepted, "true"),
		KeysAcceptedAt: a.KeysAcceptedAt,
		ShiftClosed:    strings.EqualFold(a.ShiftClosed, "true"),
		ShiftClosedAt:  a.ShiftClosedAt,
		ShiftStarted:   strings.EqualFold(a.ShiftStarted, "true"),
		ShiftStartedAt: a.ShiftStartedAt,
		Replacement:    a.Replacement,
		Basis:          a.Basis,
		Trips:          trips,
		ShiftTotal:     a.ShiftTotal,
	}
}

func eventsRequestToXML(req eventsRequest) eventsXML {
	events := eventsXML{Events: make([]eventXML, 0, len(req.Events))}
	for _, event := range req.Events {
		events.Events = append(events.Events, eventXML{
			ID:         event.ID,
			Type:       event.Type,
			DriverID:   event.DriverID,
			Assignment: event.AssignmentID,
			TripID:     event.TripID,
			Time:       event.Time,
			Comment:    event.Comment,
			Reason:     event.Reason,
			GeoTag:     geoTagRequestToXML(event.GeoTag),
		})
	}
	return events
}

// geoTagRequestToXML — nil, если метки не было (телефон не присылает 0,0 и
// пустышки, см. GeoTagProvider на Android) или координаты вне разумного
// диапазона (защита от мусора, 1С сама такое тоже не отбивает — просто
// примет событие без метки). 6 знаков после точки — ровно то, что просит
// контракт; обычное форматирование float64 в XML этого не гарантирует.
func geoTagRequestToXML(geo *geoTagRequest) *geoTagXML {
	if geo == nil {
		return nil
	}
	if geo.Latitude < -90 || geo.Latitude > 90 || geo.Longitude < -180 || geo.Longitude > 180 {
		return nil
	}
	if geo.Latitude == 0 && geo.Longitude == 0 {
		return nil
	}
	return &geoTagXML{
		Latitude:  fmt.Sprintf("%.6f", geo.Latitude),
		Longitude: fmt.Sprintf("%.6f", geo.Longitude),
		Accuracy:  geo.Accuracy,
		FixTime:   geo.FixTime,
	}
}

func resultXMLToJSON(result resultXML) eventSendResponse {
	response := eventSendResponse{
		Status: result.Status,
		Error:  result.Error,
		Events: make([]eventSendResultJSON, 0, len(result.Events)),
	}
	for _, event := range result.Events {
		accepted := strings.EqualFold(event.Accepted, "true")
		code := ""
		if !accepted {
			code = "one_c_rejected"
		}
		response.Events = append(response.Events, eventSendResultJSON{
			ID:       event.ID,
			Accepted: accepted,
			Error:    event.Error,
			Code:     code,
		})
	}
	return response
}
