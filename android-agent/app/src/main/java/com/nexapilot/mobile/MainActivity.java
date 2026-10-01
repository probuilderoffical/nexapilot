package com.nexapilot.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String SUPABASE_URL = "https://cdwcvmeruzjhjcahehqg.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_EPJE4UJ1EcsGuf2uZxvmyg_fc3QJU2F";
    private static final int VOICE_REQUEST = 2001;
    private static final int AUDIO_PERMISSION_REQUEST = 2002;

    private SharedPreferences prefs;
    private String accessToken;
    private String userId;

    private LinearLayout authPanel;
    private LinearLayout commandPanel;
    private EditText emailInput;
    private EditText passwordInput;
    private EditText commandInput;
    private TextView statusText;
    private TextView planText;
    private TextView deviceText;
    private TextView liveStatusText;
    private Button micButton;
    private Button speakerButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("nexapilot", MODE_PRIVATE);
        accessToken = prefs.getString("access_token", null);
        userId = prefs.getString("user_id", null);
        buildUi();
        refreshPanels();
        if (accessToken != null && userId != null) registerDeviceAsync();
    }

    private TextView text(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(Color.rgb(232, 236, 255));
        v.setPadding(0, 8, 0, 8);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        return b;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(140, 150, 180));
        e.setTextColor(Color.WHITE);
        e.setBackgroundColor(Color.rgb(25, 35, 58));
        e.setPadding(24, 18, 24, 18);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 8, 0, 12);
        e.setLayoutParams(p);
        return e;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(11, 16, 32));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 36, 32, 48);
        scroll.addView(root);

        TextView title = text("NexaPilot Mobile", 28);
        title.setTextColor(Color.WHITE);
        root.addView(title);
        root.addView(text("Live AI device assistant • v0.3.0", 14));

        statusText = text("Ready", 14);
        root.addView(statusText);

        authPanel = new LinearLayout(this);
        authPanel.setOrientation(LinearLayout.VERTICAL);
        root.addView(authPanel);

        emailInput = input("Email");
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        authPanel.addView(emailInput);

        passwordInput = input("Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        authPanel.addView(passwordInput);

        Button signIn = button("Sign in");
        signIn.setOnClickListener(v -> authenticate(false));
        authPanel.addView(signIn);

        Button signUp = button("Create account");
        signUp.setOnClickListener(v -> authenticate(true));
        authPanel.addView(signUp);

        commandPanel = new LinearLayout(this);
        commandPanel.setOrientation(LinearLayout.VERTICAL);
        root.addView(commandPanel);

        deviceText = text("Device: not registered", 14);
        commandPanel.addView(deviceText);

        commandPanel.addView(text("LIVE MODE", 20));
        liveStatusText = text("Live Mode OFF", 15);
        commandPanel.addView(liveStatusText);

        Button liveOn = button("▶ START LIVE MODE");
        liveOn.setOnClickListener(v -> startLiveMode());
        commandPanel.addView(liveOn);

        Button liveOff = button("■ STOP LIVE MODE");
        liveOff.setOnClickListener(v -> sendLiveAction(LiveAssistantService.ACTION_STOP));
        commandPanel.addView(liveOff);

        micButton = button("");
        micButton.setOnClickListener(v -> toggleMic());
        commandPanel.addView(micButton);

        speakerButton = button("");
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        commandPanel.addView(speakerButton);

        commandPanel.addView(text("Live example: 'NexaPilot, YouTube kholo' ya 'NexaPilot, Google pe AI agents search karo'.", 13));

        Button accessibility = button("Enable phone control (Accessibility)");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        commandPanel.addView(accessibility);

        Button testHome = button("Test control: Go Home");
        testHome.setOnClickListener(v -> {
            boolean ok = NexaAccessibilityService.goHome();
            Toast.makeText(this, ok ? "Home command sent" : "Enable NexaPilot Accessibility first", Toast.LENGTH_SHORT).show();
        });
        commandPanel.addView(testHome);

        commandPanel.addView(text("Manual Command", 18));
        commandInput = input("Example: Open ChatGPT");
        commandInput.setMinLines(3);
        commandInput.setGravity(android.view.Gravity.TOP);
        commandPanel.addView(commandInput);

        Button voice = button("🎤 ONE-TIME VOICE COMMAND");
        voice.setOnClickListener(v -> startVoiceInput());
        commandPanel.addView(voice);

        Button run = button("RUN COMMAND WITH NEXAPILOT AI");
        run.setOnClickListener(v -> planCommand(true));
        commandPanel.addView(run);

        planText = text("No AI command yet.", 13);
        planText.setTextIsSelectable(true);
        planText.setPadding(12, 20, 12, 20);
        commandPanel.addView(planText);

        Button refresh = button("Refresh device status");
        refresh.setOnClickListener(v -> registerDeviceAsync());
        commandPanel.addView(refresh);

        Button signOut = button("Sign out");
        signOut.setOnClickListener(v -> signOut());
        commandPanel.addView(signOut);

        setContentView(scroll);
        refreshLiveControls();
    }

    private void startLiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
            return;
        }
        sendLiveAction(LiveAssistantService.ACTION_START);
        prefs.edit().putBoolean("live_enabled", true).apply();
        refreshLiveControls();
        Toast.makeText(this, "NexaPilot Live started", Toast.LENGTH_SHORT).show();
    }

    private void sendLiveAction(String action) {
        Intent intent = new Intent(this, LiveAssistantService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !LiveAssistantService.ACTION_STOP.equals(action)) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        if (LiveAssistantService.ACTION_STOP.equals(action)) {
            prefs.edit().putBoolean("live_enabled", false).apply();
        }
        refreshLiveControls();
    }

    private void toggleMic() {
        boolean enabled = prefs.getBoolean("live_mic", true);
        prefs.edit().putBoolean("live_mic", !enabled).apply();
        sendLiveAction(!enabled ? LiveAssistantService.ACTION_MIC_ON : LiveAssistantService.ACTION_MIC_OFF);
        refreshLiveControls();
    }

    private void toggleSpeaker() {
        boolean enabled = prefs.getBoolean("live_speaker", true);
        prefs.edit().putBoolean("live_speaker", !enabled).apply();
        sendLiveAction(!enabled ? LiveAssistantService.ACTION_SPEAKER_ON : LiveAssistantService.ACTION_SPEAKER_OFF);
        refreshLiveControls();
    }

    private void refreshLiveControls() {
        if (micButton == null || speakerButton == null || liveStatusText == null) return;
        boolean live = prefs.getBoolean("live_enabled", false);
        boolean mic = prefs.getBoolean("live_mic", true);
        boolean speaker = prefs.getBoolean("live_speaker", true);
        liveStatusText.setText("Live Mode " + (live ? "ON" : "OFF") + " • Mic " + (mic ? "ON" : "OFF") + " • Speaker " + (speaker ? "ON" : "OFF"));
        micButton.setText(mic ? "🎤 MIC ON — tap to mute" : "🎤 MIC OFF — tap to enable");
        speakerButton.setText(speaker ? "🔊 SPEAKER ON — tap to mute" : "🔇 SPEAKER OFF — tap to enable");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLiveMode();
        } else if (requestCode == AUDIO_PERMISSION_REQUEST) {
            Toast.makeText(this, "Mic permission ke baghair Live Mode sun nahi sakta.", Toast.LENGTH_LONG).show();
        }
    }

    private void startVoiceInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "NexaPilot command bolo");
            startActivityForResult(intent, VOICE_REQUEST);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Voice recognition is not available on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                commandInput.setText(results.get(0));
                planCommand(true);
            }
        }
    }

    private void refreshPanels() {
        boolean signedIn = accessToken != null && userId != null;
        authPanel.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        commandPanel.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        statusText.setText(signedIn ? "Signed in • " + (NexaAccessibilityService.isRunning() ? "Phone control ON" : "Phone control OFF") : "Sign in to connect this phone.");
        refreshLiveControls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusText != null && accessToken != null) refreshPanels();
    }

    private void authenticate(boolean signUp) {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (email.isEmpty() || password.length() < 6) {
            statusText.setText("Enter email and a password of at least 6 characters.");
            return;
        }
        statusText.setText(signUp ? "Creating account..." : "Signing in...");
        new Thread(() -> {
            try {
                String endpoint = signUp ? "/auth/v1/signup" : "/auth/v1/token?grant_type=password";
                JSONObject payload = new JSONObject().put("email", email).put("password", password);
                HttpResult result = request("POST", SUPABASE_URL + endpoint, payload.toString(), null);
                JSONObject json = new JSONObject(result.body);
                if (result.code < 200 || result.code >= 300) {
                    String msg = json.optString("msg", json.optString("error_description", json.optString("message", "Authentication failed")));
                    ui(() -> statusText.setText(msg));
                    return;
                }

                String token = json.optString("access_token", null);
                JSONObject user = json.optJSONObject("user");
                if (token == null || user == null) {
                    ui(() -> statusText.setText("Account created. Confirm your email, then tap Sign in."));
                    return;
                }

                accessToken = token;
                userId = user.optString("id", null);
                prefs.edit().putString("access_token", accessToken).putString("user_id", userId).apply();
                ui(this::refreshPanels);
                registerDeviceAsync();
            } catch (Exception e) {
                ui(() -> statusText.setText("Auth error: " + e.getMessage()));
            }
        }).start();
    }

    private void registerDeviceAsync() {
        if (accessToken == null || userId == null) return;
        deviceText.setText("Registering this phone...");
        new Thread(() -> {
            try {
                String deviceKey = prefs.getString("device_key", null);
                if (deviceKey == null) {
                    deviceKey = UUID.randomUUID().toString();
                    prefs.edit().putString("device_key", deviceKey).apply();
                }

                String queryUrl = SUPABASE_URL + "/rest/v1/devices?select=id,name,status&user_id=eq." + userId + "&device_key=eq." + deviceKey + "&limit=1";
                HttpResult lookup = request("GET", queryUrl, null, accessToken);
                JSONArray rows = lookup.code >= 200 && lookup.code < 300 ? new JSONArray(lookup.body) : new JSONArray();

                String label = Build.MANUFACTURER + " " + Build.MODEL;
                JSONObject data = new JSONObject()
                        .put("name", label)
                        .put("platform", "android")
                        .put("os_version", "Android " + Build.VERSION.RELEASE)
                        .put("agent_version", "0.3.0")
                        .put("status", "online")
                        .put("last_seen_at", java.time.Instant.now().toString())
                        .put("capabilities", new JSONObject()
                                .put("accessibility", true)
                                .put("gestures", Build.VERSION.SDK_INT >= 24)
                                .put("voice", true)
                                .put("live_mode", true)
                                .put("tts", true)
                                .put("dynamic_app_launch", true)
                                .put("open_url", true)
                                .put("type_text", true));

                HttpResult saved;
                if (rows.length() > 0) {
                    String id = rows.getJSONObject(0).getString("id");
                    saved = request("PATCH", SUPABASE_URL + "/rest/v1/devices?id=eq." + id, data.toString(), accessToken);
                } else {
                    data.put("user_id", userId).put("device_key", deviceKey);
                    saved = request("POST", SUPABASE_URL + "/rest/v1/devices", data.toString(), accessToken);
                }

                boolean ok = saved.code >= 200 && saved.code < 300;
                ui(() -> {
                    deviceText.setText(ok ? "Connected: " + label + " • Android " + Build.VERSION.RELEASE : "Device registration failed (HTTP " + saved.code + ")");
                    refreshPanels();
                });
            } catch (Exception e) {
                ui(() -> deviceText.setText("Device error: " + e.getMessage()));
            }
        }).start();
    }

    private void planCommand(boolean execute) {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) return;
        planText.setText("NexaPilot is thinking...");
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject().put("message", command).put("target", "android");
                HttpResult result = request("POST", SUPABASE_URL + "/functions/v1/ai-command", payload.toString(), accessToken);
                if (result.code == 401) {
                    ui(() -> planText.setText("Session expired. Sign out and sign in again."));
                    return;
                }
                if (result.code < 200 || result.code >= 300) {
                    ui(() -> planText.setText("AI temporarily unavailable (HTTP " + result.code + "). Try again.\n" + result.body));
                    return;
                }
                JSONObject json = new JSONObject(result.body);
                JSONObject plan = json.optJSONObject("plan");
                String model = json.optString("model", "Gemini");
                String pretty = plan != null ? plan.toString(2) : json.toString(2);
                ui(() -> planText.setText("Model: " + model + "\n" + pretty));
                if (execute && plan != null) executePlan(plan);
            } catch (Exception e) {
                ui(() -> planText.setText("AI error: " + e.getMessage()));
            }
        }).start();
    }

    private void executePlan(JSONObject plan) {
        if (plan.optBoolean("needs_clarification", false)) return;
        JSONArray actions = plan.optJSONArray("actions");
        if (actions == null) return;

        ui(() -> {
            for (int i = 0; i < actions.length(); i++) {
                JSONObject action = actions.optJSONObject(i);
                if (action == null) continue;
                if (action.optBoolean("requires_approval", false)) {
                    Toast.makeText(this, "Approval required for: " + action.optString("tool"), Toast.LENGTH_LONG).show();
                    continue;
                }
                executeAction(action);
            }
        });
    }

    private void executeAction(JSONObject action) {
        String tool = action.optString("tool", "");
        JSONObject args = action.optJSONObject("args");
        if (args == null) args = new JSONObject();

        try {
            switch (tool) {
                case "open_app":
                    if (!openAppByLabel(args.optString("app", args.optString("package", "")))) {
                        Toast.makeText(this, "App not found", Toast.LENGTH_LONG).show();
                    }
                    break;
                case "open_url":
                    String url = args.optString("url", "");
                    if (!url.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    break;
                case "browser_search":
                    String query = args.optString("query", args.optString("text", ""));
                    if (!query.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))));
                    break;
                case "press_key":
                    String key = args.optString("key", "").toLowerCase(Locale.ROOT);
                    if ("home".equals(key)) NexaAccessibilityService.goHome();
                    else if ("back".equals(key)) NexaAccessibilityService.goBack();
                    break;
                case "type_text":
                    NexaAccessibilityService.typeIntoFocusedField(args.optString("text", ""));
                    break;
                default:
                    Toast.makeText(this, "Tool not supported yet: " + tool, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Action failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean openAppByLabel(String requested) {
        String want = normalizeName(requested);
        if (want.isEmpty()) return false;
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN, null);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);

        ResolveInfo best = null;
        int bestScore = -1;
        for (ResolveInfo info : apps) {
            CharSequence labelCs = info.loadLabel(pm);
            String label = labelCs == null ? "" : labelCs.toString();
            int score = matchScore(want, normalizeName(label));
            if (score > bestScore) {
                bestScore = score;
                best = info;
            }
        }

        if (best != null && bestScore >= 60) {
            Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
            if (launch != null) {
                startActivity(launch);
                return true;
            }
        }

        Intent pkg = pm.getLaunchIntentForPackage(requested.trim());
        if (pkg != null) {
            startActivity(pkg);
            return true;
        }
        return false;
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
    }

    private int matchScore(String want, String label) {
        if (want.equals(label)) return 100;
        if (label.startsWith(want) || want.startsWith(label)) return 90;
        if (label.contains(want) || want.contains(label)) return 80;
        String[] a = want.split(" ");
        String[] b = label.split(" ");
        int common = 0;
        for (String x : a) for (String y : b) if (!x.isEmpty() && x.equals(y)) common++;
        return common > 0 ? 60 + Math.min(15, common * 5) : -1;
    }

    private void signOut() {
        sendLiveAction(LiveAssistantService.ACTION_STOP);
        accessToken = null;
        userId = null;
        prefs.edit().remove("access_token").remove("user_id").putBoolean("live_enabled", false).apply();
        refreshPanels();
    }

    private HttpResult request(String method, String urlValue, String body, String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlValue).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(45000);
        conn.setRequestProperty("apikey", SUPABASE_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        if ("POST".equals(method)) conn.setRequestProperty("Prefer", "return=minimal");
        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
        }
        conn.disconnect();
        return new HttpResult(code, text.toString());
    }

    private void ui(Runnable r) {
        runOnUiThread(r);
    }

    private static class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body; }
    }
}
