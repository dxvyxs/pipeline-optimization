package probe

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestLocalURL(t *testing.T) {
	cases := map[string]string{
		":8080":          "http://127.0.0.1:8080/healthz",
		"0.0.0.0:9000":   "http://127.0.0.1:9000/healthz",
		"[::]:8080":      "http://127.0.0.1:8080/healthz",
		"127.0.0.1:8081": "http://127.0.0.1:8081/healthz",
	}
	for addr, want := range cases {
		got, err := LocalURL(addr, "/healthz")
		if err != nil {
			t.Fatalf("LocalURL(%q) error: %v", addr, err)
		}
		if got != want {
			t.Fatalf("LocalURL(%q) = %q, want %q", addr, got, want)
		}
	}
}

func TestLocalURLRejectsInvalidAddress(t *testing.T) {
	if _, err := LocalURL("8080", "/healthz"); err == nil {
		t.Fatal("expected an error for an address without a port separator")
	}
}

func TestCheckSucceedsOn200(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	if err := Check(context.Background(), srv.URL, time.Second); err != nil {
		t.Fatalf("Check returned %v, want nil", err)
	}
}

func TestCheckFailsOnNon200(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	if err := Check(context.Background(), srv.URL, time.Second); err == nil {
		t.Fatal("Check returned nil for a 503 response")
	}
}

func TestCheckFailsWhenUnreachable(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	url := srv.URL
	srv.Close()

	if err := Check(context.Background(), url, 500*time.Millisecond); err == nil {
		t.Fatal("Check returned nil for a closed server")
	}
}
