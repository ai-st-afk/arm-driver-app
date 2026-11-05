package httpapi

import (
	"encoding/xml"
	"strings"
	"testing"
)

func TestV13AssignmentFieldsReachMobile(t *testing.T) {
	input := `<Разнарядка><Идентификатор>a</Идентификатор><Версия>7</Версия><ДатаВыезда>2026-11-05</ДатаВыезда><Водитель><Идентификатор>d</Идентификатор></Водитель><Машина><Идентификатор>v</Идентификатор></Машина><КлючиПриняты>true</КлючиПриняты><ВремяПриемаКлючей>2026-11-05T18:40:00+03:00</ВремяПриемаКлючей><СменаЗакрыта>false</СменаЗакрыта><Ездки><Ездка><Идентификатор>t</Идентификатор><Порядок>1</Порядок><Статус>Отменена</Статус><ПричинаОтмены>Клиент перенёс приёмку</ПричинаОтмены></Ездка></Ездки></Разнарядка>`
	var assignment assignmentXML
	if err := xml.Unmarshal([]byte(input), &assignment); err != nil {
		t.Fatal(err)
	}
	if err := validateAssignment(assignment); err != nil {
		t.Fatal(err)
	}
	response := assignmentToResponse(assignment)
	if !response.KeysAccepted || response.KeysAcceptedAt == "" || response.ShiftClosed || response.Trips[0].CancelReason != "Клиент перенёс приёмку" {
		t.Fatalf("v1.3 fields lost: %+v", response)
	}
}

func TestV13FailureReasonInXML(t *testing.T) {
	req := eventsRequest{Events: []eventRequest{{
		ID: "event", Type: "Срыв", DriverID: "driver", AssignmentID: "assignment", TripID: "trip",
		Time: "2026-11-05T11:20:00+03:00", Reason: "Клиент не принимает", Comment: "Охрана не пускает",
	}}}
	batch := eventsRequestToXML(req)
	if err := validateEvents(batch); err != nil {
		t.Fatal(err)
	}
	encoded, err := xml.Marshal(batch)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(encoded), "<Причина>Клиент не принимает</Причина>") {
		t.Fatalf("reason missing: %s", encoded)
	}
}
