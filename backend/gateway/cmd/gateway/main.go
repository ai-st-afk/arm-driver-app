package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"arm-driver-app/backend/gateway/internal/config"
	"arm-driver-app/backend/gateway/internal/httpapi"
	"arm-driver-app/backend/gateway/internal/push"
	"arm-driver-app/backend/gateway/internal/storage"
)

func main() {
	cfg := config.FromEnv()
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{}))

	if cfg.GatewayToken == "" {
		logger.Warn("GATEWAY_TOKEN is empty: 1C endpoints are open")
	}
	if cfg.MobileToken == "" && cfg.GatewayToken == "" {
		logger.Warn("MOBILE_TOKEN is empty: mobile endpoints are open")
	}
	if cfg.OneCBaseURL == "" {
		logger.Warn("ONE_C_BASE_URL is empty: mobile events will return 503 and stay in the phone queue")
	}

	store, err := storage.New(cfg.DataDir)
	if err != nil {
		logger.Error("storage init failed", "error", err, "data_dir", cfg.DataDir)
		os.Exit(1)
	}

	httpClient := &http.Client{Timeout: 20 * time.Second}
	var pushSender push.Sender = push.NewLogSender(logger, store)
	if cfg.FCMCredsFile != "" {
		fcmSender, err := push.NewFCMSender(logger, httpClient, store, cfg.FCMCredsFile)
		if err != nil {
			logger.Error("fcm init failed", "error", err, "service_account", cfg.FCMCredsFile)
			os.Exit(1)
		}
		pushSender = fcmSender
	}
	api := httpapi.NewServer(cfg, logger, httpClient, store, pushSender)

	go runDocumentCleanup(logger, store, cfg)
	go runReminders(api)

	srv := &http.Server{
		Addr:              ":" + cfg.HTTPPort,
		Handler:           api.Routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	go func() {
		logger.Info("gateway started", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http server error", "error", err)
			os.Exit(1)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop

	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("graceful shutdown failed", "error", err)
	}
}

// runReminders раз в минуту проверяет, не пора ли напомнить водителю, что он
// не принял разнарядку. Минуты достаточно: сроки тут в десятках минут, а
// более частый тик просто дёргал бы диск.
func runReminders(api *httpapi.Server) {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		api.SendDueReminders(context.Background(), time.Now().UTC())
	}
}

// runDocumentCleanup раз в сутки удаляет фото документов старше срока
// хранения (см. config.Config.DocumentPendingRetentionDays/DeliveredRetentionDays).
// Запускается сразу при старте, чтобы не ждать сутки после деплоя.
func runDocumentCleanup(logger *slog.Logger, store *storage.Store, cfg config.Config) {
	pendingTTL := time.Duration(cfg.DocumentPendingRetentionDays) * 24 * time.Hour
	deliveredTTL := time.Duration(cfg.DocumentDeliveredRetentionDays) * 24 * time.Hour

	assignmentTTL := time.Duration(cfg.AssignmentRetentionDays) * 24 * time.Hour

	for {
		deleted, err := store.CleanupOldDocuments(time.Now(), pendingTTL, deliveredTTL)
		if err != nil {
			logger.Error("document cleanup failed", "error", err)
		} else if deleted > 0 {
			logger.Info("document cleanup", "deleted", deleted)
		}

		assignments, err := store.CleanupOldAssignments(time.Now(), assignmentTTL)
		if err != nil {
			logger.Error("assignment cleanup failed", "error", err)
		} else if assignments > 0 {
			logger.Info("assignment cleanup", "deleted", assignments)
		}

		time.Sleep(24 * time.Hour)
	}
}
