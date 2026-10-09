package agent

import (
	"context"
	"encoding/json"
	"github.com/KarakuriAgent/clawdroid/pkg/bus"
	"github.com/google/uuid"
)

func newTimingID() string { return uuid.New().String() }

// Only categories and bounded numeric timing travel to the diagnostic collector.
// This path remains observable after Stop, but cannot perform or display actions.
func (al *AgentLoop) emitTiming(opts processOptions, ctx context.Context, turn, phase string, call int, duration int64) {
	if opts.Channel != "websocket" {
		return
	}
	if duration < 0 {
		duration = 0
	}
	if duration > 86400000 {
		duration = 86400000
	}
	payload, _ := json.Marshal(struct {
		ID       string `json:"event_id"`
		Turn     string `json:"turn_id"`
		Phase    string `json:"phase"`
		Call     int    `json:"call"`
		Duration int64  `json:"duration_ms"`
	}{newTimingID(), turn, phase, call, duration})
	al.bus.TryPublishOutbound(bus.OutboundMessage{Channel: opts.Channel, ChatID: opts.ChatID, Type: "diagnostic", Content: string(payload), Generation: bus.Generation(ctx)})
}
