package config

import "os"

type Config struct {
	HTTPPort      string
	DataDir       string
	GatewayToken  string
	MobileToken   string
	OneCBaseURL   string
	OneCToken     string
	OneCEventsURL string
	OneCUsername  string
	OneCPassword  string
	FCMCredsFile  string
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
		OneCUsername:  os.Getenv("ONE_C_USERNAME"),
		OneCPassword:  os.Getenv("ONE_C_PASSWORD"),
		FCMCredsFile:  os.Getenv("FCM_SERVICE_ACCOUNT_FILE"),
	}
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
