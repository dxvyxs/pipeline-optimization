package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthzReturnsOK(t *testing.T) {
	rec := httptest.NewRecorder()
	NewHandler("starter-app", "test").ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
	}
	if got := rec.Body.String(); got != "ok\n" {
		t.Fatalf("body = %q, want %q", got, "ok\n")
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", got)
	}
}

func TestRootReturnsServiceInfo(t *testing.T) {
	rec := httptest.NewRecorder()
	NewHandler("starter-app", "1.2.3").ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
	}
	var got serviceInfo
	if err := json.NewDecoder(rec.Body).Decode(&got); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	want := serviceInfo{Service: "starter-app", Version: "1.2.3"}
	if got != want {
		t.Fatalf("body = %+v, want %+v", got, want)
	}
}

func TestUnknownPathReturnsNotFound(t *testing.T) {
	rec := httptest.NewRecorder()
	NewHandler("starter-app", "test").ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/missing", nil))

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNotFound)
	}
}

func TestWrongMethodIsRejected(t *testing.T) {
	rec := httptest.NewRecorder()
	NewHandler("starter-app", "test").ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/healthz", nil))

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusMethodNotAllowed)
	}
}
