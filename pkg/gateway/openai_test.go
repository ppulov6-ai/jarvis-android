package gateway

import (
 "context"
 "encoding/json"
 "os"
 "path/filepath"
 "time"
 "github.com/KarakuriAgent/clawdroid/pkg/providers"
 "net/http"
 "net/http/httptest"
 "strings"
 "testing"

 "github.com/KarakuriAgent/clawdroid/pkg/config"
)

func TestOpenAIValidationIsAuthenticatedAndRejectsEmptyKey(t *testing.T) {
 cfg:=config.DefaultConfig()
 cfg.Gateway.APIKey="gateway-only"
 server:=NewServer(cfg,"",nil)
 handler:=server.authMiddleware(server.handleValidateOpenAI)
 request:=httptest.NewRequest(http.MethodPost,"/api/openai/validate",strings.NewReader(`{"api_key":""}`))
 response:=httptest.NewRecorder()
 handler(response,request)
 if response.Code!=http.StatusUnauthorized {t.Fatal("validation bypassed gateway authentication")}
 request=httptest.NewRequest(http.MethodPost,"/api/openai/validate",strings.NewReader(`{"api_key":""}`))
 request.Header.Set("Authorization","Bearer gateway-only")
 response=httptest.NewRecorder()
 handler(response,request)
 if response.Code!=http.StatusBadRequest || !strings.Contains(response.Body.String(),"invalid_request") {t.Fatal("empty key validation contract missing")}
}

func TestOpenAIValidationUpstreamContracts(t *testing.T) {
 for _,test:=range []struct {name string; upstreamStatus int; body string; configured bool; expectedStatus int; code string}{
  {"first connection",200,`{"status":"completed","output":[]}`,false,200,""},
  {"existing configuration",200,`{"status":"incomplete","output":[]}`,true,200,""},
  {"bad key",401,`{"error":{"code":"invalid_api_key","message":"mock-key"}}`,false,401,"authentication"},
  {"quota",429,`{"error":{"code":"insufficient_quota","message":"mock-key"}}`,false,429,"quota"},
 }{
  t.Run(test.name,func(t *testing.T){
   upstream:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
    if r.URL.Path!="/responses" || r.Header.Get("Authorization")!="Bearer mock-key" {t.Error("validation did not use real Responses contract")}
    var body map[string]interface{}
    _=json.NewDecoder(r.Body).Decode(&body)
    if body["store"]!=false || body["model"]!=providers.DefaultOpenAIModel || body["max_output_tokens"]!=float64(16) {t.Error("validation request contract mismatch")}
    if _,ok:=body["tools"];ok {t.Error("validation invoked tools")}
    w.WriteHeader(test.upstreamStatus)
    _,_=w.Write([]byte(test.body))
   }))
   defer upstream.Close()
   path:=filepath.Join(t.TempDir(),"config.json")
   if test.configured {if err:=os.WriteFile(path,[]byte("{}"),0600);err!=nil {t.Fatal(err)}}
   cfg:=config.DefaultConfig();cfg.Gateway.APIKey="gateway-only"
   server:=NewServer(cfg,path,nil)
   server.openAIProviderFactory=func(key string)providers.LLMProvider{return providers.NewResponsesProvider("",key,upstream.URL)}
   request:=httptest.NewRequest(http.MethodPost,"/api/openai/validate",strings.NewReader(`{"api_key":"mock-key"}`))
   request.Header.Set("Authorization","Bearer gateway-only")
   response:=httptest.NewRecorder()
   server.authMiddleware(server.handleValidateOpenAI)(response,request)
   if response.Code!=test.expectedStatus {t.Fatalf("HTTP %d want %d",response.Code,test.expectedStatus)}
   var body map[string]interface{}
   _=json.Unmarshal(response.Body.Bytes(),&body)
   if test.code!="" {if body["error_code"]!=test.code || strings.Contains(response.Body.String(),"mock-key") {t.Fatal("validation error contract leaked key or lost code")}} else if body["valid"]!=true || body["configured"]!=test.configured {t.Fatal("validated configured state incorrect")}
   if _,err:=os.Stat(path); !test.configured && !os.IsNotExist(err) {t.Fatal("validation persisted configuration")}
  })
 }
}

func TestOpenAIValidationCancellation(t *testing.T) {
 started,release:=make(chan struct{}),make(chan struct{})
 upstream:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){close(started);<-release}))
 defer upstream.Close();defer close(release)
 cfg:=config.DefaultConfig();cfg.Gateway.APIKey="gateway-only"
 server:=NewServer(cfg,"",nil)
 server.openAIProviderFactory=func(key string)providers.LLMProvider{return providers.NewResponsesProvider("",key,upstream.URL)}
 ctx,cancel:=context.WithCancel(context.Background())
 request:=httptest.NewRequest(http.MethodPost,"/api/openai/validate",strings.NewReader(`{"api_key":"mock-key"}`)).WithContext(ctx)
 request.Header.Set("Authorization","Bearer gateway-only")
 response:=httptest.NewRecorder()
 done:=make(chan struct{})
 go func(){server.authMiddleware(server.handleValidateOpenAI)(response,request);close(done)}()
 select{case <-started:case <-time.After(time.Second):t.Fatal("validation not started")}
 cancel()
 select{case <-done:case <-time.After(time.Second):t.Fatal("validation ignored cancellation")}
 if response.Code!=http.StatusGatewayTimeout || !strings.Contains(response.Body.String(),"network") {t.Fatal("cancel contract missing")}
}
