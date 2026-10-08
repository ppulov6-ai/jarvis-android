package providers

import (
	"github.com/KarakuriAgent/clawdroid/pkg/config"
	"os"
	"strings"
)

// CreateProvider is the single entry point for constructing an LLMProvider.
// When replacing the underlying LLM library, modify only this function
// and the adapter it delegates to (currently AnyLLMAdapter).
func CreateProvider(cfg *config.Config) (LLMProvider, error) {
	if strings.HasPrefix(cfg.LLM.Model, "openai/") || strings.HasPrefix(cfg.LLM.Model, "gpt-") || cfg.LLM.Model == "" || os.Getenv("CLAWDROID_ANDROID_SECURE_SECRETS") == "true" {
		baseURL := cfg.LLM.BaseURL
		model := cfg.LLM.Model
		secure := os.Getenv("CLAWDROID_ANDROID_SECURE_SECRETS") == "true"
		if secure {
			baseURL = "https://api.openai.com/v1"
			model = "openai/" + DefaultOpenAIModel
		}
		provider := NewResponsesProvider(model, cfg.LLM.APIKey, baseURL)
		provider.forceDefaultModel = secure
		return provider, nil
	}
	return NewAnyLLMAdapter(cfg.LLM.Model, cfg.LLM.APIKey, cfg.LLM.BaseURL)
}
