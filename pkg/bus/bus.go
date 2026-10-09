package bus

import (
	"context"
	"sync"
	"time"
)

type MessageBus struct {
	inbound         chan InboundMessage
	outbound        chan OutboundMessage
	handlers        map[string]MessageHandler
	generations     map[string]int64
	invalidSessions map[string]bool
	cancelHandler   func(string, int64)
	closed          bool
	mu              sync.RWMutex
}

func NewMessageBus() *MessageBus {
	return &MessageBus{
		inbound:         make(chan InboundMessage, 100),
		outbound:        make(chan OutboundMessage, 100),
		handlers:        make(map[string]MessageHandler),
		generations:     make(map[string]int64),
		invalidSessions: make(map[string]bool),
	}
}

func (mb *MessageBus) PublishInbound(msg InboundMessage) {
	for {
		mb.mu.RLock()
		if mb.closed {
			mb.mu.RUnlock()
			return
		}
		select {
		case mb.inbound <- msg:
			mb.mu.RUnlock()
			return
		default:
			mb.mu.RUnlock()
		}
		// Never hold the bus lock while a full queue waits; Stop needs this lock.
		time.Sleep(time.Millisecond)
	}
}

func (mb *MessageBus) ConsumeInbound(ctx context.Context) (InboundMessage, bool) {
	select {
	case msg := <-mb.inbound:
		return msg, true
	case <-ctx.Done():
		return InboundMessage{}, false
	}
}

func (mb *MessageBus) PublishOutbound(msg OutboundMessage) {
	for {
		mb.mu.RLock()
		if mb.closed {
			mb.mu.RUnlock()
			return
		}
		select {
		case mb.outbound <- msg:
			mb.mu.RUnlock()
			return
		default:
			mb.mu.RUnlock()
		}
		// Never hold the bus lock while a full queue waits; Stop needs this lock.
		time.Sleep(time.Millisecond)
	}
}

func (mb *MessageBus) SubscribeOutbound(ctx context.Context) (OutboundMessage, bool) {
	select {
	case msg := <-mb.outbound:
		return msg, true
	case <-ctx.Done():
		return OutboundMessage{}, false
	}
}

func (mb *MessageBus) RegisterHandler(channel string, handler MessageHandler) {
	mb.mu.Lock()
	defer mb.mu.Unlock()
	mb.handlers[channel] = handler
}

func (mb *MessageBus) GetHandler(channel string) (MessageHandler, bool) {
	mb.mu.RLock()
	defer mb.mu.RUnlock()
	handler, ok := mb.handlers[channel]
	return handler, ok
}

func (mb *MessageBus) Close() {
	mb.mu.Lock()
	defer mb.mu.Unlock()
	if mb.closed {
		return
	}
	mb.closed = true
	close(mb.inbound)
	close(mb.outbound)
}

// SetCancelHandler installs the agent cancellation hook. Cancellation bypasses
// the inbound queue so Stop remains responsive while another message waits.
func (mb *MessageBus) SetCancelHandler(handler func(string, int64)) {
	mb.mu.Lock()
	defer mb.mu.Unlock()
	mb.cancelHandler = handler
}

// SetGeneration accepts only strictly newer request generations for a session.
func (mb *MessageBus) SetGeneration(session string, generation int64) bool {
	mb.mu.Lock()
	defer mb.mu.Unlock()
	if generation <= 0 || generation <= mb.generations[session] {
		return false
	}
	mb.generations[session] = generation
	delete(mb.invalidSessions, session)
	return true
}

func (mb *MessageBus) IsCurrent(session string, generation int64) bool {
	mb.mu.RLock()
	defer mb.mu.RUnlock()
	return mb.generations[session] == generation && !mb.invalidSessions[session]
}

func (mb *MessageBus) CancelSession(session string, generation int64) {
	mb.mu.RLock()
	handler := mb.cancelHandler
	mb.mu.RUnlock()
	if handler != nil {
		handler(session, generation)
	}
}

type generationKey struct{}

func WithGeneration(ctx context.Context, generation int64) context.Context {
	return context.WithValue(ctx, generationKey{}, generation)
}
func Generation(ctx context.Context) int64 {
	value, _ := ctx.Value(generationKey{}).(int64)
	return value
}
func (mb *MessageBus) PublishOutboundContext(ctx context.Context, msg OutboundMessage) {
	if ctx.Err() != nil {
		return
	}
	msg.Generation = Generation(ctx)
	if !mb.IsCurrent(msg.Channel+":"+msg.ChatID, msg.Generation) {
		return
	}
	mb.PublishOutbound(msg)
}

// InvalidateSession also cancels queued requests when a client disconnects.
func (mb *MessageBus) InvalidateSession(session string) {
	mb.mu.Lock()
	generation := mb.generations[session]
	mb.invalidSessions[session] = true
	handler := mb.cancelHandler
	mb.mu.Unlock()
	if handler != nil {
		handler(session, generation)
	}
}

// TryPublishOutbound drops optional telemetry rather than delaying user actions.
func (mb *MessageBus) TryPublishOutbound(msg OutboundMessage) {
	mb.mu.RLock()
	defer mb.mu.RUnlock()
	if mb.closed {
		return
	}
	select {
	case mb.outbound <- msg:
	default:
	}
}
