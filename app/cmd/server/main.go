package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"newtalkopt/app/internal/httpapi"
	"newtalkopt/app/internal/probe"
)

const serviceName = "starter-app"

var version = "dev"

func main() {
	healthcheck := flag.Bool("healthcheck", false, "probe the local /healthz endpoint and exit")
	flag.Parse()

	listenAddr := os.Getenv("LISTEN_ADDR")
	if listenAddr == "" {
		listenAddr = ":8080"
	}

	if *healthcheck {
		os.Exit(runHealthcheck(listenAddr))
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := serve(listenAddr, logger); err != nil {
		logger.Error("server stopped with error", "error", err)
		os.Exit(1)
	}
}

func runHealthcheck(listenAddr string) int {
	url, err := probe.LocalURL(listenAddr, "/healthz")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if err := probe.Check(context.Background(), url, 2*time.Second); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}

func serve(listenAddr string, logger *slog.Logger) error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()

	srv := &http.Server{
		Addr:              listenAddr,
		Handler:           httpapi.NewHandler(serviceName, version),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("listening", "addr", listenAddr, "version", version)
		errCh <- srv.ListenAndServe()
	}()

	select {
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
	}

	logger.Info("shutdown signal received")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		return fmt.Errorf("graceful shutdown: %w", err)
	}
	logger.Info("shutdown complete")
	return nil
}
