package tools

import (
	"strings"

	"github.com/KarakuriAgent/clawdroid/pkg/config"
)

// androidAction describes a single action available in the android tool.
type androidAction struct {
	Name     string
	Category string // "app", "ui", "intent", "alarm", "calendar", etc.
	Desc     string
	UIOnly   bool // restricted to non-main client types
	Params   []androidParam
}

// androidParam describes a parameter for an android action.
type androidParam struct {
	Name     string
	Type     string // "string", "number", "integer", "boolean", "object", "array"
	Desc     string
	Required bool
	Enum     []string
}

// allActions defines every action the android tool supports.
// Every action belongs to a category that can be toggled in the config.
var allActions = []androidAction{
	// ── App Management ──
	{Name: "search_apps", Category: "app", Desc: "Search installed apps by name or package name (requires query)", Params: []androidParam{
		{Name: "query", Type: "string", Desc: "Search query for app name or package name", Required: true},
	}},
	{Name: "app_info", Category: "app", Desc: "Get app details (requires package_name)", Params: []androidParam{
		{Name: "package_name", Type: "string", Desc: "Android package name", Required: true},
	}},
	{Name: "launch_app", Category: "app", Desc: "Launch an app (requires package_name)", Params: []androidParam{
		{Name: "package_name", Type: "string", Desc: "Android package name", Required: true},
	}},

	// ── UI Interaction (UIOnly) ──
	{Name: "screenshot", Category: "ui", Desc: "Capture a screenshot of the current screen (no params)", UIOnly: true},
	{Name: "get_ui_tree", Category: "ui", Desc: "Get a fresh accessibility UI tree returning observation_id and per-node node_id. Refresh after every action; coordinates use full-display physical pixels (optional: resource_id, index, bounds_x/bounds_y, max_depth, max_nodes)", UIOnly: true, Params: []androidParam{
		{Name: "resource_id", Type: "string", Desc: "View resource ID to start UI tree from (e.g. com.example:id/button)"},
		{Name: "index", Type: "integer", Desc: "Which match to use when resource_id has multiple hits (default 0)"},
		{Name: "bounds_x", Type: "number", Desc: "X coordinate to find the containing node (alternative to resource_id)"},
		{Name: "bounds_y", Type: "number", Desc: "Y coordinate to find the containing node (alternative to resource_id)"},
		{Name: "max_depth", Type: "integer", Desc: "Maximum traversal depth (default 15)"},
		{Name: "max_nodes", Type: "integer", Desc: "Maximum number of nodes to output (default 300)"},
	}},
	{Name: "tap", Category: "ui", Desc: "Tap an observed node: prefer node_id from fresh get_ui_tree with observation_id. Alternatively use observed x,y in full-display physical pixels. Never guess coordinates; refresh after each action.", UIOnly: true, Params: []androidParam{
        {Name: "observation_id", Type: "string", Desc: "UUID returned by fresh get_ui_tree; required for tap, swipe and text", Required: true},
        {Name: "node_id", Type: "string", Desc: "Observed node path from get_ui_tree; tap accepts either node_id OR x,y, never both"},
        {Name: "x", Type: "number", Desc: "Observed X in full-display physical pixels"},
        {Name: "y", Type: "number", Desc: "Observed Y in full-display physical pixels"},
    }},
    {Name: "swipe", Category: "ui", Desc: "Swipe using fresh observation_id and observed full-display physical pixel coordinates. Refresh get_ui_tree afterwards.", UIOnly: true, Params: []androidParam{
        {Name: "observation_id", Type: "string", Desc: "UUID returned by fresh get_ui_tree; required for tap, swipe and text", Required: true},
        {Name: "x", Type: "number", Desc: "Start X in full-display physical pixels", Required: true},
        {Name: "y", Type: "number", Desc: "Start Y in full-display physical pixels", Required: true},
        {Name: "x2", Type: "number", Desc: "End X in full-display physical pixels", Required: true},
        {Name: "y2", Type: "number", Desc: "End Y in full-display physical pixels", Required: true},
        {Name: "duration_ms", Type: "integer", Desc: "Swipe duration in milliseconds, 1 to 10000 (default 300)"},
    }},
    {Name: "text", Category: "ui", Desc: "Input text with fresh observation_id and observed editable node_id. If node_id omitted, only a safe focused editable field is used. Never guess a field. Refresh get_ui_tree afterwards.", UIOnly: true, Params: []androidParam{
        {Name: "observation_id", Type: "string", Desc: "UUID returned by fresh get_ui_tree; required for tap, swipe and text", Required: true},
        {Name: "node_id", Type: "string", Desc: "Observed editable node path; omit only for safe focused editable field"},
        {Name: "text", Type: "string", Desc: "Text to input", Required: true},
    }},
	{Name: "keyevent", Category: "ui", Desc: "Press a key (requires key: back/home/recents)", UIOnly: true, Params: []androidParam{
		{Name: "key", Type: "string", Desc: "Key to press", Required: true, Enum: []string{"back", "home", "recents"}},
	}},

	// ── Intent ──
	{Name: "broadcast", Category: "intent", Desc: "Send a broadcast intent (requires intent_action; optional intent_extras)", Params: []androidParam{
		{Name: "intent_action", Type: "string", Desc: "Intent action string", Required: true},
		{Name: "intent_extras", Type: "object", Desc: "Extra key-value pairs for broadcast"},
	}},
	{Name: "intent", Category: "intent", Desc: "Start an activity via intent (requires intent_action; optional intent_data, intent_package, intent_type, intent_extras)", Params: []androidParam{
		{Name: "intent_action", Type: "string", Desc: "Intent action string", Required: true},
		{Name: "intent_data", Type: "string", Desc: "Intent data URI"},
		{Name: "intent_package", Type: "string", Desc: "Target package for intent"},
		{Name: "intent_type", Type: "string", Desc: "MIME type for intent"},
		{Name: "intent_extras", Type: "object", Desc: "Extra key-value pairs for intent"},
	}},

	// ── Alarm ──
	{Name: "set_alarm", Category: "alarm", Desc: "Set an alarm (requires hour, minute)", Params: []androidParam{
		{Name: "hour", Type: "integer", Desc: "Hour (0-23)", Required: true},
		{Name: "minute", Type: "integer", Desc: "Minute (0-59)", Required: true},
		{Name: "message", Type: "string", Desc: "Alarm label"},
		{Name: "days", Type: "string", Desc: "Repeating days as comma-separated (e.g. MO,TU,WE,TH,FR)"},
		{Name: "skip_ui", Type: "boolean", Desc: "Skip alarm app UI (default true)"},
	}},
	{Name: "set_timer", Category: "alarm", Desc: "Set a countdown timer (requires duration_seconds)", Params: []androidParam{
		{Name: "duration_seconds", Type: "integer", Desc: "Timer length in seconds (1-86400)", Required: true},
		{Name: "message", Type: "string", Desc: "Timer label"},
		{Name: "skip_ui", Type: "boolean", Desc: "Skip timer app UI (default true)"},
	}},
	{Name: "dismiss_alarm", Category: "alarm", Desc: "Dismiss a currently ringing alarm"},
	{Name: "show_alarms", Category: "alarm", Desc: "Show the alarm list in the clock app"},

	// ── Calendar ──
	{Name: "create_event", Category: "calendar", Desc: "Create a calendar event (opens calendar app for confirmation)", Params: []androidParam{
		{Name: "title", Type: "string", Desc: "Event title", Required: true},
		{Name: "start_time", Type: "string", Desc: "Start time in ISO 8601 format (e.g. 2025-01-15T09:00:00)", Required: true},
		{Name: "end_time", Type: "string", Desc: "End time in ISO 8601 format"},
		{Name: "description", Type: "string", Desc: "Event description"},
		{Name: "location", Type: "string", Desc: "Event location"},
		{Name: "all_day", Type: "boolean", Desc: "All-day event flag"},
	}},
	{Name: "query_events", Category: "calendar", Desc: "Query calendar events in a date range", Params: []androidParam{
		{Name: "start_time", Type: "string", Desc: "Range start in ISO 8601 format", Required: true},
		{Name: "end_time", Type: "string", Desc: "Range end in ISO 8601 format", Required: true},
		{Name: "query", Type: "string", Desc: "Search keyword (optional)"},
	}},
	{Name: "update_event", Category: "calendar", Desc: "Update an existing calendar event", Params: []androidParam{
		{Name: "event_id", Type: "string", Desc: "Event ID to update", Required: true},
		{Name: "title", Type: "string", Desc: "New title"},
		{Name: "start_time", Type: "string", Desc: "New start time in ISO 8601"},
		{Name: "end_time", Type: "string", Desc: "New end time in ISO 8601"},
		{Name: "description", Type: "string", Desc: "New description"},
		{Name: "location", Type: "string", Desc: "New location"},
	}},
	{Name: "delete_event", Category: "calendar", Desc: "Delete a calendar event", Params: []androidParam{
		{Name: "event_id", Type: "string", Desc: "Event ID to delete", Required: true},
	}},
	{Name: "list_calendars", Category: "calendar", Desc: "List available calendar accounts"},
	{Name: "add_reminder", Category: "calendar", Desc: "Add a reminder to a calendar event", Params: []androidParam{
		{Name: "event_id", Type: "string", Desc: "Event ID", Required: true},
		{Name: "minutes", Type: "integer", Desc: "Minutes before event (e.g. 10, 30, 60)", Required: true},
	}},

	// ── Contacts ──
	{Name: "search_contacts", Category: "contacts", Desc: "Search contacts by name, phone number, or email", Params: []androidParam{
		{Name: "query", Type: "string", Desc: "Search query", Required: true},
	}},
	{Name: "get_contact_detail", Category: "contacts", Desc: "Get full details for a contact", Params: []androidParam{
		{Name: "contact_id", Type: "string", Desc: "Contact ID", Required: true},
	}},
	{Name: "add_contact", Category: "contacts", Desc: "Add a new contact (opens contacts app for confirmation)", Params: []androidParam{
		{Name: "name", Type: "string", Desc: "Contact name", Required: true},
		{Name: "phone", Type: "string", Desc: "Phone number"},
		{Name: "email", Type: "string", Desc: "Email address"},
	}},

	// ── Communication ──
	{Name: "dial", Category: "communication", Desc: "Open the dialer with a phone number (does not call)", Params: []androidParam{
		{Name: "phone_number", Type: "string", Desc: "Phone number to dial", Required: true},
	}},
	{Name: "compose_sms", Category: "communication", Desc: "Open SMS compose screen", Params: []androidParam{
		{Name: "phone_number", Type: "string", Desc: "Recipient phone number", Required: true},
		{Name: "message", Type: "string", Desc: "Pre-filled message body"},
	}},
	{Name: "compose_email", Category: "communication", Desc: "Open email compose screen", Params: []androidParam{
		{Name: "to", Type: "string", Desc: "Recipient email address", Required: true},
		{Name: "subject", Type: "string", Desc: "Email subject"},
		{Name: "body", Type: "string", Desc: "Email body"},
	}},

	// ── Media ──
	{Name: "media_play_pause", Category: "media", Desc: "Toggle media play/pause"},
	{Name: "media_next", Category: "media", Desc: "Skip to next track"},
	{Name: "media_previous", Category: "media", Desc: "Skip to previous track"},
	{Name: "play_music_search", Category: "media", Desc: "Search and play music", Params: []androidParam{
		{Name: "query", Type: "string", Desc: "Music search query (artist, song, album, etc.)", Required: true},
	}},

	// ── Navigation ──
	{Name: "navigate", Category: "navigation", Desc: "Start navigation to a destination", Params: []androidParam{
		{Name: "destination", Type: "string", Desc: "Destination address or place name", Required: true},
		{Name: "mode", Type: "string", Desc: "Travel mode", Enum: []string{"driving", "walking", "bicycling", "transit"}},
	}},
	{Name: "search_nearby", Category: "navigation", Desc: "Search for nearby places (e.g. restaurants, convenience stores)", Params: []androidParam{
		{Name: "query", Type: "string", Desc: "Search query (e.g. 'コンビニ', 'レストラン')", Required: true},
	}},
	{Name: "show_map", Category: "navigation", Desc: "Show a location on the map", Params: []androidParam{
		{Name: "query", Type: "string", Desc: "Address or place name to show"},
		{Name: "latitude", Type: "number", Desc: "Latitude"},
		{Name: "longitude", Type: "number", Desc: "Longitude"},
	}},
	{Name: "get_current_location", Category: "navigation", Desc: "Get the device's current location (latitude, longitude, address)"},

	// ── Device Control ──
	{Name: "flashlight", Category: "device_control", Desc: "Turn the flashlight on or off", Params: []androidParam{
		{Name: "enabled", Type: "boolean", Desc: "true to turn on, false to turn off", Required: true},
	}},
	{Name: "set_volume", Category: "device_control", Desc: "Set the volume level for a specific audio stream", Params: []androidParam{
		{Name: "stream", Type: "string", Desc: "Audio stream type", Required: true, Enum: []string{"music", "ring", "notification", "alarm", "system"}},
		{Name: "level", Type: "integer", Desc: "Volume level (0 to max for the stream)", Required: true},
	}},
	{Name: "set_ringer_mode", Category: "device_control", Desc: "Set the ringer mode (normal, vibrate, or silent)", Params: []androidParam{
		{Name: "mode", Type: "string", Desc: "Ringer mode", Required: true, Enum: []string{"normal", "vibrate", "silent"}},
	}},
	{Name: "set_dnd", Category: "device_control", Desc: "Enable or disable Do Not Disturb mode", Params: []androidParam{
		{Name: "enabled", Type: "boolean", Desc: "true to enable DND, false to disable", Required: true},
	}},
	{Name: "set_brightness", Category: "device_control", Desc: "Set screen brightness", Params: []androidParam{
		{Name: "level", Type: "integer", Desc: "Brightness level (0-255)", Required: true},
		{Name: "auto", Type: "boolean", Desc: "Enable auto-brightness (overrides level if true)"},
	}},

	// ── Settings ──
	{Name: "open_settings", Category: "settings", Desc: "Open a specific Android settings page", Params: []androidParam{
		{Name: "section", Type: "string", Desc: "Settings section to open", Enum: []string{
			"main", "wifi", "bluetooth", "airplane", "display", "sound",
			"battery", "apps", "location", "security", "accessibility",
			"date_time", "language", "developer", "about", "notification",
			"mobile_data", "nfc", "privacy",
		}},
	}},

	// ── Web ──
	{Name: "open_url", Category: "web", Desc: "Open a URL in the browser", Params: []androidParam{
		{Name: "url", Type: "string", Desc: "URL to open", Required: true},
	}},
	{Name: "web_search", Category: "web", Desc: "Perform a web search", Params: []androidParam{
		{Name: "query", Type: "string", Desc: "Search query", Required: true},
	}},

	// ── Clipboard ──
	{Name: "clipboard_copy", Category: "clipboard", Desc: "Copy text to the clipboard", Params: []androidParam{
		{Name: "text", Type: "string", Desc: "Text to copy", Required: true},
	}},
	{Name: "clipboard_read", Category: "clipboard", Desc: "Read the current clipboard contents"},
}

// actionCategoryMap is a lookup table from action name to its category.
var actionCategoryMap map[string]string

// uiActionMap is a precomputed set of UI-only action names.
var uiActionMap map[string]bool

func init() {
	actionCategoryMap = make(map[string]string, len(allActions))
	uiActionMap = make(map[string]bool)
	for _, a := range allActions {
		actionCategoryMap[a.Name] = a.Category
		if a.UIOnly {
			uiActionMap[a.Name] = true
		}
	}
}

// actionCategory returns the category of an action, or "" for core actions.
func actionCategory(action string) string {
	return actionCategoryMap[action]
}

// enabledActions filters allActions by config and clientType.
func enabledActions(cfg config.AndroidToolsConfig, clientType string) []androidAction {
	out := make([]androidAction, 0, len(allActions))
	for _, a := range allActions {
		// Filter by client type (hide UI actions in chat mode)
		if clientType == "main" && a.UIOnly {
			continue
		}
		// Filter by category config
		if !isCategoryEnabled(cfg, a.Category) {
			continue
		}
		// Filter by individual action toggle
		if isActionDisabledByConfig(cfg, a.Name) {
			continue
		}
		out = append(out, a)
	}
	return out
}

// isActionDisabledByConfig checks if a specific action is disabled via the Actions struct.
func isActionDisabledByConfig(cfg config.AndroidToolsConfig, action string) bool {
	switch action {
	// App
	case "search_apps":
		return !cfg.App.Actions.SearchApps
	case "app_info":
		return !cfg.App.Actions.AppInfo
	case "launch_app":
		return !cfg.App.Actions.LaunchApp
	// UI
	case "screenshot":
		return !cfg.UI.Actions.Screenshot
	case "get_ui_tree":
		return !cfg.UI.Actions.GetUITree
	case "tap":
		return !cfg.UI.Actions.Tap
	case "swipe":
		return !cfg.UI.Actions.Swipe
	case "text":
		return !cfg.UI.Actions.Text
	case "keyevent":
		return !cfg.UI.Actions.KeyEvent
	// Intent
	case "broadcast":
		return !cfg.Intent.Actions.Broadcast
	case "intent":
		return !cfg.Intent.Actions.Intent
	// Alarm
	case "set_alarm":
		return !cfg.Alarm.Actions.SetAlarm
	case "set_timer":
		return !cfg.Alarm.Actions.SetTimer
	case "dismiss_alarm":
		return !cfg.Alarm.Actions.DismissAlarm
	case "show_alarms":
		return !cfg.Alarm.Actions.ShowAlarms
	// Calendar
	case "create_event":
		return !cfg.Calendar.Actions.CreateEvent
	case "query_events":
		return !cfg.Calendar.Actions.QueryEvents
	case "update_event":
		return !cfg.Calendar.Actions.UpdateEvent
	case "delete_event":
		return !cfg.Calendar.Actions.DeleteEvent
	case "list_calendars":
		return !cfg.Calendar.Actions.ListCalendars
	case "add_reminder":
		return !cfg.Calendar.Actions.AddReminder
	// Contacts
	case "search_contacts":
		return !cfg.Contacts.Actions.SearchContacts
	case "get_contact_detail":
		return !cfg.Contacts.Actions.GetContactDetail
	case "add_contact":
		return !cfg.Contacts.Actions.AddContact
	// Communication
	case "dial":
		return !cfg.Communication.Actions.Dial
	case "compose_sms":
		return !cfg.Communication.Actions.ComposeSMS
	case "compose_email":
		return !cfg.Communication.Actions.ComposeEmail
	// Media
	case "media_play_pause":
		return !cfg.Media.Actions.PlayPause
	case "media_next":
		return !cfg.Media.Actions.Next
	case "media_previous":
		return !cfg.Media.Actions.Previous
	case "play_music_search":
		return !cfg.Media.Actions.PlayMusicSearch
	// Navigation
	case "navigate":
		return !cfg.Navigation.Actions.Navigate
	case "search_nearby":
		return !cfg.Navigation.Actions.SearchNearby
	case "show_map":
		return !cfg.Navigation.Actions.ShowMap
	case "get_current_location":
		return !cfg.Navigation.Actions.GetCurrentLocation
	// Device Control
	case "flashlight":
		return !cfg.DeviceControl.Actions.Flashlight
	case "set_volume":
		return !cfg.DeviceControl.Actions.SetVolume
	case "set_ringer_mode":
		return !cfg.DeviceControl.Actions.SetRingerMode
	case "set_dnd":
		return !cfg.DeviceControl.Actions.SetDND
	case "set_brightness":
		return !cfg.DeviceControl.Actions.SetBrightness
	// Settings
	case "open_settings":
		return !cfg.Settings.Actions.OpenSettings
	// Web
	case "open_url":
		return !cfg.Web.Actions.OpenURL
	case "web_search":
		return !cfg.Web.Actions.WebSearch
	// Clipboard
	case "clipboard_copy":
		return !cfg.Clipboard.Actions.ClipboardCopy
	case "clipboard_read":
		return !cfg.Clipboard.Actions.ClipboardRead
	default:
		return false // unknown actions are not disabled
	}
}

// isCategoryEnabled checks if a given category is enabled in the config.
func isCategoryEnabled(cfg config.AndroidToolsConfig, category string) bool {
	switch category {
	case "app":
		return cfg.App.Enabled
	case "ui":
		return cfg.UI.Enabled
	case "intent":
		return cfg.Intent.Enabled
	case "alarm":
		return cfg.Alarm.Enabled
	case "calendar":
		return cfg.Calendar.Enabled
	case "contacts":
		return cfg.Contacts.Enabled
	case "communication":
		return cfg.Communication.Enabled
	case "media":
		return cfg.Media.Enabled
	case "navigation":
		return cfg.Navigation.Enabled
	case "device_control":
		return cfg.DeviceControl.Enabled
	case "settings":
		return cfg.Settings.Enabled
	case "web":
		return cfg.Web.Enabled
	case "clipboard":
		return cfg.Clipboard.Enabled
	default:
		return false
	}
}

// buildDescription generates the tool description string from enabled actions.
func buildDescription(actions []androidAction) string {
	var b strings.Builder
	b.WriteString("Control the Android device. Available actions:\n")
	for _, a := range actions {
		b.WriteString("- ")
		b.WriteString(a.Name)
		b.WriteString(": ")
		b.WriteString(a.Desc)
		b.WriteString("\n")
	}
	return b.String()
}

// buildParameters generates the JSON Schema parameters from enabled actions.
func buildParameters(actions []androidAction) map[string]interface{} {
	// Collect action names for the enum
	names := make([]string, 0, len(actions))
	for _, a := range actions {
		names = append(names, a.Name)
	}

	// Collect all unique parameters across enabled actions.
	// When the same parameter name appears in multiple actions, merge their
	// descriptions so the LLM sees all usages (e.g. "query" used by several actions).
	props := map[string]interface{}{
		"action": map[string]interface{}{
			"type":        "string",
			"enum":        names,
			"description": "The device action to perform",
		},
	}

	type paramMeta struct {
		Type  string
		Descs []string
		Enum  []string
	}
	seen := map[string]*paramMeta{}
	for _, a := range actions {
		for _, p := range a.Params {
			if m, ok := seen[p.Name]; ok {
				// Append description if it differs from existing ones
				dup := false
				for _, d := range m.Descs {
					if d == p.Desc {
						dup = true
						break
					}
				}
				if !dup {
					m.Descs = append(m.Descs, p.Desc)
				}
				continue
			}
			seen[p.Name] = &paramMeta{
				Type:  p.Type,
				Descs: []string{p.Desc},
				Enum:  p.Enum,
			}
		}
	}
	for name, m := range seen {
		desc := strings.Join(m.Descs, "; ")
		prop := map[string]interface{}{
			"type":        m.Type,
			"description": desc,
		}
		if len(m.Enum) > 0 {
			prop["enum"] = m.Enum
		}
		props[name] = prop
	}

	return map[string]interface{}{
		"type":       "object",
		"properties": props,
		"required":   []string{"action"},
	}
}
