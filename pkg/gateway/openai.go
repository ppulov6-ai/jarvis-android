package gateway

import (
 "context"
 "encoding/json"
 "errors"
 "net/http"
 "os"
 "strings"
 "time"

 "github.com/KarakuriAgent/clawdroid/pkg/providers"
)

// handleValidateOpenAI makes exactly one user-requested Responses call. Keys
// stay in request memory; validation never saves config or returns raw errors.
func (s *Server) handleValidateOpenAI(w http.ResponseWriter, r *http.Request) {
 var input struct { APIKey string `json:"api_key"` }
 r.Body = http.MaxBytesReader(w,r.Body,8192)
 decoder := json.NewDecoder(r.Body)
 decoder.DisallowUnknownFields()
 if decoder.Decode(&input)!=nil || strings.TrimSpace(input.APIKey)=="" {
  writeJSON(w,http.StatusBadRequest,map[string]string{"error_code":"invalid_request","error":"Введите ключ API OpenAI."})
  return
 }
 key := strings.TrimSpace(input.APIKey)
 ctx,cancel := context.WithTimeout(r.Context(),30*time.Second)
 defer cancel()
 var provider providers.LLMProvider = providers.NewResponsesProvider("openai/"+providers.DefaultOpenAIModel,key,"https://api.openai.com/v1")
 if s.openAIProviderFactory != nil { provider = s.openAIProviderFactory(key) }
 _,err := provider.Chat(ctx,[]providers.Message{{Role:"user",Content:"Ответь только: OK"}},nil,"",map[string]interface{}{"max_tokens":16,"reasoning_effort":"low"})
 if err!=nil {
  status,code,message := http.StatusBadGateway,"upstream","Не удалось проверить подключение OpenAI."
  var apiError *providers.OpenAIError
  if errors.As(err,&apiError) { status,code,message=apiError.Status,apiError.Code,apiError.Message }
  if errors.Is(err,context.DeadlineExceeded) || errors.Is(err,context.Canceled) { status,code,message=http.StatusGatewayTimeout,"network","Проверка OpenAI прервана. Повторите подключение." }
  writeJSON(w,status,map[string]string{"error_code":code,"error":message})
  return
 }
 _,statErr := os.Stat(s.configPath)
 writeJSON(w,http.StatusOK,map[string]interface{}{"status":"ok","valid":true,"model":providers.DefaultOpenAIModel,"configured":statErr==nil})
}
