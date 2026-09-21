package httpapi

import "encoding/xml"

type assignmentXML struct {
	XMLName      xml.Name   `xml:"Разнарядка"`
	ID           string     `xml:"Идентификатор"`
	Version      int        `xml:"Версия"`
	Number       string     `xml:"Номер"`
	DepartureDay string     `xml:"ДатаВыезда"`
	Status       string     `xml:"Статус"`
	CancelReason string     `xml:"ПричинаОтмены"`
	PlanDepart   string     `xml:"ПланВыезда"`
	PlanReturn   string     `xml:"ПланВозврата"`
	Driver       personXML  `xml:"Водитель"`
	Vehicle      vehicleXML `xml:"Машина"`
	Trips        []tripXML  `xml:"Ездки>Ездка"`
}

type personXML struct {
	ID      string `xml:"Идентификатор" json:"id"`
	Name    string `xml:"ФИО" json:"name,omitempty"`
	StaffID string `xml:"ТабельныйНомер" json:"staff_id,omitempty"`
}

type vehicleXML struct {
	ID    string `xml:"Идентификатор" json:"id"`
	Name  string `xml:"Наименование" json:"name,omitempty"`
	Plate string `xml:"ГосНомер" json:"plate,omitempty"`
}

type trailerXML struct {
	ID    string `xml:"Идентификатор" json:"id,omitempty"`
	Plate string `xml:"ГосНомер" json:"plate,omitempty"`
}

type pointXML struct {
	Name    string `xml:"Наименование" json:"name,omitempty"`
	Address string `xml:"Адрес" json:"address,omitempty"`
}

type cargoXML struct {
	Composition string `xml:"Состав" json:"composition,omitempty"`
	Quantity    string `xml:"Количество" json:"quantity,omitempty"`
	Unit        string `xml:"ЕдиницаИзмерения" json:"unit,omitempty"`
	Weight      string `xml:"Вес" json:"weight,omitempty"`
	Volume      string `xml:"Объем" json:"volume,omitempty"`
}

type tripXML struct {
	ID          string     `xml:"Идентификатор" json:"id"`
	Order       int        `xml:"Порядок" json:"order"`
	Status      string     `xml:"Статус" json:"status"`
	Customer    string     `xml:"Заказчик" json:"customer,omitempty"`
	Trailer     trailerXML `xml:"Прицеп" json:"trailer,omitempty"`
	LoadPoint   pointXML   `xml:"ПунктПогрузки" json:"load_point"`
	UnloadPoint pointXML   `xml:"ПунктРазгрузки" json:"unload_point"`
	PlanLoad    string     `xml:"ПланПогрузки" json:"plan_load,omitempty"`
	PlanUnload  string     `xml:"ПланРазгрузки" json:"plan_unload,omitempty"`
	Cargo       cargoXML   `xml:"Груз" json:"cargo"`
}

type eventsXML struct {
	XMLName xml.Name   `xml:"События"`
	Events  []eventXML `xml:"Событие"`
}

type eventXML struct {
	ID         string `xml:"Идентификатор"`
	Type       string `xml:"Тип"`
	DriverID   string `xml:"Водитель"`
	Assignment string `xml:"Разнарядка"`
	TripID     string `xml:"Ездка"`
	Time       string `xml:"Время"`
	Comment    string `xml:"Комментарий"`
}

type resultXML struct {
	XMLName xml.Name         `xml:"Результат"`
	Status  string           `xml:"статус,attr"`
	Events  []eventResultXML `xml:"Событие,omitempty"`
	Error   string           `xml:"ошибка,attr,omitempty"`
}

type eventResultXML struct {
	ID       string `xml:"ид,attr"`
	Accepted string `xml:"принято,attr"`
	Error    string `xml:"ошибка,attr,omitempty"`
}

// photoResultXML — ответ 1С на POST .../hs/prtr_driver/photo, отдельный
// контракт от событий (одна фотография на запрос, не пачка), см.
// docs/backend-api.md.
type photoResultXML struct {
	XMLName xml.Name `xml:"Результат"`
	ID      string   `xml:"ид,attr"`
	Status  string   `xml:"статус,attr"`
	Error   string   `xml:"ошибка,attr,omitempty"`
}

type deviceXML struct {
	XMLName  xml.Name `xml:"Устройство"`
	DriverID string   `xml:"Водитель"`
	DeviceID string   `xml:"Идентификатор"`
	FCMToken string   `xml:"FCMТокен"`
}

type deviceRequest struct {
	DriverID string `json:"driver_id"`
	DeviceID string `json:"device_id"`
	FCMToken string `json:"fcm_token"`
}

type assignmentResponse struct {
	ID           string     `json:"id"`
	Version      int        `json:"version"`
	Number       string     `json:"number,omitempty"`
	DepartureDay string     `json:"departure_day"`
	Status       string     `json:"status"`
	CancelReason string     `json:"cancel_reason,omitempty"`
	Driver       personXML  `json:"driver"`
	Vehicle      vehicleXML `json:"vehicle"`
	PlanDepart   string     `json:"plan_depart,omitempty"`
	PlanReturn   string     `json:"plan_return,omitempty"`
	Trips        []tripXML  `json:"trips"`
}

type eventsRequest struct {
	Events []eventRequest `json:"events"`
}

type eventRequest struct {
	ID           string `json:"id"`
	Type         string `json:"type"`
	DriverID     string `json:"driver_id"`
	AssignmentID string `json:"assignment_id"`
	TripID       string `json:"trip_id,omitempty"`
	Time         string `json:"time"`
	Comment      string `json:"comment,omitempty"`
}

type eventSendResponse struct {
	Status string                `json:"status"`
	Events []eventSendResultJSON `json:"events,omitempty"`
	Error  string                `json:"error,omitempty"`
}

type eventSendResultJSON struct {
	ID       string `json:"id"`
	Accepted bool   `json:"accepted"`
	Error    string `json:"error,omitempty"`
}

type errorResponse struct {
	Error string `json:"error"`
	Code  string `json:"code"`
}
