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
