# Backend API

Шлюз разводит два формата:

- 1С ↔ gateway: XML по согласованному контракту.
- Android ↔ gateway: JSON, чтобы не тащить XML-парсинг в приложение.

Все запросы требуют:

```http
X-Auth-Token: <token>
```

Для XML: `Content-Type: application/xml; charset=utf-8`, UTF-8 без BOM.
Для JSON: `Content-Type: application/json; charset=utf-8`.

## Service

### `GET /healthz`

Liveness-check.

### `GET /readyz`

JSON-статус конфигурации:

```json
{
  "status": "ok",
  "one_c_configured": true,
  "fcm_configured": false
}
```

## 1C -> Gateway

### `POST /api/1c/assignments`

Принимает полный XML `Разнарядка`.

Шлюз:

- валидирует обязательные поля и даты;
- сохраняет исходный XML в технический delivery-cache;
- игнорирует устаревшую версию той же разнарядки;
- отправляет FCM push водителю, если зарегистрирован токен.

Ответ:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Результат статус="ok"></Результат>
```

## Android -> Gateway

### `POST /api/mobile/devices`

Регистрирует FCM-токен устройства.

```json
{
  "driver_id": "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162",
  "device_id": "android-device-id",
  "fcm_token": "..."
}
```

Ответ:

```json
{"status":"ok"}
```

### `GET /api/mobile/assignments/current?driver_id=<guid>`

Возвращает последнюю разнарядку водителя в JSON.

### `GET /api/mobile/assignments/{id}`

Возвращает разнарядку по GUID в JSON.

### `GET /api/mobile/assignments/current/xml?driver_id=<guid>`

### `GET /api/mobile/assignments/{id}/xml`

Диагностические ручки: возвращают исходный XML разнарядки, который пришёл от 1С.
Нужны для стыковки и сверки, Android в обычном сценарии использует JSON-ручки.

### `POST /api/mobile/events`

Принимает события от телефона в JSON, валидирует инварианты контракта, собирает
XML `События` и форвардит пачку в 1С:

```http
POST ${ONE_C_BASE_URL}/prtr_driver/events
```

Запрос:

```json
{
  "events": [
    {
      "id": "b3e2648a-9f42-4c18-b994-b03f4a705977",
      "type": "ПрибылНаПогрузку",
      "driver_id": "3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162",
      "assignment_id": "b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f",
      "trip_id": "e5f6a7b8-1c2d-4e3f-9a0b-5c6d7e8f9a0b",
      "time": "2026-09-10T07:34:12+03:00",
      "comment": ""
    }
  ]
}
```

Ответ — JSON-проекция поштучного ответа 1С:

```json
{
  "status": "ok",
  "events": [
    {
      "id": "b3e2648a-9f42-4c18-b994-b03f4a705977",
      "accepted": true
    },
    {
      "id": "9a4b2d3f-5c8e-4b02-8d22-3f6e7d1b8c42",
      "accepted": false,
      "error": "Разгрузка без отметки прибытия на разгрузку"
    }
  ]
}
```

Телефон удаляет из локальной очереди только события с `accepted: true`.

Если `ONE_C_BASE_URL` не задан или 1С недоступна, endpoint отвечает не-2xx JSON
ошибкой; телефон должен оставить события в локальной очереди.

Формат ошибки для Android:

```json
{
  "code": "validation_error",
  "error": "событие ...: для Срыв обязателен Комментарий"
}
```

### `POST /api/mobile/documents`

Фото подписанного документа с разгрузки (снимается только с камеры, не из
галереи — так решили на стороне Android). `multipart/form-data`:

- `driver_id`, `trip_id` — обязательные текстовые поля.
- `assignment_id` — текстовое поле, необязательное.
- `photo` — файл, JPEG или PNG.

Ответ:

```json
{"status": "ok", "id": "a1b2c3..."}
```

**В 1С фото пока не пересылается** — контракт `<Событие>` не рассчитан на
вложения, менять его в одностороннем порядке нельзя. Файл лежит в
техническом delivery-cache (`DATA_DIR/documents`) до появления
согласованного с 1С-командой способа доставки.

Срок хранения — `DOCUMENT_PENDING_RETENTION_DAYS` (по умолчанию 90) для
не подтверждённых доставленными в 1С, `DOCUMENT_DELIVERED_RETENTION_DAYS`
(по умолчанию 7) — после подтверждения. Чистка раз в сутки в фоне.
