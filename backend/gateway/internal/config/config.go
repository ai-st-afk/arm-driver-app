package config

import (
	"os"
	"strconv"
)

type Config struct {
	HTTPPort      string
	DataDir       string
	GatewayToken  string
	MobileToken   string
	OneCBaseURL   string
	OneCToken     string
	OneCEventsURL string
	OneCPhotoURL  string
	OneCUsername  string
	OneCPassword  string
	FCMCredsFile  string

	// Сроки хранения фото документов (technical delivery-cache, не источник
	// истины) — см. DEVLOG: 90 дней, пока фото не подтверждено доставленным
	// в 1С (сейчас доставки в 1С ещё нет вообще, так что действует всегда
	// эта цифра), 7 дней после подтверждения.
	DocumentPendingRetentionDays   int
	DocumentDeliveredRetentionDays int

	// За сколько часов назад показывать разнарядки водителю в списке
	// GET /api/mobile/assignments. Не календарная дата — инвариант "разнарядка
	// доступна с вечера" означает, что "завтрашняя" разнарядка легитимно
	// приходит вечером текущего дня. 48 часов покрывает "с вечера" плюс
	// весь день; если окно окажется не тем — меняется одной переменной.
	AssignmentListWindowHours int

	// Через сколько минут после прихода разнарядки напоминать водителю, что
	// он её ещё не принял. У него час на приём (дальше диспетчер звонит
	// вручную), поэтому 30 минут — половина срока, 50 — «осталось 10 минут».
	AssignmentReminderFirstMinutes  int
	AssignmentReminderSecondMinutes int

	// Сколько держать XML разнарядок в delivery-cache. История разнарядок
	// живёт в 1С, у нас это только доставка на телефон.
	AssignmentRetentionDays int
}

func FromEnv() Config {
	baseURL := os.Getenv("ONE_C_BASE_URL")
	return Config{
		HTTPPort:      env("HTTP_PORT", "8080"),
		DataDir:       env("DATA_DIR", "./data"),
		GatewayToken:  os.Getenv("GATEWAY_TOKEN"),
		MobileToken:   os.Getenv("MOBILE_TOKEN"),
		OneCBaseURL:   baseURL,
		OneCToken:     os.Getenv("ONE_C_TOKEN"),
		OneCEventsURL: baseURL + "/prtr_driver/events",
		OneCPhotoURL:  baseURL + "/prtr_driver/photo",
		OneCUsername:  os.Getenv("ONE_C_USERNAME"),
		OneCPassword:  os.Getenv("ONE_C_PASSWORD"),
		FCMCredsFile:  os.Getenv("FCM_SERVICE_ACCOUNT_FILE"),

		DocumentPendingRetentionDays:   envInt("DOCUMENT_PENDING_RETENTION_DAYS", 90),
		DocumentDeliveredRetentionDays: envInt("DOCUMENT_DELIVERED_RETENTION_DAYS", 7),

		AssignmentListWindowHours: envInt("ASSIGNMENT_LIST_WINDOW_HOURS", 48),

		AssignmentReminderFirstMinutes:  envInt("ASSIGNMENT_REMINDER_FIRST_MINUTES", 30),
		AssignmentReminderSecondMinutes: envInt("ASSIGNMENT_REMINDER_SECOND_MINUTES", 50),

		AssignmentRetentionDays: envInt("ASSIGNMENT_RETENTION_DAYS", 30),
	}
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func envInt(key string, fallback int) int {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}
