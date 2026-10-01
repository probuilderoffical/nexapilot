package com.nexapilot.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.view.Gravity;
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
import java.util.Locale;

public class NexaPilotActivity extends Activity {
    private static final int REQ_AUDIO = 401;
    private static final int REQ_VOICE = 402;
    private static final int REQ_SCREEN = 403;
    private static final String SUPABASE_URL = "https://cdwcvmeruzjhjcahehqg.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_EPJE4UJ1EcsGuf2uZxvmyg_fc3QJU2F";

    private SharedPreferences prefs;
    private LinearLayout content;
    private TextView topStatus;
    private int currentTab = 0;

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private Button btn(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackground(bg(color, 18));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("nexapilot", MODE_PRIVATE);
        if (prefs.getString("access_token", null) == null) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        buildShell();
        showChat();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(7, 10, 18));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(20), dp(20), dp(12));
        TextView title = text("NexaPilot", 27, Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title);
        topStatus = text("Ready", 12, Color.rgb(145, 160, 195));
        topStatus.setPadding(0, dp(3), 0, 0);
        header.addView(topStatus);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(22));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(10), dp(8), dp(10), dp(12));
        nav.setBackgroundColor(Color.rgb(12, 17, 29));

        Button chat = btn("Chat", Color.rgb(31, 39, 58));
        Button live = btn("Live", Color.rgb(31, 39, 58));
        Button settings = btn("Settings", Color.rgb(31, 39, 58));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        chat.setLayoutParams(new LinearLayout.LayoutParams(p));
        live.setLayoutParams(new LinearLayout.LayoutParams(p));
        settings.setLayoutParams(new LinearLayout.LayoutParams(p));
        chat.setOnClickListener(v -> showChat());
        live.setOnClickListener(v -> showLive());
        settings.setOnClickListener(v -> showSettings());
        nav.addView(chat); nav.addView(live); nav.addView(settings);
        root.addView(nav);

        setContentView(root);
    }

    private void clear() { content.removeAllViews(); }

    private void sectionTitle(String title, String subtitle) {
        TextView t = text(title, 24, Color.WHITE);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(t);
        TextView s = text(subtitle, 14, Color.rgb(151, 164, 196));
        s.setPadding(0, dp(5), 0, dp(18));
        content.addView(s);
    }

    private void showChat() {
        currentTab = 0;
        clear();
        topStatus.setText("Chat • type or speak a command");
        sectionTitle("Chat", "Talk normally, ask questions, or tell NexaPilot to operate your phone.");

        LinearLayout history = new LinearLayout(this);
        history.setOrientation(LinearLayout.VERTICAL);
        history.setPadding(dp(14), dp(14), dp(14), dp(14));
        history.setBackground(bg(Color.rgb(15, 21, 36), 22));
        TextView intro = text("NexaPilot\nMain ready hoon. Bolo kya karna hai.", 15, Color.rgb(225, 230, 245));
        intro.setPadding(dp(5), dp(5), dp(5), dp(14));
        history.addView(intro);
        TextView answer = text("", 14, Color.rgb(180, 194, 226));
        answer.setPadding(dp(5), dp(8), dp(5), dp(8));
        history.addView(answer);
        content.addView(history);

        EditText input = new EditText(this);
        input.setHint("Message NexaPilot…");
        input.setHintTextColor(Color.rgb(112, 126, 157));
        input.setTextColor(Color.WHITE);
        input.setTextSize(16);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        input.setBackground(bg(Color.rgb(20, 28, 46), 20));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.setMargins(0, dp(18), 0, dp(10));
        input.setLayoutParams(ip);
        content.addView(input);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button dictate = btn("🎤  Speak to text", Color.rgb(39, 49, 73));
        Button send = btn("Send  ➜", Color.rgb(82, 73, 220));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(52), 1f);
        half.setMargins(0, 0, dp(5), 0);
        dictate.setLayoutParams(half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(52), 1f);
        half2.setMargins(dp(5), 0, 0, 0);
        send.setLayoutParams(half2);
        row.addView(dictate); row.addView(send);
        content.addView(row);

        dictate.setOnClickListener(v -> startDictation());
        send.setOnClickListener(v -> {
            String message = input.getText().toString().trim();
            if (message.isEmpty()) return;
            input.setText("");
            answer.setText("You: " + message + "\n\nNexaPilot: working…");
            runTextCommand(message, answer);
        });

        Button goLive = btn("Start Live conversation", Color.rgb(28, 111, 91));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(54));
        gp.setMargins(0, dp(16), 0, 0);
        goLive.setLayoutParams(gp);
        goLive.setOnClickListener(v -> showLive());
        content.addView(goLive);
    }

    private void showLive() {
        currentTab = 1;
        clear();
        boolean live = prefs.getBoolean("live_enabled", false);
        topStatus.setText(live ? "Live • active" : "Live • off");
        sectionTitle("Live", "Natural voice, screen understanding and real phone actions while you use other apps.");

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(18), dp(28), dp(18), dp(28));
        hero.setBackground(bg(Color.rgb(18, 24, 44), 28));
        TextView orb = text(live ? "●" : "○", 72, live ? Color.rgb(92, 232, 179) : Color.rgb(107, 120, 151));
        orb.setGravity(Gravity.CENTER);
        hero.addView(orb);
        TextView state = text(live ? "NexaPilot is live" : "Start a live session", 21, Color.WHITE);
        state.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        state.setGravity(Gravity.CENTER);
        hero.addView(state);
        TextView hint = text("You can leave this app. The session keeps running in the background.", 13, Color.rgb(153, 168, 200));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(16));
        hero.addView(hint);
        Button liveBtn = btn(live ? "Stop Live" : "Start Live", live ? Color.rgb(156, 54, 69) : Color.rgb(82, 73, 220));
        liveBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(56)));
        liveBtn.setOnClickListener(v -> { if (prefs.getBoolean("live_enabled", false)) stopLive(); else startLive(); showLive(); });
        hero.addView(liveBtn);
        content.addView(hero);

        boolean mic = prefs.getBoolean("live_mic", true);
        boolean speaker = prefs.getBoolean("live_speaker", true);
        boolean vision = ScreenCaptureService.isActive();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(16), 0, 0);
        Button micBtn = btn(mic ? "🎤 Mic on" : "Mic off", Color.rgb(37, 47, 69));
        Button spBtn = btn(speaker ? "🔊 Speaker on" : "Speaker off", Color.rgb(37, 47, 69));
        LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(0, dp(52), 1f); h1.setMargins(0,0,dp(5),0);
        LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(0, dp(52), 1f); h2.setMargins(dp(5),0,0,0);
        micBtn.setLayoutParams(h1); spBtn.setLayoutParams(h2);
        row.addView(micBtn); row.addView(spBtn); content.addView(row);
        micBtn.setOnClickListener(v -> { toggleMic(); showLive(); });
        spBtn.setOnClickListener(v -> { toggleSpeaker(); showLive(); });

        Button visionBtn = btn(vision ? "👁  Screen Vision ON — tap to stop" : "👁  Share screen with NexaPilot", vision ? Color.rgb(28, 111, 91) : Color.rgb(39, 49, 73));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, dp(56)); vp.setMargins(0, dp(10), 0, 0); visionBtn.setLayoutParams(vp);
        visionBtn.setOnClickListener(v -> { if (ScreenCaptureService.isActive()) stopScreenVision(); else requestScreenVision(); });
        content.addView(visionBtn);

        TextView note = text("Live control uses Accessibility for taps/typing and optional Screen Vision for pixels. Android will show its own screen-sharing indicator while vision is active.", 13, Color.rgb(142, 157, 190));
        note.setPadding(0, dp(16), 0, 0);
        content.addView(note);
    }

    private void showSettings() {
        currentTab = 2;
        clear();
        topStatus.setText("Settings • permissions and safety");
        sectionTitle("Settings", "Control exactly what NexaPilot can hear, see and operate.");

        addSetting("Accessibility control", NexaAccessibilityService.isRunning() ? "On" : "Off", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        addSetting("Floating live controls", overlay ? "On" : "Off", this::requestOverlay);
        boolean mic = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        addSetting("Microphone permission", mic ? "Allowed" : "Not allowed", this::requestAudio);
        addSetting("Screen Vision", ScreenCaptureService.isActive() ? "Sharing" : "Off", () -> { if (ScreenCaptureService.isActive()) stopScreenVision(); else requestScreenVision(); });
        addSetting("Sensitive actions", "Ask before sending, deleting, paying or publishing", () -> Toast.makeText(this, "Safety confirmation is enabled.", Toast.LENGTH_SHORT).show());
        addSetting("Account / setup", "Open login and device details", () -> startActivity(new Intent(this, MainActivity.class)));

        Button signOut = btn("Sign out", Color.rgb(112, 43, 55));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(54)); sp.setMargins(0, dp(18), 0, 0); signOut.setLayoutParams(sp);
        signOut.setOnClickListener(v -> {
            stopLive(); stopScreenVision();
            prefs.edit().remove("access_token").remove("user_id").apply();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        content.addView(signOut);
    }

    private void addSetting(String title, String value, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(bg(Color.rgb(17, 23, 38), 18));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.setMargins(0, 0, 0, dp(10)); card.setLayoutParams(cp);
        TextView t = text(title, 16, Color.WHITE); t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); card.addView(t);
        TextView v = text(value, 13, Color.rgb(148, 164, 197)); v.setPadding(0, dp(4), 0, dp(8)); card.addView(v);
        Button b = btn("Open", Color.rgb(34, 43, 64)); b.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(46))); b.setOnClickListener(x -> action.run()); card.addView(b);
        content.addView(card);
    }

    private void startLive() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudio(); return;
        }
        Intent i = new Intent(this, LiveAssistantService.class).setAction(LiveAssistantService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        prefs.edit().putBoolean("live_enabled", true).apply();
        Toast.makeText(this, "Live started — you can leave the app now.", Toast.LENGTH_SHORT).show();
    }

    private void stopLive() {
        Intent i = new Intent(this, LiveAssistantService.class).setAction(LiveAssistantService.ACTION_STOP);
        startService(i);
        prefs.edit().putBoolean("live_enabled", false).apply();
    }

    private void toggleMic() {
        boolean next = !prefs.getBoolean("live_mic", true);
        prefs.edit().putBoolean("live_mic", next).apply();
        Intent i = new Intent(this, LiveAssistantService.class).setAction(next ? LiveAssistantService.ACTION_MIC_ON : LiveAssistantService.ACTION_MIC_OFF);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void toggleSpeaker() {
        boolean next = !prefs.getBoolean("live_speaker", true);
        prefs.edit().putBoolean("live_speaker", next).apply();
        Intent i = new Intent(this, LiveAssistantService.class).setAction(next ? LiveAssistantService.ACTION_SPEAKER_ON : LiveAssistantService.ACTION_SPEAKER_OFF);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void requestAudio() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }
    }

    private void requestScreenVision() {
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_SCREEN);
    }

    private void stopScreenVision() {
        startService(new Intent(this, ScreenCaptureService.class).setAction(ScreenCaptureService.ACTION_STOP));
        if (currentTab == 1) getWindow().getDecorView().postDelayed(this::showLive, 200);
    }

    private void startDictation() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Speech recognition unavailable.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCREEN && resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, ScreenCaptureService.class)
                    .setAction(ScreenCaptureService.ACTION_START)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            Toast.makeText(this, "Screen Vision started.", Toast.LENGTH_SHORT).show();
            if (currentTab == 1) getWindow().getDecorView().postDelayed(this::showLive, 350);
        } else if (requestCode == REQ_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) {
                final String spoken = r.get(0);
                Toast.makeText(this, spoken, Toast.LENGTH_SHORT).show();
                LiveAssistantService.submitText(spoken);
            }
        }
    }

    private void runTextCommand(String message, TextView answer) {
        if (LiveAssistantService.submitText(message)) {
            answer.setText("You: " + message + "\n\nNexaPilot: sent to your active Live session.");
            return;
        }
        String accessToken = prefs.getString("access_token", null);
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject().put("message", message).put("target", "android");
                HttpResult r = request("POST", SUPABASE_URL + "/functions/v1/ai-command", payload.toString(), accessToken);
                if (r.code < 200 || r.code >= 300) {
                    runOnUiThread(() -> answer.setText("You: " + message + "\n\nNexaPilot: AI unavailable (HTTP " + r.code + ")"));
                    return;
                }
                JSONObject json = new JSONObject(r.body);
                JSONObject plan = json.optJSONObject("plan");
                if (plan == null) return;
                String reply = plan.optString("assistant_message", "Theek hai.");
                JSONArray actions = plan.optJSONArray("actions");
                if (actions != null) executeActions(actions);
                runOnUiThread(() -> answer.setText("You: " + message + "\n\nNexaPilot: " + reply));
            } catch (Exception e) {
                runOnUiThread(() -> answer.setText("You: " + message + "\n\nNexaPilot: command process nahi ho saka."));
            }
        }).start();
    }

    private void executeActions(JSONArray actions) {
        for (int n = 0; n < actions.length(); n++) {
            JSONObject a = actions.optJSONObject(n); if (a == null || a.optBoolean("requires_approval", false)) continue;
            JSONObject args = a.optJSONObject("args"); if (args == null) args = new JSONObject();
            String tool = a.optString("tool", "");
            if ("press_key".equals(tool)) {
                String k = args.optString("key", ""); if ("home".equalsIgnoreCase(k)) NexaAccessibilityService.goHome(); else if ("back".equalsIgnoreCase(k)) NexaAccessibilityService.goBack();
            } else if ("type_text".equals(tool)) NexaAccessibilityService.typeIntoFocusedField(args.optString("text", ""));
            else if ("click".equals(tool)) NexaAccessibilityService.tap((float)args.optDouble("x",0), (float)args.optDouble("y",0));
        }
    }

    private HttpResult request(String method, String urlValue, String body, String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlValue).openConnection();
        conn.setRequestMethod(method); conn.setConnectTimeout(15000); conn.setReadTimeout(45000);
        conn.setRequestProperty("apikey", SUPABASE_KEY); conn.setRequestProperty("Content-Type", "application/json");
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) { conn.setDoOutput(true); try (OutputStream out = conn.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); } }
        int code = conn.getResponseCode(); InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder s = new StringBuilder(); if (stream != null) try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { String line; while ((line = br.readLine()) != null) s.append(line); }
        conn.disconnect(); return new HttpResult(code, s.toString());
    }

    private static class HttpResult { final int code; final String body; HttpResult(int code, String body) { this.code = code; this.body = body; } }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentTab == 1) showLive();
        else if (currentTab == 2) showSettings();
    }
}
