# Мобильный АРМ водителя

Репозиторий для Android-приложения водителя и backend-шлюза между приложением и 1С.

Текущий технический выбор:

- Go — основной backend-шлюз.
- Python — локальные инструменты и моки для стыковки/проверки XML-контракта.
- 1С ↔ gateway общаются XML, Android ↔ gateway общается JSON.

## Запуск Go-шлюза локально

```bash
cd backend/gateway
HTTP_PORT=8080 GATEWAY_TOKEN=dev-1c MOBILE_TOKEN=dev-mobile go run ./cmd/gateway
```

Проверка:

```bash
curl http://127.0.0.1:8080/healthz
curl http://127.0.0.1:8080/readyz
```

## Локальный мок 1С

Из корня репозитория:

```bash
python3 tools/python/mock_1c.py --port 18081 --token dev-1c
```

В другом терминале:

```bash
cd backend/gateway
HTTP_PORT=8080 \
GATEWAY_TOKEN=dev-1c \
MOBILE_TOKEN=dev-mobile \
ONE_C_BASE_URL=http://127.0.0.1:18081 \
ONE_C_TOKEN=dev-1c \
ONE_C_USERNAME= \
ONE_C_PASSWORD= \
go run ./cmd/gateway
```

Контракт и инварианты проекта описаны в `architecture.md` и `AGENTS.md`.
Backend API: `docs/backend-api.md`.
Чеклист локальной стыковки с мок-1С: `docs/integration-checklist.md`.

## Запуск на сервере

Сервис рассчитан на запуск в контейнере за Traefik, как соседние проекты в
`~/go/src`: порт наружу не публикуется, вход идёт через external-сеть `edge`.

```bash
cp .env.example .env
docker compose up -d --build
```

Техническое состояние шлюза хранится в Docker volume `arm_driver_gateway_data`:
FCM-токены устройств и последние XML разнарядок для доставки мобильному
приложению после push. 1С всё равно остаётся источником правды; это не
бизнес-БД разнарядок.

Если `FCM_SERVICE_ACCOUNT_FILE` не задан, push не отправляется в Firebase, а
только логируется. Это удобно для стыковки с 1С до подключения Android/FCM.

Если `ONE_C_BASE_URL` не задан, `/api/mobile/events` отвечает `503`: телефон не
должен чистить локальную очередь, пока события реально не ушли в 1С.
