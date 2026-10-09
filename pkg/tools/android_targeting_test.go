package tools

import (
 "math"
 "strings"
 "testing"
 "github.com/KarakuriAgent/clawdroid/pkg/config"
)

const targetingObservation = "71551762-4000-4000-8000-123456789abc"

func TestObservedTargetValidation(t *testing.T) {
 tool := NewAndroidTool(config.AndroidToolsConfig{})
 tests := []struct { name, action string; args map[string]interface{}; valid bool }{
  {"node", "tap", map[string]interface{}{"node_id":"0.2.1"}, true},
  {"coordinates", "tap", map[string]interface{}{"x":0.0,"y":20.0}, true},
  {"text target", "text", map[string]interface{}{"node_id":"0.2", "text":"Привет"}, true},
  {"focused text", "text", map[string]interface{}{"text":"Привет"}, true},
  {"swipe", "swipe", map[string]interface{}{"x":0,"y":1,"x2":2,"y2":3}, true},
  {"conflict", "tap", map[string]interface{}{"node_id":"0.1","x":1,"y":2}, false},
  {"partial", "tap", map[string]interface{}{"x":1}, false},
  {"negative", "tap", map[string]interface{}{"x":-1,"y":2}, false},
  {"nan", "tap", map[string]interface{}{"x":math.NaN(),"y":2}, false},
  {"infinity", "swipe", map[string]interface{}{"x":1,"y":2,"x2":math.Inf(1),"y2":3}, false},
  {"node injection", "tap", map[string]interface{}{"node_id":"0.1\n"}, false},
  {"wide path", "tap", map[string]interface{}{"node_id":"0.10000"}, false},
  {"deep path", "tap", map[string]interface{}{"node_id":"0"+strings.Repeat(".1",50)}, false},
  {"text coords", "text", map[string]interface{}{"text":"hello","x":2}, false},
  {"swipe node", "swipe", map[string]interface{}{"node_id":"0"}, false},
  {"missing text", "text", map[string]interface{}{"node_id":"0"}, false},
  {"invalid duration", "swipe", map[string]interface{}{"x":1,"y":2,"x2":3,"y2":4,"duration_ms":math.NaN()}, false},
 }
 for _, test := range tests { t.Run(test.name,func(t *testing.T) {
  test.args["observation_id"] = targetingObservation
  params, err := tool.validateAndBuildParams(test.action,test.args)
  if (err==nil)!=test.valid { t.Fatalf("valid=%v error=%v",test.valid,err) }
  if test.valid {
   if params["observation_id"]!=targetingObservation { t.Fatal("observation not forwarded") }
   if node, ok:=test.args["node_id"]; ok && params["node_id"]!=node { t.Fatal("node target not forwarded") }
  }
 }) }
}

func TestObservationSyntaxAndDeviceFreshnessBoundary(t *testing.T) {
 tool:=NewAndroidTool(config.AndroidToolsConfig{})
 for _, observation:=range []interface{}{nil,"","stale-token","71551762-4000-4000-8000-123456789abc\n",42} {
  if _,err:=tool.validateAndBuildParams("tap",map[string]interface{}{"observation_id":observation,"node_id":"0"});err==nil { t.Fatalf("accepted malformed observation %v",observation) }
 }
 // Freshness is verified by the Android observation store, not the transport.
 if _,err:=tool.validateAndBuildParams("tap",map[string]interface{}{"observation_id":targetingObservation,"node_id":"0"});err!=nil { t.Fatal(err) }
}
