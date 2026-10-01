package com.nexapilot.mobile;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
import java.util.UUID;

public class MainActivity extends android.app.Activity {
    private static final String SUPABASE_URL = "https://cdwcvmeruzjhjcahehqg.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_EPJE4UJ1EcsGuf2uZxvmyg_fc3QJU2F";

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
        root.addView(text("AI command center for this phone and your connected devices. v0.1.0", 14));

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

        Button accessibility = button("Enable phone control (Accessibility)");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        commandPanel.addView(accessibility);

        Button testHome = button("Test control: Go Home");
        testHome.setOnClickListener(v -> {
            boolean ok = NexaAccessibilityService.goHome();
            Toast.makeText(this, ok ? "Home command sent" : "Enable NexaPilot Accessibility first", Toast.LENGTH_SHORT).show();
        });
        commandPanel.addView(testHome);

        commandPanel.addView(text("AI Command", 18));
        commandInput = input("Example: Open Chrome and search for affordable laptops");
        commandInput.setMinLines(3);
        commandInput.setGravity(android.view.Gravity.TOP);
        commandPanel.addView(commandInput);

        Button plan = button("Plan command with NexaPilot AI");
        plan.setOnClickListener(v -> planCommand());
        commandPanel.addView(plan);

        planText = text("No AI plan yet.", 13);
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
    }

    private void refreshPanels() {
        boolean signedIn = accessToken != null && userId != null;
        authPanel.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        commandPanel.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        statusText.setText(signedIn ? "Signed in • " + (NexaAccessibilityService.isRunning() ? "Phone control ON" : "Phone control OFF") : "Sign in to connect this phone.");
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
                        .put("agent_version", "0.1.0")
                        .put("status", "online")
                        .put("last_seen_at", java.time.Instant.now().toString())
                        .put("capabilities", new JSONObject().put("accessibility", true).put("gestures", Build.VERSION.SDK_INT >= 24));

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

    private void planCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) return;
        planText.setText("NexaPilot is planning...");
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject().put("message", command).put("target", "android");
                HttpResult result = request("POST", SUPABASE_URL + "/functions/v1/ai-command", payload.toString(), accessToken);
                if (result.code == 401) {
                    ui(() -> planText.setText("Session expired. Sign out and sign in again."));
                    return;
                }
                if (result.code < 200 || result.code >= 300) {
                    ui(() -> planText.setText("AI error HTTP " + result.code + "\n" + result.body));
                    return;
                }
                JSONObject json = new JSONObject(result.body);
                JSONObject plan = json.optJSONObject("plan");
                String pretty = plan != null ? plan.toString(2) : json.toString(2);
                ui(() -> planText.setText(pretty));
            } catch (Exception e) {
                ui(() -> planText.setText("AI error: " + e.getMessage()));
            }
        }).start();
    }

    private void signOut() {
        accessToken = null;
        userId = null;
        prefs.edit().remove("access_token").remove("user_id").apply();
        refreshPanels();
    }

    private HttpResult request(String method, String urlValue, String body, String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlValue).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
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
