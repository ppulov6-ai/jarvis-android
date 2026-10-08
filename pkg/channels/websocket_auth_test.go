package channels

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/KarakuriAgent/clawdroid/pkg/bus"
	"github.com/KarakuriAgent/clawdroid/pkg/config"
)

func TestEmbeddedWebSocketRequiresGatewayBearer(t *testing.T) {
	t.Setenv("CLAWDROID_ANDROID_SECURE_SECRETS", "true")
	t.Setenv("CLAWDROID_GATEWAY_API_KEY", "gateway-secret")
	channel, err := NewWebSocketChannel(config.WebSocketConfig{APIKey: "unrelated-channel-key"}, bus.NewMessageBus(), "")
	if err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name, header, query string
		allowed             bool
	}{
		{"missing", "", "", false},
		{"wrong", "Bearer wrong", "", false},
		{"channel key", "Bearer unrelated-channel-key", "", false},
		{"query prohibited", "", "?api_key=gateway-secret", false},
		{"bearer", "Bearer gateway-secret", "", true},
	} {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodGet, "/ws"+test.query, nil)
			request.Header.Set("Authorization", test.header)
			if got := channel.authorizedRequest(request); got != test.allowed {
				t.Fatalf("authentication got %v, want %v", got, test.allowed)
			}
			if !test.allowed {
				response := httptest.NewRecorder()
				channel.handleWS(response, request)
				if response.Code != http.StatusUnauthorized {
					t.Fatal("unauthorized socket reached upgrade")
				}
			}
		})
	}
}

func TestEmbeddedWebSocketFailsClosedWithoutKey(t *testing.T) {
	t.Setenv("CLAWDROID_ANDROID_SECURE_SECRETS", "true")
	t.Setenv("CLAWDROID_GATEWAY_API_KEY", "")
	if _, err := NewWebSocketChannel(config.WebSocketConfig{APIKey: "legacy"}, bus.NewMessageBus(), ""); err == nil {
		t.Fatal("embedded websocket accepted missing gateway key")
	}
}

func TestStandaloneWebSocketSupportsLegacyQuery(t *testing.T) {
	t.Setenv("CLAWDROID_ANDROID_SECURE_SECRETS", "")
	channel, err := NewWebSocketChannel(config.WebSocketConfig{APIKey: "legacy"}, bus.NewMessageBus(), "")
	if err != nil {
		t.Fatal(err)
	}
	if !channel.authorizedRequest(httptest.NewRequest(http.MethodGet, "/ws?api_key=legacy", nil)) {
		t.Fatal("standalone query authentication rejected")
	}
}
