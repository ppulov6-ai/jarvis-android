package channels

import (
 "context"
 "net/http"
 "net/http/httptest"
 "strings"
 "testing"
 "time"
 "github.com/KarakuriAgent/clawdroid/pkg/bus"
 "github.com/KarakuriAgent/clawdroid/pkg/config"
 "github.com/gorilla/websocket"
)

func TestStoppedWebSocketSendsDiagnosticsButNeverStaleActions(t *testing.T) {
 t.Setenv("CLAWDROID_ANDROID_SECURE_SECRETS", "")
 mb:=bus.NewMessageBus()
 channel,err:=NewWebSocketChannel(config.WebSocketConfig{},mb,"");if err!=nil {t.Fatal(err)}
 accepted:=make(chan *websocket.Conn,1)
 server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  conn,err:=channel.upgrader.Upgrade(w,r,nil);if err!=nil {return};accepted<-conn
 }))
 defer server.Close()
 client,_,err:=websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL,"http"),nil);if err!=nil {t.Fatal(err)};defer client.Close()
 serverConn:=<-accepted;defer serverConn.Close()
 channel.clients[serverConn]="session";channel.chatConns["session"]=serverConn;channel.setRunning(true)
 mb.SetGeneration("websocket:session",1);mb.SetGeneration("websocket:session",2);mb.CancelSession("websocket:session",2)
 for _, kind:=range []string{"tool_request","message","diagnostic"} {
  if err:=channel.Send(context.Background(),bus.OutboundMessage{Channel:"websocket",ChatID:"session",Generation:1,Type:kind,Content:"timing"});err!=nil {t.Fatal(err)}
 }
 _=client.SetReadDeadline(time.Now().Add(time.Second))
 var got wsOutgoing;if err:=client.ReadJSON(&got);err!=nil {t.Fatal(err)}
 if got.Type!="diagnostic" || got.Generation!=1 {t.Fatalf("stale action escaped: %+v",got)}
}
