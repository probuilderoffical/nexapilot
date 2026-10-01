package com.nexapilot.mobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public class NexaAccessibilityService extends AccessibilityService {
    private static NexaAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean goHome() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public static boolean goBack() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public static boolean openRecents() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    public static boolean tap(float x, float y) {
        if (instance == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 80);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return instance.dispatchGesture(gesture, null, null);
    }

    public static boolean swipe(float startX, float startY, float endX, float endY, long durationMs) {
        if (instance == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, Math.max(120, durationMs));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return instance.dispatchGesture(gesture, null, null);
    }

    public static boolean typeIntoFocusedField(String text) {
        if (instance == null) return false;
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    public static boolean clickText(String query) {
        if (instance == null || query == null || query.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        if (root == null) return false;
        String want = normalize(query);
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        AccessibilityNodeInfo partial = null;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = node.getText() == null ? "" : node.getText().toString();
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
            String combined = normalize((text + " " + desc).trim());
            if (!combined.isEmpty()) {
                if (combined.equals(want) || combined.startsWith(want) || want.startsWith(combined)) {
                    return clickNodeOrParent(node);
                }
                if (partial == null && (combined.contains(want) || want.contains(combined))) partial = node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return partial != null && clickNodeOrParent(partial);
    }

    private static boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds.width() > 0 && bounds.height() > 0 && tap(bounds.centerX(), bounds.centerY());
    }

    public static boolean scroll(String direction) {
        if (instance == null) return false;
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        if (root == null) return false;
        int action = "up".equalsIgnoreCase(direction)
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isScrollable() && node.performAction(action)) return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return false;
    }

    public static JSONObject readScreen() {
        JSONObject result = new JSONObject();
        JSONArray elements = new JSONArray();
        try {
            result.put("accessibility_running", instance != null);
            if (instance == null) {
                result.put("elements", elements);
                return result;
            }
            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
            if (root == null) {
                result.put("elements", elements);
                return result;
            }
            CharSequence pkg = root.getPackageName();
            result.put("package", pkg == null ? "" : pkg.toString());
            Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            int count = 0;
            while (!queue.isEmpty() && count < 120) {
                AccessibilityNodeInfo node = queue.removeFirst();
                String text = node.getText() == null ? "" : node.getText().toString().trim();
                String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
                if (!text.isEmpty() || !desc.isEmpty() || node.isClickable() || node.isEditable()) {
                    JSONObject item = new JSONObject();
                    item.put("text", text);
                    item.put("description", desc);
                    item.put("clickable", node.isClickable());
                    item.put("editable", node.isEditable());
                    item.put("scrollable", node.isScrollable());
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    item.put("bounds", bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom);
                    elements.put(item);
                    count++;
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.addLast(child);
                }
            }
            result.put("elements", elements);
        } catch (Exception ignored) { }
        return result;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
