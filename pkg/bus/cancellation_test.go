package bus

import (
	"context"
	"testing"
	"time"
)

func TestStopInvalidatesQueuedAndLateMessages(t *testing.T) {
	mb := NewMessageBus()
	session := "websocket:session"
	if !mb.SetGeneration(session, 1) {
		t.Fatal("first generation rejected")
	}
	oldContext := WithGeneration(context.Background(), 1)
	cancelled := make(chan int64, 1)
	mb.SetCancelHandler(func(gotSession string, generation int64) {
		if gotSession != session {
			t.Errorf("wrong cancellation session: %s", gotSession)
		}
		cancelled <- generation
	})
	if !mb.SetGeneration(session, 2) {
		t.Fatal("Stop generation rejected")
	}
	mb.CancelSession(session, 2)
	select {
	case generation := <-cancelled:
		if generation != 2 {
			t.Fatal("wrong cancellation generation")
		}
	default:
		t.Fatal("cancellation was queued instead of delivered immediately")
	}
	if mb.IsCurrent(session, 0) {
		t.Fatal("legacy request accepted after Stop")
	}
	if mb.IsCurrent(session, 1) {
		t.Fatal("stopped request remains current")
	}
	mb.PublishOutboundContext(oldContext, OutboundMessage{Channel: "websocket", ChatID: "session", Type: "tool_request"})
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	if _, ok := mb.SubscribeOutbound(ctx); ok {
		t.Fatal("stale tool request was published")
	}
	if mb.SetGeneration(session, 1) || mb.SetGeneration(session, 2) {
		t.Fatal("replayed generation accepted")
	}
	if !mb.SetGeneration(session, 3) {
		t.Fatal("new request after Stop rejected")
	}
	mb.PublishOutboundContext(WithGeneration(context.Background(), 3), OutboundMessage{Channel: "websocket", ChatID: "session", Content: "new response"})
	message, ok := mb.SubscribeOutbound(context.Background())
	if !ok || message.Generation != 3 {
		t.Fatal("new request lost generation")
	}
}

func TestCancelledContextCannotPublish(t *testing.T) {
	mb := NewMessageBus()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	mb.PublishOutboundContext(ctx, OutboundMessage{Content: "late response"})
	if len(mb.outbound) != 0 {
		t.Fatal("cancelled request published response")
	}
}

func TestDisconnectInvalidatesAndReconnectCanContinue(t *testing.T) {
	mb := NewMessageBus()
	session := "websocket:stable-client"
	mb.SetGeneration(session, 7)
	mb.InvalidateSession(session)
	if mb.IsCurrent(session, 7) || mb.IsCurrent(session, 0) {
		t.Fatal("disconnected request is current")
	}
	if !mb.SetGeneration(session, 8) || !mb.IsCurrent(session, 8) {
		t.Fatal("reconnected client cannot continue")
	}
}
