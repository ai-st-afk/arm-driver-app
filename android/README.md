# АРМ водителя — Android

Kotlin, Jetpack Compose, MVVM, Hilt, Retrofit/OkHttp, Room, FCM.
Контракт backend: [../docs/backend-api.md](../docs/backend-api.md).
Правила проекта: [../AGENTS.md](../AGENTS.md).

## Первый запуск

1. Открыть папку `android/` в Android Studio (не корень репозитория).
   При первом открытии Studio предложит создать Gradle wrapper (`gradlew`) —
   в репозитории сейчас лежит только `gradle-wrapper.properties` с нужной
   версией Gradle, сам `gradle-wrapper.jar` Studio доставит на синхронизации.
2. Скопировать `local.properties.example` → `local.properties`, вписать
   `gateway.mobileToken` (см. `.env` на сервере или спросить у автора).
   Файл не коммитится.
3. Дождаться Gradle sync, собрать/запустить на эмуляторе или устройстве
   Android 13+.

## Структура

- `di` — Hilt-модули
- `data.network` — Retrofit API, DTO, интерсептор авторизации
- `data.db` — Room (кэш разнарядки, очередь событий)
- `data.repository` — сведение сети и кэша к одному источнику для ViewModel
- `ui.*` — экраны по фиче (Compose + ViewModel)

Без отдельного domain-слоя — проект простой, лишние абстракции не нужны
(см. `AGENTS.md` → «Стиль»).
