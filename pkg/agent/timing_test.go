package agent

import (
	"context"
	"encoding/json"
	"github.com/KarakuriAgent/clawdroid/pkg/bus"
	"github.com/KarakuriAgent/clawdroid/pkg/config"
	"github.com/KarakuriAgent/clawdroid/pkg/providers"
	"sync/atomic"
	"testing"
	"time"
)

func TestProviderTimingMatchesCallAndObservesCancellation(t *testing.T) {
	for _, cancelled := range []bool{false, true} {
		t.Run(map[bool]string{false: "success", true: "cancelled"}[cancelled], func(t *testing.T) {
			cfg := config.DefaultConfig()
			cfg.Agents.Defaults.Workspace = t.TempDir()
			cfg.Agents.Defaults.DataDir = t.TempDir()
			mb := bus.NewMessageBus()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			var provider providers.LLMProvider = &simpleMockProvider{response: "answer"}
			if cancelled {
				provider = &cancelDuringChatProvider{cancelFn: cancel}
			}
			al := NewAgentLoop(cfg, mb, provider)
			var status atomic.Value
			_, _, _ = al.runLLMIteration(ctx, []providers.Message{{Role: "system", Content: "private prompt"}}, processOptions{Channel: "websocket", ChatID: "test", SessionKey: "test"}, &status)
			phases := []string{"turn_started", "llm_started", "llm_success", "turn_finished"}
			if cancelled {
				phases[2] = "llm_cancelled"
				phases[3] = "turn_cancelled"
			}
			turn := ""
			call := float64(0)
			for i, phase := range phases {
				readCtx, stop := context.WithTimeout(context.Background(), time.Second)
				msg, ok := mb.SubscribeOutbound(readCtx)
				stop()
				if !ok {
					t.Fatalf("missing %s", phase)
				}
				var event map[string]interface{}
				if json.Unmarshal([]byte(msg.Content), &event) != nil || msg.Type != "diagnostic" || event["phase"] != phase {
					t.Fatalf("invalid event: %+v", event)
				}
				if len(event) != 5 {
					t.Fatal("unexpected fields may expose payload")
				}
				if i == 0 {
					turn = event["turn_id"].(string)
				}
				if event["turn_id"] != turn {
					t.Fatal("turn mismatch")
				}
				if i == 1 {
					call = event["call"].(float64)
				}
				if i == 2 && event["call"] != call {
					t.Fatal("provider call mismatch")
				}
				if event["duration_ms"].(float64) < 0 {
					t.Fatal("negative duration")
				}
			}
		})
	}
}
