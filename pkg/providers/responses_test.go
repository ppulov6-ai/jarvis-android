package providers

import (
 "context"
 "encoding/json"
 "errors"
 "net/http"
 "net/http/httptest"
 "strings"
 "testing"
 "time"
 "github.com/KarakuriAgent/clawdroid/pkg/config"
)

func TestResponsesToolsImagesAndReasoningContinuation(t *testing.T) {
 calls := 0
 server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
  calls++
  if r.URL.Path!="/v1/responses" || r.Method!=http.MethodPost { t.Errorf("wrong native API route: %s %s",r.Method,r.URL.Path) }
  if r.Header.Get("Authorization")!="Bearer mock-key" { t.Error("missing authorization") }
  var request map[string]interface{}
  if json.NewDecoder(r.Body).Decode(&request)!=nil { t.Error("invalid request"); return }
  if request["store"]!=false || request["model"]!=DefaultOpenAIModel { t.Error("stateless model contract missing") }
  if _,found:=request["temperature"]; found { t.Error("unsupported reasoning temperature sent") }
  input,_:=request["input"].([]interface{})
  encoded,_:=json.Marshal(input)
  if calls==1 {
   definitions:=request["tools"].([]interface{})
   function:=definitions[0].(map[string]interface{})
   if function["strict"]!=false || function["name"]!="android" { t.Error("optional tool arguments forced strict") }
   if !strings.Contains(string(encoded),"input_image") { t.Error("user image missing") }
   w.Header().Set("Content-Type","application/json")
   _,_=w.Write([]byte(`{"status":"completed","output":[{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"encrypted-state"},{"type":"function_call","id":"fc_1","status":"completed","call_id":"call_1","name":"android","arguments":"{\"action\":\"screenshot\"}"}],"usage":{"input_tokens":10,"output_tokens":12,"total_tokens":22}}`))
  } else {
   text:=string(encoded)
   for _,wanted:=range []string{"encrypted-state","function_call_output","call_1","input_image","image/jpeg;base64,tool-image"} { if !strings.Contains(text,wanted) { t.Errorf("continuation lost %s",wanted) } }
   _,_=w.Write([]byte(`{"status":"completed","output":[{"type":"message","id":"msg_1","status":"completed","role":"assistant","content":[{"type":"output_text","text":"Вижу экран","annotations":[]}]}],"usage":{"input_tokens":20,"output_tokens":5,"total_tokens":25}}`))
  }
 }))
 defer server.Close()
 provider:=NewResponsesProvider("openai/"+DefaultOpenAIModel,"mock-key",server.URL+"/v1")
 history:=[]Message{{Role:"system",Content:"Работай на русском"},{Role:"user",Content:"Посмотри экран",Media:[]string{"data:image/jpeg;base64,user-image"}}}
 tools:=[]ToolDefinition{{Type:"function",Function:ToolFunctionDefinition{Name:"android",Parameters:map[string]interface{}{"type":"object","properties":map[string]interface{}{"action":map[string]interface{}{"type":"string"}},"required":[]string{"action"}}}}}
 first,err:=provider.Chat(context.Background(),history,tools,"",map[string]interface{}{"max_tokens":1024,"temperature":1.0})
 if err!=nil { t.Fatal(err) }
 if len(first.ToolCalls)!=1 || first.ToolCalls[0].ID!="call_1" || first.Usage.TotalTokens!=22 { t.Fatal("tool/usage mapping failed") }
 history=append(history,Message{Role:"assistant",ToolCalls:first.ToolCalls,ResponsesOutput:first.ResponsesOutput},Message{Role:"tool",ToolCallID:"call_1",Content:"Снимок экрана",Media:[]string{"data:image/jpeg;base64,tool-image"}})
 second,err:=provider.Chat(context.Background(),history,tools,"",nil)
 if err!=nil || second.Content!="Вижу экран" { t.Fatalf("continuation: %v %+v",err,second) }
}

func TestResponsesErrorRedactsUpstreamSecrets(t *testing.T) {
 server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
  w.WriteHeader(http.StatusUnauthorized)
  _,_=w.Write([]byte(`{"error":{"message":"Invalid key super-secret","code":"invalid_api_key"}}`))
 }))
 defer server.Close()
 _,err:=NewResponsesProvider("","super-secret",server.URL).Chat(context.Background(),nil,nil,"",nil)
 var apiError *OpenAIError
 if !errors.As(err,&apiError) || apiError.Code!="authentication" || strings.Contains(err.Error(),"super-secret") { t.Fatalf("unsafe/untyped error: %v",err) }
}

func TestResponsesStopCancelsHTTP(t *testing.T) {
 started:=make(chan struct{})
 release:=make(chan struct{})
 server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) { close(started); <-release }))
 defer server.Close()
 defer close(release)
 ctx,cancel:=context.WithCancel(context.Background())
 result:=make(chan error,1)
 go func(){ _,err:=NewResponsesProvider("","mock-key",server.URL).Chat(ctx,nil,nil,"",nil); result<-err }()
 select { case <-started: case <-time.After(time.Second): t.Fatal("request did not start") }
 cancel()
 select { case err:=<-result: if !errors.Is(err,context.Canceled){t.Fatalf("HTTP cancellation lost: %v",err)}; case <-time.After(time.Second): t.Fatal("Stop did not cancel HTTP") }
}

func TestResponsesInputFiltersCancelledFunctionCall(t *testing.T) {
 raw:=json.RawMessage(`{"type":"function_call","call_id":"cancelled","name":"android","arguments":"{}"}`)
 input:=responsesInput([]Message{{Role:"assistant",ResponsesOutput:[]json.RawMessage{raw}}})
 if len(input)!=0 {t.Fatal("orphaned cancelled function replayed")}
}

func TestSecureProviderPinsModelAndEndpointDuringMigration(t *testing.T) {
 t.Setenv("CLAWDROID_ANDROID_SECURE_SECRETS","true")
 cfg:=config.DefaultConfig()
 cfg.LLM.Model="anthropic/legacy-model"
 cfg.LLM.BaseURL="https://wrong.example/v1"
 cfg.LLM.APIKey="mock-key"
 provider,err:=CreateProvider(cfg)
 if err!=nil {t.Fatal(err)}
 native,ok:=provider.(*ResponsesProvider)
 if !ok || native.baseURL!="https://api.openai.com/v1" || native.defaultModel!="openai/"+DefaultOpenAIModel || !native.forceDefaultModel {t.Fatal("legacy configuration bypassed fixed OpenAI model/endpoint")}
 server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  var body struct {Model string `json:"model"`}
  _=json.NewDecoder(r.Body).Decode(&body)
  if body.Model!=DefaultOpenAIModel {t.Error("request used hidden legacy model")}
  _,_=w.Write([]byte(`{"status":"completed","output":[]}`))
 }))
 defer server.Close()
 native.baseURL=server.URL
 if _,err:=native.Chat(context.Background(),nil,nil,cfg.LLM.Model,nil);err!=nil {t.Fatal(err)}
}

func TestResponsesEncryptedReplayResetIsExactlyOneSpecificRetry(t *testing.T) {
 for _,test:=range []struct{name,code string; secondSucceeds bool; expectedCalls int; expectedReset bool}{
  {"project rotation","invalid_encrypted_content",true,2,true},
  {"rejected fallback","invalid_encrypted_content",false,2,false},
  {"unrelated format error","invalid_request_error",false,1,false},
 }{
  t.Run(test.name,func(t *testing.T){
   calls:=0
   server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
    calls++
    var body map[string]interface{}
    _=json.NewDecoder(r.Body).Decode(&body)
    encoded,_:=json.Marshal(body["input"])
    if calls==1 && !strings.Contains(string(encoded),"old-project-reasoning") {t.Error("initial reasoning missing")}
    if calls==2 {
     if strings.Contains(string(encoded),"old-project-reasoning") {t.Error("fallback retained invalid encrypted reasoning")}
     for _,value:=range []string{"visible history","function_call_output","completed-result","call_done"}{if !strings.Contains(string(encoded),value){t.Errorf("fallback lost %s",value)}}
    }
    if calls==2 && test.secondSucceeds {_,_=w.Write([]byte(`{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"OK"}]}]}`));return}
    w.WriteHeader(http.StatusBadRequest)
    _,_=w.Write([]byte(`{"error":{"code":"`+test.code+`","message":"unsafe diagnostics"}}`))
   }))
   defer server.Close()
   history:=[]Message{
    {Role:"assistant",Content:"visible history",ToolCalls:[]ToolCall{{ID:"call_done",Name:"android",Arguments:map[string]interface{}{"action":"app_info"}}},ResponsesOutput:[]json.RawMessage{json.RawMessage(`{"type":"reasoning","encrypted_content":"old-project-reasoning","summary":[]}`),json.RawMessage(`{"type":"function_call","call_id":"call_done","name":"android","arguments":"{}"}`)}},
    {Role:"tool",ToolCallID:"call_done",Content:"completed-result"},
    {Role:"user",Content:"new question"},
   }
   result,err:=NewResponsesProvider("","mock-key",server.URL).Chat(context.Background(),history,nil,"",nil)
   if calls!=test.expectedCalls {t.Fatalf("calls=%d want=%d",calls,test.expectedCalls)}
   if test.expectedReset {if err!=nil || !result.ReplayReset {t.Fatalf("successful reset missing: %v",err)}} else if err==nil {t.Fatal("rejected request reported success")}
   if len(history[0].ResponsesOutput)!=2 {t.Fatal("provider mutated caller history")}
  })
 }
}
