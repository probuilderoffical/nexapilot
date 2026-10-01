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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LiveAssistantService extends Service implements RecognitionListener, TextToSpeech.OnInitListener {
    public static final String ACTION_START = "com.nexapilot.mobile.START_LIVE";
    public static final String ACTION_STOP = "com.nexapilot.mobile.STOP_LIVE";
    public static final String ACTION_MIC_ON = "com.nexapilot.mobile.MIC_ON";
    public static final String ACTION_MIC_OFF = "com.nexapilot.mobile.MIC_OFF";
    public static final String ACTION_SPEAKER_ON = "com.nexapilot.mobile.SPEAKER_ON";
    public static final String ACTION_SPEAKER_OFF = "com.nexapilot.mobile.SPEAKER_OFF";

    private static final String CHANNEL_ID = "nexapilot_live";
    private static final int NOTIFICATION_ID = 91;
    private static final String SUPABASE_URL = "https://cdwcvmeruzjhjcahehqg.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_EPJE4UJ1EcsGuf2uZxvmyg_fc3QJU2F";

    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private TextToSpeech tts;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean micEnabled = true;
    private boolean speakerEnabled = true;
    private boolean listening = false;
    private boolean speaking = false;
    private boolean stopped = false;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("nexapilot", MODE_PRIVATE);
        micEnabled = prefs.getBoolean("live_mic", true);
        speakerEnabled = prefs.getBoolean("live_speaker", true);
        tts = new TextToSpeech(this, this);
        createRecognizer();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopped = true;
            stopListening();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_MIC_ON.equals(action)) {
            micEnabled = true;
            prefs.edit().putBoolean("live_mic", true).apply();
        } else if (ACTION_MIC_OFF.equals(action)) {
            micEnabled = false;
            prefs.edit().putBoolean("live_mic", false).apply();
            stopListening();
        } else if (ACTION_SPEAKER_ON.equals(action)) {
            speakerEnabled = true;
            prefs.edit().putBoolean("live_speaker", true).apply();
        } else if (ACTION_SPEAKER_OFF.equals(action)) {
            speakerEnabled = false;
            prefs.edit().putBoolean("live_speaker", false).apply();
            if (tts != null) tts.stop();
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        stopped = false;
        if (micEnabled && !speaking) scheduleListen(250);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        stopListening();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
    }

    private void scheduleListen(long delayMs) {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::startListening, delayMs);
    }

    private void startListening() {
        if (stopped || !micEnabled || speaking || recognizer == null || listening) return;
        try {
            listening = true;
            recognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            listening = false;
            scheduleListen(1200);
        }
    }

    private void stopListening() {
        listening = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
        }
    }

    @Override public void onReadyForSpeech(android.os.Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { listening = false; }

    @Override
    public void onError(int error) {
        listening = false;
        if (!stopped && micEnabled && !speaking) {
            long delay = (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) ? 1500 : 650;
            scheduleListen(delay);
        }
    }

    @Override
    public void onResults(android.os.Bundle results) {
        listening = false;
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String heard = matches.get(0).trim();
            if (!heard.isEmpty()) processHeardCommand(heard);
            else scheduleListen(400);
        } else {
            scheduleListen(400);
        }
    }

    @Override public void onPartialResults(android.os.Bundle partialResults) { }
    @Override public void onEvent(int eventType, android.os.Bundle params) { }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            tts.setLanguage(Locale.getDefault());
            tts.setSpeechRate(1.0f);
        }
    }

    private void processHeardCommand(String raw) {
        String command = normalizeWakePhrase(raw);
        if (command.isEmpty()) {
            scheduleListen(350);
            return;
        }

        String lower = command.toLowerCase(Locale.ROOT);

        if (containsAny(lower, "mic off", "microphone off", "sunna band", "listening off")) {
            say("Mic off kar raha hoon.");
            micEnabled = false;
            prefs.edit().putBoolean("live_mic", false).apply();
            return;
        }
        if (containsAny(lower, "speaker off", "awaz band", "voice off")) {
            speakerEnabled = false;
            prefs.edit().putBoolean("live_speaker", false).apply();
            if (tts != null) tts.stop();
            scheduleListen(300);
            return;
        }
        if (containsAny(lower, "speaker on", "awaz on", "voice on")) {
            speakerEnabled = true;
            prefs.edit().putBoolean("live_speaker", true).apply();
            say("Speaker on hai.");
            return;
        }
        if (containsAny(lower, "go home", "home jao", "home kholo")) {
            say("Home ja raha hoon.");
            NexaAccessibilityService.goHome();
            return;
        }
        if (containsAny(lower, "go back", "wapas jao", "back jao")) {
            say("Wapas ja raha hoon.");
            NexaAccessibilityService.goBack();
            return;
        }

        String appName = extractOpenAppName(command);
        if (appName != null) {
            boolean opened = openAppByLabel(appName);
            if (opened) say(appName + " khol raha hoon.");
            else say(appName + " mujhe installed apps mein nahi mila.");
            return;
        }

        String query = extractSearchQuery(command);
        if (query != null && !query.isEmpty()) {
            say("Google par search kar raha hoon.");
            openGoogleSearch(query);
            return;
        }

        say("Samajh gaya, soch raha hoon.");
        callAiAndExecute(command);
    }

    private String normalizeWakePhrase(String raw) {
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        String[] wakePrefixes = {"nexapilot", "nexa pilot", "nexta pilot", "nexa", "nexta"};
        for (String prefix : wakePrefixes) {
            if (lower.startsWith(prefix)) {
                text = text.substring(Math.min(prefix.length(), text.length())).trim();
                text = text.replaceFirst("^[,.:;\\-]+", "").trim();
                break;
            }
        }
        return text;
    }

    private String extractOpenAppName(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        String[] markers = {"open ", "kholo ", "khol do ", "kholna ", "launch "};
        for (String marker : markers) {
            int idx = lower.indexOf(marker);
            if (idx >= 0) {
                String name = command.substring(idx + marker.length()).trim();
                name = name.replaceAll("(?i)\\s+(app|application)$", "").trim();
                if (!name.isEmpty()) return name;
            }
        }
        return null;
    }

    private String extractSearchQuery(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        String[] markers = {"google pe search karo ", "google par search karo ", "search karo ", "search for ", "research karo ", "research on "};
        for (String marker : markers) {
            int idx = lower.indexOf(marker);
            if (idx >= 0) {
                String q = command.substring(idx + marker.length()).trim();
                if (!q.isEmpty()) return q;
            }
        }
        return null;
    }

    private boolean openAppByLabel(String requested) {
        String want = normalizeName(requested);
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN, null);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);

        ResolveInfo best = null;
        int bestScore = -1;
        for (ResolveInfo info : apps) {
            CharSequence labelCs = info.loadLabel(pm);
            String label = labelCs == null ? "" : labelCs.toString();
            String normalized = normalizeName(label);
            int score = matchScore(want, normalized);
            if (score > bestScore) {
                bestScore = score;
                best = info;
            }
        }

        if (best != null && bestScore >= 60) {
            Intent intent = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            }
        }

        // Package-id fallback if Gemini ever passes a package instead of a label.
        Intent byPackage = pm.getLaunchIntentForPackage(requested.trim());
        if (byPackage != null) {
            byPackage.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(byPackage);
            return true;
        }
        return false;
    }

    private int matchScore(String want, String label) {
        if (want.equals(label)) return 100;
        if (label.startsWith(want) || want.startsWith(label)) return 90;
        if (label.contains(want) || want.contains(label)) return 80;
        String[] a = want.split(" ");
        String[] b = label.split(" ");
        int common = 0;
        for (String x : a) for (String y : b) if (!x.isEmpty() && x.equals(y)) common++;
        if (common > 0) return 60 + Math.min(15, common * 5);
        return -1;
    }

    private String normalizeName(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void openGoogleSearch(String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + encoded));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            say("Search open nahi ho saka.");
        }
    }

    private void callAiAndExecute(String command) {
        final String accessToken = prefs.getString("access_token", null);
        if (accessToken == null) {
            say("Pehle NexaPilot app mein sign in karo.");
            return;
        }

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("message", command).put("target", "android");
                HttpResult result = request("POST", SUPABASE_URL + "/functions/v1/ai-command", body.toString(), accessToken);
                if (result.code < 200 || result.code >= 300) {
                    sayOnMain("AI abhi available nahi hai. Dobara try karo.");
                    return;
                }
                JSONObject response = new JSONObject(result.body);
                JSONObject plan = response.optJSONObject("plan");
                if (plan == null) {
                    sayOnMain("Mujhe usable plan nahi mila.");
                    return;
                }
                String assistantMessage = plan.optString("assistant_message", "Theek hai.");
                handler.post(() -> executePlan(plan, assistantMessage));
            } catch (Exception e) {
                sayOnMain("Command process nahi ho saka.");
            }
        }).start();
    }

    private void executePlan(JSONObject plan, String assistantMessage) {
        if (plan.optBoolean("needs_clarification", false)) {
            String q = plan.optString("clarification_question", assistantMessage);
            say(q);
            return;
        }

        JSONArray actions = plan.optJSONArray("actions");
        if (actions == null || actions.length() == 0) {
            say(assistantMessage);
            return;
        }

        boolean didSomething = false;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null || action.optBoolean("requires_approval", false)) continue;
            JSONObject args = action.optJSONObject("args");
            if (args == null) args = new JSONObject();
            String tool = action.optString("tool", "");

            try {
                switch (tool) {
                    case "open_app":
                        didSomething |= openAppByLabel(args.optString("app", args.optString("package", "")));
                        break;
                    case "open_url":
                        String url = args.optString("url", "");
                        if (!url.isEmpty()) {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            didSomething = true;
                        }
                        break;
                    case "browser_search":
                        String q = args.optString("query", args.optString("text", ""));
                        if (!q.isEmpty()) {
                            openGoogleSearch(q);
                            didSomething = true;
                        }
                        break;
                    case "press_key":
                        String key = args.optString("key", "").toLowerCase(Locale.ROOT);
                        if ("home".equals(key)) didSomething |= NexaAccessibilityService.goHome();
                        else if ("back".equals(key)) didSomething |= NexaAccessibilityService.goBack();
                        break;
                    case "type_text":
                        didSomething |= NexaAccessibilityService.typeIntoFocusedField(args.optString("text", ""));
                        break;
                }
            } catch (Exception ignored) { }
        }

        say(didSomething ? assistantMessage : "Plan bana hai, lekin is action ka executor abhi add hona baqi hai.");
    }

    private void sayOnMain(String text) {
        handler.post(() -> say(text));
    }

    private void say(String text) {
        stopListening();
        if (!speakerEnabled || tts == null || text == null || text.trim().isEmpty()) {
            speaking = false;
            if (micEnabled && !stopped) scheduleListen(350);
            return;
        }
        speaking = true;
        String utteranceId = "nexa_" + System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        }
        // A lightweight delay keeps the recognizer from hearing NexaPilot's own first words.
        long delay = Math.max(900, Math.min(5500, text.length() * 55L));
        handler.postDelayed(() -> {
            speaking = false;
            if (micEnabled && !stopped) scheduleListen(250);
        }, delay);
    }

    private boolean containsAny(String value, String... options) {
        for (String option : options) if (value.contains(option)) return true;
        return false;
    }

    private HttpResult request(String method, String urlValue, String body, String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlValue).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("apikey", SUPABASE_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "NexaPilot Live", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps NexaPilot listening while Live Mode is on");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("NexaPilot Live is active")
                .setContentText(micEnabled ? "Listening for your commands" : "Live Mode on • microphone off")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private static class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
