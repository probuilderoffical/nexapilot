package com.nexapilot.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class LiveAssistantService extends Service {
    public static final String ACTION_START = "com.nexapilot.mobile.START_LIVE";
    public static final String ACTION_STOP = "com.nexapilot.mobile.STOP_LIVE";
    public static final String ACTION_MIC_ON = "com.nexapilot.mobile.MIC_ON";
    public static final String ACTION_MIC_OFF = "com.nexapilot.mobile.MIC_OFF";
    public static final String ACTION_SPEAKER_ON = "com.nexapilot.mobile.SPEAKER_ON";
    public static final String ACTION_SPEAKER_OFF = "com.nexapilot.mobile.SPEAKER_OFF";

    private static final String CHANNEL_ID = "nexapilot_live_v3";
    private static final int NOTIFICATION_ID = 91;
    private static final String SUPABASE_URL = "https://cdwcvmeruzjhjcahehqg.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_EPJE4UJ1EcsGuf2uZxvmyg_fc3QJU2F";
    private static final String MODEL = "models/gemini-3.8-live";

    private static volatile LiveAssistantService instance;
    private static volatile boolean screenVisionActive = false;

    private final OkHttpClient http = new OkHttpClient.Builder().build();
    private final ExecutorService io = Executors.newCachedThreadPool();
    private SharedPreferences prefs;
    private WebSocket socket;
    private AudioRecord recorder;
    private AudioTrack player;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private volatile boolean running;
    private volatile boolean setupComplete;
    private volatile boolean micEnabled = true;
    private volatile boolean speakerEnabled = true;

    private WindowManager windowManager;
    private View overlay;
    private TextView overlayStatus;
    private Button overlayMic;
    private Button overlaySpeaker;

    public static boolean isRunningLive() {
        LiveAssistantService s = instance;
        return s != null && s.running && s.setupComplete;
    }

    public static boolean submitText(String text) {
        LiveAssistantService s = instance;
        if (s == null || !s.running || !s.setupComplete || s.socket == null || text == null || text.trim().isEmpty()) return false;
        try {
            JSONObject realtime = new JSONObject().put("text", text.trim());
            return s.socket.send(new JSONObject().put("realtimeInput", realtime).toString());
        } catch (Exception e) { return false; }
    }

    public static void pushVideoFrame(byte[] jpeg) {
        LiveAssistantService s = instance;
        if (s == null || !s.running || !s.setupComplete || s.socket == null || jpeg == null || jpeg.length == 0) return;
        try {
            JSONObject video = new JSONObject()
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    .put("mimeType", "image/jpeg");
            s.socket.send(new JSONObject().put("realtimeInput", new JSONObject().put("video", video)).toString());
        } catch (Exception ignored) { }
    }

    public static void notifyScreenVisionChanged(boolean active) {
        screenVisionActive = active;
        LiveAssistantService s = instance;
        if (s != null) s.updateStatus(active ? "Live • screen vision on" : "Live • screen vision off");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("nexapilot", MODE_PRIVATE);
        micEnabled = prefs.getBoolean("live_mic", true);
        speakerEnabled = prefs.getBoolean("live_speaker", true);
        createNotificationChannel();
        createPlayer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopLive();
            return START_NOT_STICKY;
        }
        if (ACTION_MIC_ON.equals(action)) setMic(true);
        else if (ACTION_MIC_OFF.equals(action)) setMic(false);
        else if (ACTION_SPEAKER_ON.equals(action)) setSpeaker(true);
        else if (ACTION_SPEAKER_OFF.equals(action)) setSpeaker(false);

        startForeground(NOTIFICATION_ID, buildNotification("Starting Live…"));
        showOverlayIfAllowed();
        if (!running) startLive();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        instance = null;
        cleanup(false);
        io.shutdownNow();
        super.onDestroy();
    }

    private void startLive() {
        String accessToken = prefs.getString("access_token", null);
        if (accessToken == null) {
            updateStatus("Sign in required");
            return;
        }
        running = true;
        setupComplete = false;
        prefs.edit().putBoolean("live_enabled", true).apply();
        updateStatus("Connecting…");
        io.execute(() -> fetchTokenAndConnect(accessToken));
    }

    private void fetchTokenAndConnect(String accessToken) {
        try {
            HttpResult r = request("POST", SUPABASE_URL + "/functions/v1/live-token", "{}", accessToken);
            if (r.code < 200 || r.code >= 300) {
                running = false;
                updateStatus("Live token error " + r.code);
                return;
            }
            JSONObject json = new JSONObject(r.body);
            String token = json.optString("token", "");
            if (token.isEmpty()) {
                running = false;
                updateStatus("Live token missing");
                return;
            }
            String ws = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token="
                    + URLEncoder.encode(token, "UTF-8");
            socket = http.newWebSocket(new Request.Builder().url(ws).build(), new LiveSocketListener());
        } catch (Exception e) {
            running = false;
            updateStatus("Connect failed");
        }
    }

    private class LiveSocketListener extends WebSocketListener {
        @Override public void onOpen(WebSocket webSocket, Response response) { updateStatus("Setting up Live…"); sendSetup(webSocket); }
        @Override public void onMessage(WebSocket webSocket, String text) { try { handleServerMessage(new JSONObject(text)); } catch (Exception ignored) { } }
        @Override public void onClosed(WebSocket webSocket, int code, String reason) { setupComplete = false; if (running) updateStatus("Live disconnected"); }
        @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) { setupComplete = false; if (running) updateStatus("Live connection error"); }
    }

    private void sendSetup(WebSocket ws) {
        try {
            JSONObject setup = new JSONObject();
            setup.put("model", MODEL);
            JSONObject generation = new JSONObject()
                    .put("responseModalities", new JSONArray().put("AUDIO"))
                    .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                            .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", "Kore"))));
            setup.put("generationConfig", generation);
            setup.put("inputAudioTranscription", new JSONObject());
            setup.put("outputAudioTranscription", new JSONObject());
            setup.put("realtimeInputConfig", new JSONObject()
                    .put("automaticActivityDetection", new JSONObject()
                            .put("disabled", false)
                            .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                            .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")));

            String instructions = "You are NexaPilot, a professional live Android AI operator. Talk naturally, briefly and confidently in Roman Urdu by default. " +
                    "You receive live microphone audio, optional screen frames and phone tools. You may interrupt naturally and the user may interrupt you. " +
                    "When asked to operate the phone, say a short progress phrase, use tools, observe the screen, then continue until the goal is complete. " +
                    "Use screen pixels when Screen Vision is active and use read_screen for semantic Android UI. Prefer click_text over coordinates. " +
                    "Available tools can open apps, inspect UI, tap, type, scroll, go back/home, open URLs and search the web. " +
                    "For sending messages, deleting data, purchases/payments, security/account changes, installing software or publishing content, ask for explicit confirmation immediately before the action. " +
                    "Never ask for passwords, OTPs, recovery codes, payment card details or secret API keys. Do not bypass protected or secure Android screens.";
            setup.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", instructions))));
            setup.put("tools", new JSONArray().put(buildFunctionTools()));
            ws.send(new JSONObject().put("setup", setup).toString());
        } catch (Exception e) { updateStatus("Setup error"); }
    }

    private JSONObject buildFunctionTools() throws Exception {
        JSONArray f = new JSONArray();
        f.put(function("open_app", "Open an installed Android app by visible name.", oneStringSchema("app", "Visible app name"), arr("app")));
        f.put(function("read_screen", "Read the current Android UI accessibility tree. Use after actions and whenever exact UI state is uncertain.", emptySchema(), new JSONArray()));
        f.put(function("click_text", "Tap an on-screen element by visible text or accessibility description.", oneStringSchema("text", "Visible text or description"), arr("text")));
        f.put(function("type_text", "Type text into the focused editable field.", oneStringSchema("text", "Text to type"), arr("text")));
        f.put(function("scroll", "Scroll current screen.", oneStringSchema("direction", "up or down"), arr("direction")));
        JSONObject xy = new JSONObject().put("type", "object").put("properties", new JSONObject()
                .put("x", new JSONObject().put("type", "number"))
                .put("y", new JSONObject().put("type", "number")));
        f.put(function("tap", "Tap raw screen coordinates as fallback.", xy, new JSONArray().put("x").put("y")));
        f.put(function("back", "Press Android Back.", emptySchema(), new JSONArray()));
        f.put(function("home", "Go to Android Home.", emptySchema(), new JSONArray()));
        f.put(function("browser_search", "Open Google search for a query.", oneStringSchema("query", "Search query"), arr("query")));
        f.put(function("open_url", "Open a secure HTTPS URL.", oneStringSchema("url", "HTTPS URL"), arr("url")));
        return new JSONObject().put("functionDeclarations", f);
    }

    private JSONArray arr(String value) { return new JSONArray().put(value); }
    private JSONObject emptySchema() throws Exception { return new JSONObject().put("type", "object").put("properties", new JSONObject()); }
    private JSONObject oneStringSchema(String name, String description) throws Exception {
        return new JSONObject().put("type", "object").put("properties", new JSONObject()
                .put(name, new JSONObject().put("type", "string").put("description", description)));
    }
    private JSONObject function(String name, String description, JSONObject parameters, JSONArray required) throws Exception {
        parameters.put("required", required);
        return new JSONObject().put("name", name).put("description", description).put("parameters", parameters);
    }

    private void handleServerMessage(JSONObject msg) throws Exception {
        if (msg.has("setupComplete")) {
            setupComplete = true;
            updateStatus(screenVisionActive ? "Live • listening • vision" : "Live • listening");
            startMicStreaming();
            return;
        }
        JSONObject server = msg.optJSONObject("serverContent");
        if (server != null) {
            if (server.optBoolean("interrupted", false)) flushAudio();
            JSONObject input = server.optJSONObject("inputTranscription");
            if (input != null && !input.optString("text", "").isEmpty()) updateStatus("You: " + shorten(input.optString("text", "")));
            JSONObject output = server.optJSONObject("outputTranscription");
            if (output != null && !output.optString("text", "").isEmpty()) updateStatus("NexaPilot: " + shorten(output.optString("text", "")));
            JSONObject turn = server.optJSONObject("modelTurn");
            if (turn != null) {
                JSONArray parts = turn.optJSONArray("parts");
                if (parts != null) for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    JSONObject inline = p == null ? null : p.optJSONObject("inlineData");
                    if (inline != null && inline.optString("mimeType", "").startsWith("audio/pcm")) {
                        playAudio(Base64.decode(inline.optString("data", ""), Base64.DEFAULT));
                    }
                }
            }
        }
        JSONObject toolCall = msg.optJSONObject("toolCall");
        if (toolCall != null) executeToolCalls(toolCall.optJSONArray("functionCalls"));
    }

    private void executeToolCalls(JSONArray calls) {
        if (calls == null || socket == null) return;
        io.execute(() -> {
            JSONArray responses = new JSONArray();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call == null) continue;
                String id = call.optString("id", "");
                String name = call.optString("name", "");
                JSONObject args = call.optJSONObject("args");
                if (args == null) args = new JSONObject();
                JSONObject result = runTool(name, args);
                try { responses.put(new JSONObject().put("id", id).put("name", name).put("response", result)); }
                catch (Exception ignored) { }
            }
            try { socket.send(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", responses)).toString()); }
            catch (Exception ignored) { }
        });
    }

    private JSONObject runTool(String name, JSONObject args) {
        JSONObject out = new JSONObject();
        try {
            updateStatus("Working • " + name.replace('_', ' '));
            switch (name) {
                case "open_app": out.put("ok", openAppByLabel(args.optString("app", ""))); break;
                case "read_screen": return NexaAccessibilityService.readScreen().put("screen_vision", screenVisionActive);
                case "click_text": out.put("ok", NexaAccessibilityService.clickText(args.optString("text", ""))); break;
                case "type_text": out.put("ok", NexaAccessibilityService.typeIntoFocusedField(args.optString("text", ""))); break;
                case "scroll": out.put("ok", NexaAccessibilityService.scroll(args.optString("direction", "down"))); break;
                case "tap": out.put("ok", NexaAccessibilityService.tap((float)args.optDouble("x",0), (float)args.optDouble("y",0))); break;
                case "back": out.put("ok", NexaAccessibilityService.goBack()); break;
                case "home": out.put("ok", NexaAccessibilityService.goHome()); break;
                case "browser_search": out.put("ok", openSearch(args.optString("query", ""))); break;
                case "open_url": out.put("ok", openUrl(args.optString("url", ""))); break;
                default: out.put("ok", false).put("error", "unsupported_tool");
            }
        } catch (Exception e) {
            try { out.put("ok", false).put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
        }
        return out;
    }

    private void startMicStreaming() {
        if (recorder != null || !running) return;
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(4096, min * 2));
        try {
            if (AcousticEchoCanceler.isAvailable()) { echoCanceler = AcousticEchoCanceler.create(recorder.getAudioSessionId()); if (echoCanceler != null) echoCanceler.setEnabled(true); }
            if (NoiseSuppressor.isAvailable()) { noiseSuppressor = NoiseSuppressor.create(recorder.getAudioSessionId()); if (noiseSuppressor != null) noiseSuppressor.setEnabled(true); }
        } catch (Exception ignored) { }
        try { recorder.startRecording(); } catch (Exception e) { updateStatus("Mic start failed"); return; }
        io.execute(() -> {
            byte[] buf = new byte[2048];
            while (running && recorder != null) {
                int n = recorder.read(buf, 0, buf.length);
                if (n > 0 && micEnabled && setupComplete && socket != null) {
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    try {
                        JSONObject audio = new JSONObject().put("data", Base64.encodeToString(chunk, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000");
                        socket.send(new JSONObject().put("realtimeInput", new JSONObject().put("audio", audio)).toString());
                    } catch (Exception ignored) { }
                }
            }
        });
    }

    private void createPlayer() {
        int min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFormat fmt = new AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        player = new AudioTrack(attrs, fmt, Math.max(8192, min * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        try { player.play(); } catch (Exception ignored) { }
    }

    private void playAudio(byte[] pcm) { if (speakerEnabled && player != null && pcm != null && pcm.length > 0) player.write(pcm, 0, pcm.length); }
    private void flushAudio() { try { if (player != null) { player.pause(); player.flush(); player.play(); } } catch (Exception ignored) { } }

    private void setMic(boolean enabled) {
        micEnabled = enabled;
        prefs.edit().putBoolean("live_mic", enabled).apply();
        if (!enabled && socket != null && setupComplete) try { socket.send(new JSONObject().put("realtimeInput", new JSONObject().put("audioStreamEnd", true)).toString()); } catch (Exception ignored) { }
        updateOverlayButtons();
    }
    private void setSpeaker(boolean enabled) { speakerEnabled = enabled; prefs.edit().putBoolean("live_speaker", enabled).apply(); if (!enabled) flushAudio(); updateOverlayButtons(); }

    private boolean openAppByLabel(String requested) {
        if (requested == null || requested.trim().isEmpty()) return false;
        String want = normalize(requested);
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(q, 0);
        ResolveInfo best = null; int bestScore = -1;
        for (ResolveInfo info : apps) {
            int score = matchScore(want, normalize(String.valueOf(info.loadLabel(pm))));
            if (score > bestScore) { bestScore = score; best = info; }
        }
        if (best != null && bestScore >= 60) {
            Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
            if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); return true; }
        }
        return false;
    }
    private int matchScore(String a, String b) {
        if (a.equals(b)) return 100; if (a.startsWith(b) || b.startsWith(a)) return 90; if (a.contains(b) || b.contains(a)) return 80;
        int common = 0; for (String x : a.split(" ")) for (String y : b.split(" ")) if (!x.isEmpty() && x.equals(y)) common++;
        return common > 0 ? 60 + Math.min(15, common * 5) : -1;
    }
    private String normalize(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim(); }

    private boolean openSearch(String q) {
        try { if (q == null || q.trim().isEmpty()) return false; Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return true; }
        catch (Exception e) { return false; }
    }
    private boolean openUrl(String url) {
        try { if (url == null || !url.startsWith("https://")) return false; Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return true; }
        catch (Exception e) { return false; }
    }

    private void stopLive() { cleanup(true); }
    private void cleanup(boolean stopSelfToo) {
        running = false; setupComplete = false;
        if (prefs != null) prefs.edit().putBoolean("live_enabled", false).apply();
        try { if (socket != null) socket.close(1000, "stopped"); } catch (Exception ignored) { } socket = null;
        try { if (recorder != null) { recorder.stop(); recorder.release(); } } catch (Exception ignored) { } recorder = null;
        try { if (echoCanceler != null) echoCanceler.release(); } catch (Exception ignored) { } echoCanceler = null;
        try { if (noiseSuppressor != null) noiseSuppressor.release(); } catch (Exception ignored) { } noiseSuppressor = null;
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) { } player = null;
        removeOverlay();
        if (stopSelfToo) { stopForeground(true); stopSelf(); }
    }

    private void showOverlayIfAllowed() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        if (overlay != null) return;
        runOnMain(() -> {
            try {
                windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
                LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(18,12,18,12); box.setBackgroundColor(Color.argb(232,15,21,36));
                overlayStatus = new TextView(this); overlayStatus.setTextColor(Color.WHITE); overlayStatus.setTextSize(12); overlayStatus.setText("NexaPilot Live"); box.addView(overlayStatus);
                LinearLayout row = new LinearLayout(this); overlayMic = new Button(this); overlaySpeaker = new Button(this); Button stop = new Button(this);
                overlayMic.setOnClickListener(v -> setMic(!micEnabled)); overlaySpeaker.setOnClickListener(v -> setSpeaker(!speakerEnabled)); stop.setText("■"); stop.setOnClickListener(v -> stopLive());
                row.addView(overlayMic); row.addView(overlaySpeaker); row.addView(stop); box.addView(row);
                int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.END; lp.x = 14; lp.y = 100;
                overlay = box; windowManager.addView(overlay, lp); updateOverlayButtons();
            } catch (Exception ignored) { }
        });
    }
    private void removeOverlay() { runOnMain(() -> { try { if (windowManager != null && overlay != null) windowManager.removeView(overlay); } catch (Exception ignored) { } overlay = null; }); }
    private void updateOverlayButtons() { runOnMain(() -> { if (overlayMic != null) overlayMic.setText(micEnabled ? "🎤" : "🔇"); if (overlaySpeaker != null) overlaySpeaker.setText(speakerEnabled ? "🔊" : "🔈×"); }); }
    private void updateStatus(String text) {
        runOnMain(() -> {
            if (overlayStatus != null) overlayStatus.setText(shorten(text));
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && running) nm.notify(NOTIFICATION_ID, buildNotification(text));
        });
    }
    private String shorten(String s) { if (s == null) return ""; s = s.trim().replaceAll("\\s+", " "); return s.length() > 72 ? s.substring(0,69) + "…" : s; }
    private void runOnMain(Runnable r) { new android.os.Handler(getMainLooper()).post(r); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "NexaPilot Live", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Live AI conversation and phone control");
            NotificationManager nm = getSystemService(NotificationManager.class); if (nm != null) nm.createNotificationChannel(c);
        }
    }
    private Notification buildNotification(String text) {
        Intent open = new Intent(this, NexaPilotActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("NexaPilot Live").setContentText(shorten(text)).setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).setContentIntent(pi).build();
    }

    private HttpResult request(String method, String urlValue, String body, String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(urlValue).openConnection();
        conn.setRequestMethod(method); conn.setConnectTimeout(15000); conn.setReadTimeout(30000); conn.setRequestProperty("apikey", SUPABASE_KEY); conn.setRequestProperty("Content-Type", "application/json");
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) { conn.setDoOutput(true); try (OutputStream out = conn.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); } }
        int code = conn.getResponseCode(); InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream(); StringBuilder s = new StringBuilder();
        if (stream != null) try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { String line; while ((line = br.readLine()) != null) s.append(line); }
        conn.disconnect(); return new HttpResult(code, s.toString());
    }
    private static class HttpResult { final int code; final String body; HttpResult(int code, String body) { this.code = code; this.body = body; } }
}
