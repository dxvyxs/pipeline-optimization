package httpapi

import (
	"encoding/json"
	"net/http"
)

type serviceInfo struct {
	Service string `json:"service"`
	Version string `json:"version"`
}

func NewHandler(service, version string) http.Handler {
	mux := http.NewServeMux()
	info := serviceInfo{Service: service, Version: version}

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})

	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		_ = json.NewEncoder(w).Encode(info)
	})

	return mux
}
