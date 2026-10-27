package httpapi

import (
	"encoding/json"
	"encoding/xml"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"testing"

	"arm-driver-app/backend/gateway/internal/config"
)

func TestCargoContactsDeliveredToMobile(t *testing.T) {
	raw, err := os.ReadFile("../../../../docs/samples/assignment-cargo-contacts.xml")
	if err != nil {
		t.Fatal(err)
	}
	// Этот же JSON декодируется Android-тестом: проверяем общую границу обмена.
	fixture, err := os.ReadFile("../../../../android/app/src/test/resources/assignment-cargo-contacts.json")
	if err != nil {
		t.Fatal(err)
	}
	var expected assignmentResponse
	if err := json.Unmarshal(fixture, &expected); err != nil {
		t.Fatal(err)
	}
	api := newTestServer(t, config.Config{GatewayToken: "one-c-token", MobileToken: "mobile-token"})
	post := httptest.NewRequest(http.MethodPost, "/api/1c/assignments", strings.NewReader(string(raw)))
	post.Header.Set("X-Auth-Token", "one-c-token")
	post.Header.Set("Content-Type", "application/xml; charset=utf-8")
	rec := httptest.NewRecorder()
	api.Routes().ServeHTTP(rec, post)
	if rec.Code != http.StatusOK {
		t.Fatalf("post: %d %s", rec.Code, rec.Body.String())
	}
	for _, path := range []string{
		"/api/mobile/assignments/b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f",
		"/api/mobile/assignments?driver_id=3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162",
	} {
		t.Run(path, func(t *testing.T) {
			get := httptest.NewRequest(http.MethodGet, path, nil)
			get.Header.Set("X-Auth-Token", "mobile-token")
			rec := httptest.NewRecorder()
			api.Routes().ServeHTTP(rec, get)
			if rec.Code != http.StatusOK {
				t.Fatalf("get: %d %s", rec.Code, rec.Body.String())
			}
			var a assignmentResponse
			if strings.Contains(path, "?") {
				var list assignmentsListResponse
				if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
					t.Fatal(err)
				}
				if len(list.Assignments) != 1 {
					t.Fatalf("assignments: %+v", list)
				}
				a = list.Assignments[0]
			} else if err := json.Unmarshal(rec.Body.Bytes(), &a); err != nil {
				t.Fatal(err)
			}
			if a.ShiftTotal == nil || len(a.ShiftTotal.Positions.Items) != 2 || a.ShiftTotal.Weight != "18.500" {
				t.Fatalf("total: %+v", a.ShiftTotal)
			}
			if len(a.Trips) != 2 {
				t.Fatalf("trips: %+v", a.Trips)
			}
			if !reflect.DeepEqual(a.ShiftTotal, expected.ShiftTotal) {
				t.Fatalf("total differs from Android fixture: %+v", a.ShiftTotal)
			}
			for i, trip := range a.Trips {
				if !reflect.DeepEqual(trip.Cargo, expected.Trips[i].Cargo) || !reflect.DeepEqual(trip.Contacts, expected.Trips[i].Contacts) {
					t.Fatalf("trip %d differs from Android fixture", i)
				}
			}
			trip := a.Trips[0]
			if trip.Cargo.Positions == nil || len(trip.Cargo.Positions.Items) != 2 {
				t.Fatalf("positions: %+v", trip.Cargo)
			}
			item := trip.Cargo.Positions.Items[0]
			if item.ProductID != "5d0c7e12-3a4b-4c8d-9e1f-2a3b4c5d6e7f" || item.Name != "Плита ПК 60-15" || item.Quantity != "4.000" || item.Unit != "шт" || item.Weight != "6.200" || item.Volume != "2.400" {
				t.Fatalf("item: %+v", item)
			}
			if len(trip.Contacts) != 4 {
				t.Fatalf("contacts: %+v", trip.Contacts)
			}
			contact := trip.Contacts[0]
			if contact.Purpose != "Разгрузка" || contact.Role != "Контактное лицо" || contact.Name != "Евгений Петров" || len(contact.Phones) != 2 || contact.Phones[0].Number != "+78332405550" || contact.Phones[0].Display != "8 (8332) 40-55-50" {
				t.Fatalf("contact: %+v", contact)
			}
			if len(trip.Contacts[2].Phones) != 0 || trip.Contacts[3].Name != "" || trip.Contacts[3].Phones[0].Number != "405550" || trip.Contacts[3].Phones[1].Number != "" {
				t.Fatalf("optional contacts: %+v", trip.Contacts)
			}
			cancelled := a.Trips[1]
			if cancelled.Status != "Отменена" || cancelled.Cargo.Positions == nil || len(cancelled.Cargo.Positions.Items) != 0 || len(cancelled.Contacts) != 0 {
				t.Fatalf("cancelled: %+v", cancelled)
			}
		})
	}
}

func TestCargoBlocksPreserveAbsentAndEmpty(t *testing.T) {
	for _, test := range []struct {
		name, blocks, cargo string
		present             bool
	}{
		{name: "legacy", cargo: "<Состав>Старое описание</Состав>"},
		{name: "empty", blocks: "<ИтогоЗаСмену><Позиции/><Вес>0.000</Вес><Объем>0.000</Объем></ИтогоЗаСмену>", cargo: "<Состав>Старое описание</Состав><Позиции/>", present: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			var a assignmentXML
			if err := xml.Unmarshal([]byte("<Разнарядка>"+test.blocks+"<Ездки><Ездка><Груз>"+test.cargo+"</Груз><Контакты/></Ездка></Ездки></Разнарядка>"), &a); err != nil {
				t.Fatal(err)
			}
			raw, err := json.Marshal(assignmentToResponse(a))
			if err != nil {
				t.Fatal(err)
			}
			var decoded assignmentResponse
			if err := json.Unmarshal(raw, &decoded); err != nil {
				t.Fatal(err)
			}
			if (decoded.ShiftTotal != nil) != test.present || (decoded.Trips[0].Cargo.Positions != nil) != test.present {
				t.Fatalf("presence lost: %s", raw)
			}
			if test.present && (!strings.Contains(string(raw), `"positions":{}`) || decoded.ShiftTotal.Weight != "0.000") {
				t.Fatalf("empty block: %s", raw)
			}
		})
	}
}

func TestShiftTotalIsForwardedWithoutAggregation(t *testing.T) {
	var a assignmentXML
	raw := `<Разнарядка><ИтогоЗаСмену><Позиции>
      <Позиция><НоменклатураИдентификатор>same</НоменклатураИдентификатор><ЕдиницаИзмерения>шт</ЕдиницаИзмерения><Количество>12.000</Количество></Позиция>
      <Позиция><НоменклатураИдентификатор>same</НоменклатураИдентификатор><ЕдиницаИзмерения>м³</ЕдиницаИзмерения><Количество>7.000</Количество></Позиция>
      </Позиции><Вес>99.000</Вес><Объем>88.000</Объем></ИтогоЗаСмену><Ездки/></Разнарядка>`
	if err := xml.Unmarshal([]byte(raw), &a); err != nil {
		t.Fatal(err)
	}
	total := assignmentToResponse(a).ShiftTotal
	if total == nil || len(total.Positions.Items) != 2 || total.Weight != "99.000" || total.Volume != "88.000" || total.Positions.Items[1].Unit != "м³" {
		t.Fatalf("total changed: %+v", total)
	}
}
