package com.nexapilot.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class LauncherActivity extends Activity {
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable card() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.rgb(17, 23, 38));
        d.setCornerRadius(dp(28));
        return d;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(7, 10, 18));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(28), dp(32), dp(28), dp(32));
        panel.setBackground(card());
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("NexaPilot");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(title);

        TextView badge = new TextView(this);
        badge.setText("v0.5.1  •  NEW UI");
        badge.setTextColor(Color.rgb(103, 231, 178));
        badge.setTextSize(15);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(0, dp(8), 0, dp(14));
        panel.addView(badge);

        TextView desc = new TextView(this);
        desc.setText("Chat  •  Live  •  Screen Vision  •  Settings");
        desc.setTextColor(Color.rgb(154, 169, 202));
        desc.setTextSize(14);
        desc.setGravity(Gravity.CENTER);
        panel.addView(desc);

        setContentView(root);

        getWindow().getDecorView().postDelayed(() -> {
            SharedPreferences prefs = getSharedPreferences("nexapilot", MODE_PRIVATE);
            boolean signedIn = prefs.getString("access_token", null) != null && prefs.getString("user_id", null) != null;
            Intent next = new Intent(this, signedIn ? NexaPilotActivity.class : MainActivity.class);
            next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(next);
            finish();
        }, 950);
    }
}
