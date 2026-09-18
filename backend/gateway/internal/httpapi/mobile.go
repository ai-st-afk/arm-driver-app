package httpapi

import "strings"

func assignmentToResponse(a assignmentXML) assignmentResponse {
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
		Trips:        a.Trips,
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
		})
	}
	return events
}

func resultXMLToJSON(result resultXML) eventSendResponse {
	response := eventSendResponse{
		Status: result.Status,
		Error:  result.Error,
		Events: make([]eventSendResultJSON, 0, len(result.Events)),
	}
	for _, event := range result.Events {
		response.Events = append(response.Events, eventSendResultJSON{
			ID:       event.ID,
			Accepted: strings.EqualFold(event.Accepted, "true"),
			Error:    event.Error,
		})
	}
	return response
}
