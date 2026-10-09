package agent

import (
	"strings"
	"testing"
)

func TestAndroidSessionUsesPermanentGuidance(t *testing.T) {
	// An empty workspace models an upgrade without a new AGENT.md bootstrap.
	builder := NewContextBuilder(t.TempDir(), t.TempDir())
	for _, chatID := range []string{"", "overlay-session", "chat-session"} {
		messages := builder.BuildMessages(nil, "", "Напиши сообщение", nil, "websocket", chatID, "text", nil)
		if len(messages) == 0 || messages[0].Role != "system" {
			t.Fatal("effective messages do not start with a system prompt")
		}
		for _, required := range []string{
			"Respond in Russian by default",
			"English tool errors",
			"user explicitly requests another language",
			"fresh get_ui_tree and its observation_id",
			"observed node_id",
			"actual editable message or search field",
			"verify the actual effect",
			"stop guessing",
			"Honor approval already granted",
			"Keep progress brief",
			"exact displayed interval label",
			"absent from the accessibility tree",
		} {
			if !strings.Contains(messages[0].Content, required) {
				t.Errorf("chat %q missing permanent rule %q", chatID, required)
			}
		}
	}
}

func TestAndroidSessionGuidanceDoesNotChangeOtherChannels(t *testing.T) {
	builder := NewContextBuilder(t.TempDir(), t.TempDir())
	for _, channel := range []string{"telegram", "discord", "", "cli"} {
		messages := builder.BuildMessages(nil, "", "Hello", nil, channel, "session", "text", nil)
		if strings.Contains(messages[0].Content, "## Jarvis Android Session") {
			t.Errorf("Android-only guidance leaked into channel %q", channel)
		}
	}
}
