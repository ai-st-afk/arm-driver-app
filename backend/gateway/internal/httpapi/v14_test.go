package httpapi

import (
	"encoding/json"
	"encoding/xml"
	"os"
	"reflect"
	"strings"
	"testing"
)

func TestV14LoadedTripMatchesAndroidFixture(t *testing.T) {
	raw, err := os.ReadFile("../../../../docs/samples/replacement-loaded.xml")
	if err != nil {
		t.Fatal(err)
	}
	var assignment assignmentXML
	if err := xml.Unmarshal(raw, &assignment); err != nil {
		t.Fatal(err)
	}
	if err := validateAssignment(assignment); err != nil {
		t.Fatal(err)
	}
	actualJSON, err := json.Marshal(assignmentToResponse(assignment))
	if err != nil {
		t.Fatal(err)
	}
	expectedJSON, err := os.ReadFile("../../../../android/app/src/test/resources/replacement-loaded.json")
	if err != nil {
		t.Fatal(err)
	}
	var actual, expected map[string]any
	if err := json.Unmarshal(actualJSON, &actual); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(expectedJSON, &expected); err != nil {
		t.Fatal(err)
	}
	actualTrip := actual["trips"].([]any)[0].(map[string]any)
	expectedTrip := expected["trips"].([]any)[0].(map[string]any)
	for _, key := range []string{"basis_trip_id", "stages", "cargo_in_vehicle", "next_stage"} {
		if !reflect.DeepEqual(actualTrip[key], expectedTrip[key]) {
			t.Errorf("%s: got %v, want %v", key, actualTrip[key], expectedTrip[key])
		}
	}
	if actual["shift_started_at"] != expected["shift_started_at"] {
		t.Errorf("shift_started_at: got %v, want %v", actual["shift_started_at"], expected["shift_started_at"])
	}
}

func TestV14RejectsBadTripStage(t *testing.T) {
	raw, err := os.ReadFile("../../../../docs/samples/replacement-loaded.xml")
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct{ name, from, to string }{
		{"bad time", "2026-11-09T08:50:00+03:00", "2026-11-09T08:50:00+03:"},
		{"bad cargo flag", "<ГрузВМашине>true</ГрузВМашине>", "<ГрузВМашине>maybe</ГрузВМашине>"},
		{"bad next stage", "<СледующийЭтап>ПрибылНаРазгрузку</СледующийЭтап>", "<СледующийЭтап>Неизвестно</СледующийЭтап>"},
		{"removed next stage", "<СледующийЭтап>ПрибылНаРазгрузку</СледующийЭтап>", "<СледующийЭтап>ПринялЗадание</СледующийЭтап>"},
		{"empty next stage before unloading", "<СледующийЭтап>ПрибылНаРазгрузку</СледующийЭтап>", "<СледующийЭтап/>"},
		{"removed stage", "<ПрибылНаПогрузку>2026-11-09T08:25:00+03:00</ПрибылНаПогрузку>", "<ПринялЗадание>2026-11-09T08:25:00+03:00</ПринялЗадание>"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var assignment assignmentXML
			if err := xml.Unmarshal([]byte(strings.Replace(string(raw), tc.from, tc.to, 1)), &assignment); err != nil {
				t.Fatal(err)
			}
			if err := validateAssignment(assignment); err == nil {
				t.Fatal("expected validation error")
			}
		})
	}
}
