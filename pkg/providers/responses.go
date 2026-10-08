package providers

import (
 "bytes"
 "context"
 "encoding/json"
 "errors"
 "fmt"
 "io"
 "net/http"
 "strings"
 "time"
)

const DefaultOpenAIModel = "gpt-5.4-mini"

// ResponsesProvider is stateless: replay is carried by session messages rather
// than mutable provider state, so concurrent sessions cannot share reasoning.
type ResponsesProvider struct {
 apiKey string
 baseURL string
 defaultModel string
 forceDefaultModel bool
 client *http.Client
}

func NewResponsesProvider(model, apiKey, baseURL string) *ResponsesProvider {
 if model == "" { model = "openai/" + DefaultOpenAIModel }
 if baseURL == "" { baseURL = "https://api.openai.com/v1" }
 return &ResponsesProvider{apiKey: apiKey, baseURL: strings.TrimRight(baseURL, "/"), defaultModel: model, client: &http.Client{Timeout: 120*time.Second}}
}
func (p *ResponsesProvider) GetDefaultModel() string { return p.defaultModel }

type OpenAIError struct { Status int; Code string; Message string; upstreamCode string }
func (e *OpenAIError) Error() string { return e.Message }

func classifyOpenAIError(status int, body []byte) *OpenAIError {
 var envelope struct { Error struct { Code string `json:"code"`; Type string `json:"type"` } `json:"error"` }
 _ = json.Unmarshal(body, &envelope)
 code, message := "upstream", "OpenAI не смог обработать запрос. Попробуйте позже."
 switch {
 case status == 401: code, message = "authentication", "OpenAI отклонил ключ API. Проверьте ключ."
 case envelope.Error.Code == "insufficient_quota": code, message = "quota", "На аккаунте OpenAI недостаточно средств или исчерпан лимит."
 case status == 429: code, message = "quota", "Достигнут лимит запросов OpenAI. Повторите позже."
 case status == 403 || status == 404 || envelope.Error.Code == "model_not_found": code, message = "model", "Аккаунту недоступна выбранная модель OpenAI."
 case envelope.Error.Code == "context_length_exceeded": code, message = "context", "Превышен размер контекста OpenAI."
 case status == 400: code, message = "invalid_request", "OpenAI отклонил формат запроса."
 }
 return &OpenAIError{Status: status, Code: code, Message: message, upstreamCode: envelope.Error.Code}
}

func responsesContent(content string, media []string, role string) []map[string]interface{} {
 parts := []map[string]interface{}{}
 if content != "" {
  kind := "input_text"
  if role == "assistant" { kind = "output_text" }
  parts = append(parts, map[string]interface{}{"type": kind, "text": content})
 }
 for _, image := range media { parts = append(parts, map[string]interface{}{"type": "input_image", "image_url": image, "detail": "auto"}) }
 return parts
}

func responsesInput(messages []Message) []interface{} {
 input := []interface{}{}
 // Orphaned function calls from cancelled/compressed turns must not replay.
 answered := map[string]bool{}
 for _, message := range messages { if message.Role == "tool" { answered[message.ToolCallID] = true } }
 for _, message := range messages {
  if len(message.ResponsesOutput) > 0 {
   for _, raw := range message.ResponsesOutput {
    var item struct { Type string `json:"type"`; CallID string `json:"call_id"` }
    if json.Unmarshal(raw, &item) != nil { continue }
    if item.Type == "function_call" && !answered[item.CallID] { continue }
    input = append(input, raw)
   }
   continue
  }
  if message.Role == "tool" {
   var output interface{} = message.Content
   if len(message.Media)>0 { output = responsesContent(message.Content, message.Media, "user") }
   input = append(input, map[string]interface{}{"type": "function_call_output", "call_id": message.ToolCallID, "output": output})
   continue
  }
  if message.Content != "" || len(message.Media)>0 {
   var content interface{} = responsesContent(message.Content, message.Media, message.Role)
   if message.Role == "assistant" && len(message.Media) == 0 { content = message.Content }
   input = append(input, map[string]interface{}{"role": message.Role, "content": content})
  }
  for _, call := range message.ToolCalls {
   if !answered[call.ID] { continue }
   name, arguments := call.Name, "{}"
   if call.Function != nil { name, arguments = call.Function.Name, call.Function.Arguments } else if data, err := json.Marshal(call.Arguments); err == nil { arguments = string(data) }
   input = append(input, map[string]interface{}{"type": "function_call", "call_id": call.ID, "name": name, "arguments": arguments})
  }
 }
 return input
}

func (p *ResponsesProvider) Chat(ctx context.Context, messages []Message, tools []ToolDefinition, model string, options map[string]interface{}) (*LLMResponse, error) {
 response, err := p.chatOnce(ctx, messages, tools, model, options)
 var apiError *OpenAIError
 if err == nil || !errors.As(err, &apiError) || apiError.upstreamCode != "invalid_encrypted_content" { return response, err }
 clean := WithoutResponsesReplay(messages)
 response, err = p.chatOnce(ctx, clean, tools, model, options)
 if err == nil { response.ReplayReset = true }
 return response, err
}

// WithoutResponsesReplay preserves visible conversation and completed tool
// results when an account/project rotation invalidates encrypted reasoning.
func WithoutResponsesReplay(messages []Message) []Message {
 clean := append([]Message(nil), messages...)
 for i := range clean { clean[i].ResponsesOutput = nil }
 return clean
}

func (p *ResponsesProvider) chatOnce(ctx context.Context, messages []Message, tools []ToolDefinition, model string, options map[string]interface{}) (*LLMResponse, error) {
 if ctx.Err()!=nil { return nil, ctx.Err() }
 if p.apiKey == "" { return nil, &OpenAIError{Status:401, Code:"authentication", Message:"Укажите ключ API OpenAI."} }
 if model == "" || p.forceDefaultModel { model = p.defaultModel }
 model = strings.TrimPrefix(model, "openai/")
 request := map[string]interface{}{"model": model, "input": responsesInput(messages), "store": false, "include": []string{"reasoning.encrypted_content"}, "reasoning": map[string]string{"effort":"low"}}
 if limit, ok := options["max_tokens"]; ok { request["max_output_tokens"] = limit }
 if effort, ok := options["reasoning_effort"].(string); ok { request["reasoning"] = map[string]string{"effort": effort} }
 definitions := []map[string]interface{}{}
 for _, tool := range tools { definitions = append(definitions, map[string]interface{}{"type":"function", "name":tool.Function.Name, "description":tool.Function.Description, "parameters":tool.Function.Parameters, "strict":false}) }
 if len(definitions)>0 { request["tools"] = definitions; request["parallel_tool_calls"] = false }
 payload, err := json.Marshal(request)
 if err != nil { return nil, fmt.Errorf("Не удалось подготовить запрос OpenAI") }
 req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.baseURL+"/responses", bytes.NewReader(payload))
 if err != nil { return nil, fmt.Errorf("Некорректный адрес OpenAI") }
 req.Header.Set("Authorization", "Bearer "+p.apiKey)
 req.Header.Set("Content-Type", "application/json")
 response, err := p.client.Do(req)
 if err != nil {
  if ctx.Err()!=nil { return nil, ctx.Err() }
  return nil, &OpenAIError{Status:502,Code:"network",Message:"Не удалось подключиться к OpenAI. Проверьте интернет."}
 }
 defer response.Body.Close()
 data, err := io.ReadAll(io.LimitReader(response.Body, 32<<20))
 if err != nil { return nil, &OpenAIError{Status:502,Code:"network",Message:"Соединение с OpenAI прервано."} }
 if response.StatusCode<200 || response.StatusCode>=300 { return nil, classifyOpenAIError(response.StatusCode,data) }
 var envelope struct {
  Status string `json:"status"`
  Output []json.RawMessage `json:"output"`
  Error json.RawMessage `json:"error"`
  Usage struct { Input int `json:"input_tokens"`; Output int `json:"output_tokens"`; Total int `json:"total_tokens"` } `json:"usage"`
 }
 if json.Unmarshal(data,&envelope)!=nil { return nil, fmt.Errorf("OpenAI вернул некорректный ответ") }
 if envelope.Status == "failed" { return nil, classifyOpenAIError(502,data) }
 if envelope.Status != "completed" && envelope.Status != "incomplete" { return nil, fmt.Errorf("OpenAI не завершил обработку запроса") }
 result := &LLMResponse{FinishReason:"stop", ResponsesOutput:envelope.Output, Usage:&UsageInfo{PromptTokens:envelope.Usage.Input, CompletionTokens:envelope.Usage.Output, TotalTokens:envelope.Usage.Total}}
 for _, raw := range envelope.Output {
  var item struct {
   Type string `json:"type"`; CallID string `json:"call_id"`; Name string `json:"name"`; Arguments string `json:"arguments"`
   Content []struct { Type string `json:"type"`; Text string `json:"text"`; Refusal string `json:"refusal"` } `json:"content"`
  }
  if err := json.Unmarshal(raw,&item); err!=nil { return nil, fmt.Errorf("OpenAI вернул некорректный элемент ответа") }
  switch item.Type {
  case "message": for _, part := range item.Content { if part.Type=="output_text" { result.Content += part.Text } else if part.Type=="refusal" { result.Content += part.Refusal } }
  case "function_call":
   arguments := map[string]interface{}{}
   if json.Unmarshal([]byte(item.Arguments),&arguments)!=nil || item.CallID=="" || item.Name=="" { return nil, fmt.Errorf("OpenAI вернул некорректные аргументы инструмента") }
   result.ToolCalls = append(result.ToolCalls,ToolCall{ID:item.CallID, Type:"function", Name:item.Name, Arguments:arguments})
  }
 }
 if len(result.ToolCalls)>0 { result.FinishReason="tool_calls" }
 if envelope.Status=="incomplete" { result.FinishReason="length" }
 return result,nil
}
