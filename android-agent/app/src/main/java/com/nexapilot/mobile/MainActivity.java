package com.nexapilot.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String SUPABASE_URL = "https://cdwcvmeruzjhjcahehqg.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_EPJE4UJ1EcsGuf2uZxvmyg_fc3QJU2F";

    private SharedPreferences prefs;
    private EditText email;
    private EditText password;
    private TextView status;

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("nexapilot", MODE_PRIVATE);

        // Important migration path: older versions may reopen this Activity after an update.
        // Signed-in users must always be forwarded to the new Chat / Live / Settings shell.
        if (prefs.getString("access_token", null) != null && prefs.getString("user_id", null) != null) {
            openNewUi();
            return;
        }

        buildAuthUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs != null && prefs.getString("access_token", null) != null && prefs.getString("user_id", null) != null) {
            openNewUi();
        }
    }

    private void openNewUi() {
        Intent i = new Intent(this, NexaPilotActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackground(bg(color, 18));
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(52)));
        return b;
    }

    private EditText input(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(112, 126, 157));
        e.setTextColor(Color.WHITE);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setPadding(dp(16), 0, dp(16), 0);
        e.setBackground(bg(Color.rgb(20, 28, 46), 18));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.setMargins(0, dp(7), 0, dp(7));
        e.setLayoutParams(p);
        return e;
    }

    private void buildAuthUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 10, 18));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(24), dp(34), dp(24), dp(34));
        scroll.addView(root);

        TextView brand = text("NexaPilot", 34, Color.WHITE);
        brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(brand);

        TextView version = text("Mobile Agent  •  v0.5.1", 14, Color.rgb(107, 215, 177));
        version.setPadding(0, dp(4), 0, dp(28));
        root.addView(version);

        TextView heading = text("Sign in", 24, Color.WHITE);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(heading);

        TextView sub = text("Chat, Live voice aur phone control ek hi NexaPilot app mein.", 14, Color.rgb(148, 163, 195));
        sub.setPadding(0, dp(5), 0, dp(18));
        root.addView(sub);

        email = input("Email", false);
        password = input("Password", true);
        root.addView(email);
        root.addView(password);

        Button signIn = button("Sign in", Color.rgb(82, 73, 220));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(52));
        sp.setMargins(0, dp(8), 0, dp(7));
        signIn.setLayoutParams(sp);
        signIn.setOnClickListener(v -> authenticate(false));
        root.addView(signIn);

        Button signUp = button("Create account", Color.rgb(32, 43, 64));
        signUp.setOnClickListener(v -> authenticate(true));
        root.addView(signUp);

        status = text("", 13, Color.rgb(182, 194, 220));
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);

        setContentView(scroll);
    }

    private void authenticate(boolean signUp) {
        String e = email.getText().toString().trim();
        String p = password.getText().toString();
        if (e.isEmpty() || p.length() < 6) {
            status.setText("Email aur kam az kam 6 characters ka password enter karo.");
            return;
        }
        status.setText(signUp ? "Account bana raha hoon…" : "Sign in ho raha hai…");

        new Thread(() -> {
            try {
                String endpoint = signUp ? "/auth/v1/signup" : "/auth/v1/token?grant_type=password";
                JSONObject body = new JSONObject().put("email", e).put("password", p);
                HttpResult r = request("POST", SUPABASE_URL + endpoint, body.toString());
                JSONObject json = new JSONObject(r.body);

                if (r.code < 200 || r.code >= 300) {
                    String msg = json.optString("msg", json.optString("error_description", json.optString("message", "Authentication failed")));
                    runOnUiThread(() -> status.setText(msg));
                    return;
                }

                String token = json.optString("access_token", null);
                JSONObject user = json.optJSONObject("user");
                if (token == null || user == null) {
                    runOnUiThread(() -> status.setText("Account created. Email confirmation enabled ho to email confirm karke Sign in karo."));
                    return;
                }

                String uid = user.optString("id", null);
                prefs.edit().putString("access_token", token).putString("user_id", uid).apply();
                runOnUiThread(this::openNewUi);
            } catch (Exception ex) {
                runOnUiThread(() -> status.setText("Auth error: " + ex.getMessage()));
            }
        }).start();
    }

    private HttpResult request(String method, String urlValue, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlValue).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("apikey", SUPABASE_KEY);
        conn.setRequestProperty("Authorization", "Bearer " + SUPABASE_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder result = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) result.append(line);
            }
        }
        conn.disconnect();
        return new HttpResult(code, result.toString());
    }

    private static class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body; }
    }
}
