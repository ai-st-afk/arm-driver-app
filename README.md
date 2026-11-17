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
cp .env.example .env.prod
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

## Тестовый и боевой шлюзы

На `srv-ai` работают два экземпляра одного Go-шлюза с разными настройками и
Docker volumes:

| Контур | Адрес телефона | Входящие разнарядки от 1С | Конфигурация | APK |
|---|---|---|---|---|
| test3 | `https://arm-driver.profstroy-services.ru/` | `/api/1c/assignments` | `.env` | `/download` |
| Боевая база | `https://arm-driver.profstroy-services.ru/prod/` | `/prod/api/1c/assignments` | `.env.prod` | `/prod/download` |

В `.env.prod` находятся отдельные `GATEWAY_TOKEN` и `MOBILE_TOKEN`; наружу эти
значения не выкладывать. `ONE_C_BASE_URL` указывает на боевую 1С, а настройки
test3 остаются в `.env`. Префикс `/prod` снимает Traefik; само приложение шлюза
и XML-контракт одинаковы. Файлы APK лежат отдельно в `releases/` и
`releases-prod/`.

APK 0.9.14 собран для боевого адреса. Уже установленные 0.9.13 смотрят за
обновлениями в test3 и не перейдут сами: один раз установить боевой APK по
`/prod/download` поверх существующего приложения. Сначала убедиться, что нет
неотправленных событий и фото test3; затем очистить только кэш разнарядок
через «Служебный вход» и проверить привязку по QR из боевой 1С. Дальше
автообновления приходят с боевого шлюза. Выпуск боевого APK выполняется
`tools/release-apk.sh` с отдельной папкой публикации:

```bash
ARM_DRIVER_GATEWAY_BASE_URL=https://arm-driver.profstroy-services.ru/prod/ \
ARM_DRIVER_MOBILE_TOKEN_FILE="$HOME/.arm-driver/prod-mobile-token" \
ARM_DRIVER_REMOTE_RELEASES_DIR=apps/arm-driver-app/releases-prod \
ARM_DRIVER_APK_LABEL=prod \
ARM_DRIVER_SERVER=admin-ai@192.168.26.163 \
ARM_DRIVER_SSH_KEY_FILE="$HOME/.ssh/id_ed25519_arm_driver" \
tools/release-apk.sh
```
