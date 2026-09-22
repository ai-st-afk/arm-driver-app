# Integration checklist

Локальная стыковка без Android:

1. Поднять мок 1С:

```bash
python3 tools/python/mock_1c.py --port 18081 --token dev-1c
```

2. Поднять gateway:

```bash
cd backend/gateway
HTTP_PORT=8080 \
GATEWAY_TOKEN=dev-gateway \
MOBILE_TOKEN=dev-mobile \
ONE_C_BASE_URL=http://127.0.0.1:18081 \
ONE_C_TOKEN=dev-1c \
ONE_C_USERNAME= \
ONE_C_PASSWORD= \
go run ./cmd/gateway
```

3. Отправить разнарядку как будто от 1С:

```bash
curl -i \
  -H 'X-Auth-Token: dev-gateway' \
  -H 'Content-Type: application/xml; charset=utf-8' \
  --data-binary @docs/samples/assignment.xml \
  http://127.0.0.1:8080/api/1c/assignments
```

4. Проверить JSON-список для Android:

```bash
curl -i \
  -H 'X-Auth-Token: dev-mobile' \
  'http://127.0.0.1:8080/api/mobile/assignments?driver_id=3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162'
```

5. Проверить исходный XML для сверки с 1С (id — из ответа шага 3/4 или
   `docs/samples/assignment.xml`):

```bash
curl -i \
  -H 'X-Auth-Token: dev-mobile' \
  'http://127.0.0.1:8080/api/mobile/assignments/b1e4f207-9a3c-4d15-8e77-0c6b5a4d3e2f/xml'
```

6. Отправить событие как будто от Android:

```bash
curl -i \
  -H 'X-Auth-Token: dev-mobile' \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @docs/samples/mobile-events.json \
  http://127.0.0.1:8080/api/mobile/events
```

Ожидаемый результат последнего шага: JSON с поштучным результатом. Если 1С
вернула `accepted: false`, это транспортно тоже успешная проверка: формат дошёл,
а причина отказа берётся из бизнес-валидации 1С. Телефон удаляет из локальной
очереди только события с `accepted: true`.
