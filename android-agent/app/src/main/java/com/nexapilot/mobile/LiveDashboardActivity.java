package com.nexapilot.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class LiveDashboardActivity extends Activity {
    private SharedPreferences prefs;
    private TextView liveState;
    private TextView permissionState;
    private Button micButton;
    private Button speakerButton;
    private Button liveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("nexapilot", MODE_PRIVATE);
        if (prefs.getString("access_token", null) == null) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        buildUi();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable panel(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp((int) radius));
        return d;
    }

    private TextView label(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private Button action(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setBackground(panel(Color.rgb(38, 48, 72), 14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(p);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 12, 24));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(34));
        scroll.addView(root);

        TextView title = label("NexaPilot", 32, Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView subtitle = label("Live AI phone agent", 15, Color.rgb(158, 170, 205));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(3), 0, dp(20));
        subtitle.setLayoutParams(sp);
        root.addView(subtitle);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        hero.setBackground(panel(Color.rgb(18, 26, 48), 22));
        liveState = label("Live Mode OFF", 21, Color.WHITE);
        liveState.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hero.addView(liveState);
        TextView desc = label("Natural voice conversation + live Android actions", 14, Color.rgb(170, 182, 215));
        desc.setPadding(0, dp(5), 0, dp(14));
        hero.addView(desc);

        liveButton = action("Start Live Mode");
        liveButton.setBackground(panel(Color.rgb(88, 88, 230), 16));
        liveButton.setOnClickListener(v -> toggleLive());
        hero.addView(liveButton);
        root.addView(hero);

        TextView controlsTitle = label("Controls", 18, Color.WHITE);
        controlsTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        controlsTitle.setPadding(0, dp(22), 0, dp(8));
        root.addView(controlsTitle);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        micButton = action("");
        speakerButton = action("");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(54), 1f);
        half.setMargins(0, 0, dp(5), 0);
        micButton.setLayoutParams(half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(54), 1f);
        half2.setMargins(dp(5), 0, 0, 0);
        speakerButton.setLayoutParams(half2);
        micButton.setOnClickListener(v -> toggleMic());
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        row.addView(micButton);
        row.addView(speakerButton);
        root.addView(row);

        TextView permissionsTitle = label("Phone access", 18, Color.WHITE);
        permissionsTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        permissionsTitle.setPadding(0, dp(22), 0, dp(8));
        root.addView(permissionsTitle);

        permissionState = label("", 13, Color.rgb(160, 172, 203));
        permissionState.setPadding(0, 0, 0, dp(8));
        root.addView(permissionState);

        Button accessibility = action("Enable Accessibility control");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button overlay = action("Enable floating Live controls");
        overlay.setOnClickListener(v -> requestOverlayPermission());
        root.addView(overlay);

        Button micPermission = action("Allow microphone");
        micPermission.setOnClickListener(v -> requestMicPermission());
        root.addView(micPermission);

        TextView info = label(
                "Live Mode runs as an Android foreground service. Once started, NexaPilot stays active while you use YouTube, Chrome, WhatsApp or other apps. " +
                "The small floating panel shows status and lets you mute Mic/Speaker or stop the session.",
                13, Color.rgb(145, 158, 190));
        info.setPadding(0, dp(20), 0, dp(14));
        root.addView(info);

        Button advanced = action("Open setup / account screen");
        advanced.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(advanced);

        setContentView(scroll);
        refreshUi();
    }

    private void toggleLive() {
        boolean live = prefs.getBoolean("live_enabled", false);
        if (live) {
            sendAction(LiveAssistantService.ACTION_STOP);
            prefs.edit().putBoolean("live_enabled", false).apply();
            refreshUi();
            return;
        }
        if (!hasMicPermission()) {
            requestMicPermission();
            Toast.makeText(this, "Mic allow karke Start Live dubara tap karo.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!NexaAccessibilityService.isRunning()) {
            Toast.makeText(this, "Accessibility ON karna recommended hai taake NexaPilot phone control kar sake.", Toast.LENGTH_LONG).show();
        }
        sendAction(LiveAssistantService.ACTION_START);
        prefs.edit().putBoolean("live_enabled", true).apply();
        refreshUi();
        Toast.makeText(this, "NexaPilot Live started", Toast.LENGTH_SHORT).show();
        // The assistant keeps running; return the user to their phone instead of trapping them in this app.
        getWindow().getDecorView().postDelayed(() -> moveTaskToBack(true), 450);
    }

    private void toggleMic() {
        boolean next = !prefs.getBoolean("live_mic", true);
        prefs.edit().putBoolean("live_mic", next).apply();
        sendAction(next ? LiveAssistantService.ACTION_MIC_ON : LiveAssistantService.ACTION_MIC_OFF);
        refreshUi();
    }

    private void toggleSpeaker() {
        boolean next = !prefs.getBoolean("live_speaker", true);
        prefs.edit().putBoolean("live_speaker", next).apply();
        sendAction(next ? LiveAssistantService.ACTION_SPEAKER_ON : LiveAssistantService.ACTION_SPEAKER_OFF);
        refreshUi();
    }

    private void sendAction(String action) {
        Intent i = new Intent(this, LiveAssistantService.class);
        i.setAction(action);
        if (Build.VERSION.SDK_INT >= 26 && !LiveAssistantService.ACTION_STOP.equals(action)) startForegroundService(i);
        else startService(i);
    }

    private void refreshUi() {
        if (liveState == null) return;
        boolean live = prefs.getBoolean("live_enabled", false);
        boolean mic = prefs.getBoolean("live_mic", true);
        boolean speaker = prefs.getBoolean("live_speaker", true);
        liveState.setText(live ? "● Live Mode ON" : "Live Mode OFF");
        liveState.setTextColor(live ? Color.rgb(112, 232, 168) : Color.WHITE);
        liveButton.setText(live ? "Stop Live Mode" : "Start Live Mode");
        micButton.setText(mic ? "🎤  Mic ON" : "Mic OFF");
        speakerButton.setText(speaker ? "🔊  Speaker ON" : "Speaker OFF");
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        permissionState.setText("Accessibility: " + (NexaAccessibilityService.isRunning() ? "ON" : "OFF") +
                "   •   Floating controls: " + (overlay ? "ON" : "OFF") +
                "   •   Mic: " + (hasMicPermission() ? "allowed" : "not allowed"));
    }

    private boolean hasMicPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestMicPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !hasMicPermission()) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 501);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }
}
