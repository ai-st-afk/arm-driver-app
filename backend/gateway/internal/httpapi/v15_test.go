package httpapi

import (
	"encoding/xml"
	"strings"
	"testing"
)

func TestV15ReturnReachesMobile(t *testing.T) {
	raw := `<Разнарядка><Идентификатор>a</Идентификатор><Версия>3</Версия><ДатаВыезда>2026-11-11</ДатаВыезда><Водитель><Идентификатор>d</Идентификатор></Водитель><Машина><Идентификатор>v</Идентификатор></Машина><Ездки><Ездка><Идентификатор>t</Идентификатор><Порядок>1</Порядок><Статус>Назначена</Статус><Возврат>true</Возврат><СледующийЭтап>ПрибылНаРазгрузку</СледующийЭтап><ПунктРазгрузки><Адрес>Склад завода</Адрес></ПунктРазгрузки></Ездка></Ездки></Разнарядка>`
	var assignment assignmentXML
	if err := xml.Unmarshal([]byte(raw), &assignment); err != nil {
		t.Fatal(err)
	}
	if err := validateAssignment(assignment); err != nil {
		t.Fatal(err)
	}
	trip := assignmentToResponse(assignment).Trips[0]
	if trip.ReturnToDepot == nil || !*trip.ReturnToDepot || trip.UnloadPoint.Address != "Склад завода" {
		t.Fatalf("return route lost: %+v", trip)
	}
	assignment.Trips[0].ReturnRaw = "не знаю"
	if err := validateAssignment(assignment); err == nil {
		t.Fatal("invalid return flag accepted")
	}
}

func TestV15FailureReasonCodeInXML(t *testing.T) {
	req := eventsRequest{Events: []eventRequest{{
		ID: "event", Type: "Срыв", DriverID: "driver", AssignmentID: "assignment", TripID: "trip",
		Time: "2026-11-11T11:20:00+03:00", Reason: "Клиент не принимает: скол", ReasonCode: "Качество",
	}}}
	batch := eventsRequestToXML(req)
	if err := validateEvents(batch); err != nil {
		t.Fatal(err)
	}
	encoded, err := xml.Marshal(batch)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(encoded), "<КодПричины>Качество</КодПричины>") {
		t.Fatalf("reason code missing: %s", encoded)
	}
	batch.Events[0].ReasonCode = "неизвестно"
	if err := validateEvents(batch); err == nil {
		t.Fatal("invalid reason code accepted")
	}
}
