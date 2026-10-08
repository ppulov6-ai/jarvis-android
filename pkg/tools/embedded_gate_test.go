package tools

import (
 "context"
 "os"
 "path/filepath"
 "testing"
)

func TestEmbeddedRegistryBlocksNativeGateBypasses(t *testing.T) {
 t.Setenv("CLAWDROID_ANDROID_SECURE_SECRETS", "true")
 home := t.TempDir()
 t.Setenv("HOME", home)
 registry := NewToolRegistry()
 registry.Register(NewExecTool(home, false))
 registry.Register(NewMessageTool())
 registry.Register(NewWriteFileTool(home, false))
 for _, name := range []string{"exec", "message", "write_file", "future_mutation"} {
  if _, ok := registry.Get(name); ok { t.Fatalf("blocked tool %s registered", name) }
  result := registry.Execute(context.Background(), name, nil)
  if !result.IsError { t.Fatalf("blocked tool %s executed", name) }
 }
 // Defense in depth against a future registration path bypassing Register.
 registry.tools["exec"] = NewExecTool(home, false)
 if !registry.Execute(context.Background(), "exec", map[string]interface{}{"command": "echo forbidden"}).IsError { t.Fatal("manually inserted exec executed") }
 for _, definition := range registry.ToProviderDefs() { if definition.Function.Name == "exec" { t.Fatal("blocked tool advertised to model") } }
}

func TestEmbeddedFileToolsArePinnedOutsideVault(t *testing.T) {
 t.Setenv("CLAWDROID_ANDROID_SECURE_SECRETS", "true")
 home := t.TempDir()
 t.Setenv("HOME", home)
 workspace := filepath.Join(home, ".clawdroid", "workspace")
 if err := os.MkdirAll(workspace, 0700); err != nil { t.Fatal(err) }
 if err := os.WriteFile(filepath.Join(home, "vault.xml"), []byte("secret"), 0600); err != nil { t.Fatal(err) }
 if err := os.WriteFile(filepath.Join(workspace, "note.txt"), []byte("working note"), 0600); err != nil { t.Fatal(err) }
 registry := NewToolRegistry()
 registry.Register(NewReadFileTool(home, false))
 registry.Register(NewListDirTool(home, false))
 for _, name := range []string{"read_file", "list_dir"} {
  if !registry.Execute(context.Background(), name, map[string]interface{}{"path": filepath.Join(home, "vault.xml")}).IsError { t.Fatalf("%s accessed vault", name) }
 }
 if registry.Execute(context.Background(), "read_file", map[string]interface{}{"path": "note.txt"}).IsError { t.Fatal("working file blocked") }
 if err := os.Symlink(home, filepath.Join(workspace, "outside")); err != nil { t.Fatal(err) }
 if !registry.Execute(context.Background(), "read_file", map[string]interface{}{"path": "outside/vault.xml"}).IsError { t.Fatal("symlink leaked vault") }
}

func TestEmbeddedWorkspaceRootSymlinkRejected(t *testing.T) {
 t.Setenv("CLAWDROID_ANDROID_SECURE_SECRETS", "true")
 home := t.TempDir()
 t.Setenv("HOME", home)
 base := filepath.Join(home, ".clawdroid")
 if err := os.MkdirAll(base, 0700); err != nil { t.Fatal(err) }
 if err := os.Symlink(home, filepath.Join(base, "workspace")); err != nil { t.Fatal(err) }
 registry := NewToolRegistry()
 registry.Register(NewReadFileTool(home, false))
 if _, ok := registry.Get("read_file"); ok { t.Fatal("vault symlink accepted as workspace") }
}
