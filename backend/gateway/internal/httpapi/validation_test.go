package httpapi

import "testing"

func TestValidateEventsRequiresTripForTripLevelEvent(t *testing.T) {
	batch := eventsXML{Events: []eventXML{{
		ID:         "8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31",
		Type:       "ПрибылНаПогрузку",
		DriverID:   "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162",
		Assignment: "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f",
		Time:       "2026-09-10T07:34:12+03:00",
	}}}

	if err := validateEvents(batch); err == nil {
		t.Fatal("expected validation error")
	}
}

func TestValidateEventsRejectsTripForAssignmentLevelEvent(t *testing.T) {
	batch := eventsXML{Events: []eventXML{{
		ID:         "8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31",
		Type:       "НачалоСмены",
		DriverID:   "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162",
		Assignment: "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f",
		TripID:     "e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b",
		Time:       "2026-09-10T07:34:12+03:00",
	}}}

	if err := validateEvents(batch); err == nil {
		t.Fatal("expected validation error")
	}
}

func TestValidateEventsRejectsBrokenTimezone(t *testing.T) {
	batch := eventsXML{Events: []eventXML{{
		ID:         "8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31",
		Type:       "Ознакомление",
		DriverID:   "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162",
		Assignment: "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f",
		Time:       "2026-09-10T07:34:12+03:",
	}}}

	if err := validateEvents(batch); err == nil {
		t.Fatal("expected validation error")
	}
}

func TestValidateEventsAcceptsFailureWithComment(t *testing.T) {
	batch := eventsXML{Events: []eventXML{{
		ID:         "8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31",
		Type:       "Срыв",
		DriverID:   "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162",
		Assignment: "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f",
		TripID:     "e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b",
		Time:       "2026-09-10T07:34:12+03:00",
		Comment:    "сломался подъезд к объекту",
	}}}

	if err := validateEvents(batch); err != nil {
		t.Fatalf("expected valid batch: %v", err)
	}
}
