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

func TestReplacementAssignmentFieldsReachMobile(t *testing.T) {
	oldXML := `<Разнарядка><Идентификатор>old</Идентификатор><Версия>5</Версия><ДатаВыезда>2026-11-06</ДатаВыезда><Статус>Заменена</Статус><Замена><Идентификатор>new</Идентификатор><Номер>37</Номер></Замена><Водитель><Идентификатор>driver</Идентификатор></Водитель><Машина><Идентификатор>old-vehicle</Идентификатор></Машина><КлючиПриняты>false</КлючиПриняты><СменаЗакрыта>false</СменаЗакрыта></Разнарядка>`
	newXML := `<Разнарядка><Идентификатор>new</Идентификатор><Версия>2</Версия><ДатаВыезда>2026-11-06</ДатаВыезда><Статус>Активна</Статус><Основание><Идентификатор>old</Идентификатор><Номер>36</Номер></Основание><СменаНачата>true</СменаНачата><КлючиВыданы>false</КлючиВыданы><Водитель><Идентификатор>driver</Идентификатор></Водитель><Машина><Идентификатор>new-vehicle</Идентификатор></Машина></Разнарядка>`
	for _, tc := range []struct {
		name  string
		raw   string
		check func(assignmentResponse) bool
	}{
		{"old", oldXML, func(a assignmentResponse) bool {
			return a.Status == "Заменена" && a.Replacement != nil && a.Replacement.ID == "new" && a.Replacement.Number == "37" && !a.ShiftClosed
		}},
		{"new", newXML, func(a assignmentResponse) bool {
			return a.Basis != nil && a.Basis.ID == "old" && a.ShiftStarted && !a.KeysIssued
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var assignment assignmentXML
			if err := xml.Unmarshal([]byte(tc.raw), &assignment); err != nil {
				t.Fatal(err)
			}
			if err := validateAssignment(assignment); err != nil {
				t.Fatal(err)
			}
			if !tc.check(assignmentToResponse(assignment)) {
				t.Fatalf("replacement fields lost: %+v", assignmentToResponse(assignment))
			}
		})
	}
}

func TestReplacedAssignmentRequiresReplacementLink(t *testing.T) {
	assignment := assignmentXML{ID: "old", Version: 2, Status: "Заменена", Driver: personXML{ID: "driver"}, Vehicle: vehicleXML{ID: "vehicle"}}
	if err := validateAssignment(assignment); err == nil {
		t.Fatal("missing replacement link accepted")
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
