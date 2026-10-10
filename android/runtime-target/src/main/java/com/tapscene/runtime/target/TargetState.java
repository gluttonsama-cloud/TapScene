package com.tapscene.runtime.target;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** In-process evidence for the isolated, synthetic debug target only. */
final class TargetState {
    static final String LOG_TAG = "TapSceneRuntimeTarget";
    static final int CENTER_COLOR = 0xfff7f7f7;
    static final long DRAW_DELAY_MS = 150L;
    private static TargetState instance;

    static synchronized TargetState get(Context context) {
        if (!BuildConfig.DEBUG) throw new SecurityException("Debug fixture only");
        if (instance == null) instance = new TargetState(context.getApplicationContext());
        return instance;
    }

    private final File filesDir;
    private JSONArray events = new JSONArray();
    private JSONObject geometry = new JSONObject();
    private String sessionId = "unconfigured";
    private boolean staticMode;
    private boolean ready;
    private boolean attached;
    private boolean focused;
    private String ioError = "";
    private int sequence;
    private int clickCount;
    private int renderedCounter;
    private int button1Count;
    private int button2Count;
    private long generation;
    private long drawnUptimeMs;
    private long drawnElapsedRealtimeNs;
    private TargetActivity.TargetView view;

    private TargetState(Context context) {
        filesDir = context.getFilesDir();
    }

    static void validateSession(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("A bounded synthetic sessionId is required");
        }
    }

    synchronized boolean isConfigured() {
        return !"unconfigured".equals(sessionId);
    }

    synchronized void attach(TargetActivity.TargetView currentView) {
        view = currentView;
        attached = true;
        ready = false;
    }

    synchronized void detach(TargetActivity.TargetView currentView) {
        if (view != currentView) return;
        view = null;
        attached = false;
        ready = false;
        persistSnapshot();
    }

    synchronized void setFocused(boolean value) {
        focused = value;
    }

    /** The only non-touch mutation resets a whole synthetic round, never injects a click. */
    synchronized void reset(String nonce, boolean makeStatic) {
        validateSession(nonce);
        generation++;
        sessionId = nonce;
        staticMode = makeStatic;
        sequence = 0;
        clickCount = 0;
        renderedCounter = 0;
        button1Count = 0;
        button2Count = 0;
        drawnUptimeMs = 0;
        drawnElapsedRealtimeNs = 0;
        events = new JSONArray();
        ready = false;
        ioError = "";
        try (FileOutputStream output = new FileOutputStream(new File(filesDir, "target-events.jsonl"))) {
            output.getFD().sync();
        } catch (IOException failure) {
            recordIoFailure(failure);
        }
        if (view != null) view.resetDrawing();
        writeJson("geometry.json", json("schema", 1, "sessionId", sessionId, "geometry", geometry));
        persistSnapshot();
        Log.i(LOG_TAG, json("schema", 1, "kind", "reset", "sessionId", sessionId,
                "static", staticMode, "generation", generation).toString());
    }

    synchronized void updateGeometry(JSONObject nextGeometry) {
        geometry = nextGeometry;
        writeJson("geometry.json", json("schema", 1, "sessionId", sessionId, "geometry", geometry));
        persistSnapshot();
    }

    synchronized int renderedCounter() {
        return renderedCounter;
    }

    /** Called only from TargetView's actual MotionEvent dispatch path. */
    synchronized void record(MotionEvent event, int button, boolean acceptedUp,
            long receivedUptimeMs, long receivedElapsedRealtimeNs) {
        if (acceptedUp && event.getActionMasked() != MotionEvent.ACTION_UP) {
            throw new IllegalArgumentException("Only a matching real UP changes state");
        }
        if (acceptedUp) {
            clickCount++;
            if (button == 1) button1Count++;
            if (button == 2) button2Count++;
        }
        JSONObject entry = json("schema", 1, "sessionId", sessionId,
                "sequence", ++sequence, "action", event.getActionMasked(),
                "actionName", MotionEvent.actionToString(event.getActionMasked()),
                "rawX", (double) event.getRawX(), "rawY", (double) event.getRawY(),
                "eventTimeMs", event.getEventTime(), "downTimeMs", event.getDownTime(),
                "receivedUptimeMs", receivedUptimeMs,
                "receivedElapsedRealtimeNs", receivedElapsedRealtimeNs,
                "receivedElapsedRealtimeNanos", receivedElapsedRealtimeNs,
                "button", button, "counter", clickCount, "clickCount", clickCount,
                "button1Count", button1Count, "button2Count", button2Count,
                "stateChanged", acceptedUp, "static", staticMode,
                "renderedCounter", renderedCounter);
        events.put(entry);
        try (FileOutputStream output = new FileOutputStream(new File(filesDir, "target-events.jsonl"), true)) {
            output.write((entry + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (IOException failure) {
            recordIoFailure(failure);
        }
        Log.i(LOG_TAG, entry.toString());
        persistSnapshot();
        if (acceptedUp && !staticMode && view != null) {
            final long scheduledGeneration = generation;
            final int scheduledCounter = clickCount;
            final TargetActivity.TargetView scheduledView = view;
            scheduledView.postDelayed(() -> showCounter(scheduledView, scheduledGeneration,
                    scheduledCounter), DRAW_DELAY_MS);
        }
    }

    private synchronized void showCounter(TargetActivity.TargetView expectedView,
            long expectedGeneration, int counter) {
        if (staticMode || view != expectedView || generation != expectedGeneration) return;
        renderedCounter = counter;
        expectedView.invalidate();
    }

    synchronized void didDraw() {
        drawnUptimeMs = SystemClock.uptimeMillis();
        drawnElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos();
        ready = attached && geometry.optInt("width", 0) > 0;
        persistSnapshot();
    }

    synchronized Bundle snapshot(String expectedSession) {
        validateSession(expectedSession);
        if (!sessionId.equals(expectedSession)) throw new IllegalArgumentException("Stale target session");
        Bundle result = new Bundle();
        result.putInt("schema", 1);
        result.putString("sessionId", sessionId);
        result.putBoolean("ready", ready);
        result.putBoolean("static", staticMode);
        result.putBoolean("focused", focused);
        result.putInt("width", geometry.optInt("width", 0));
        result.putInt("height", geometry.optInt("height", 0));
        for (String field : new String[]{"button1X", "button1Y", "button2X", "button2Y"}) {
            result.putInt(field, geometry.optInt(field, 0));
        }
        result.putInt("clickCount", clickCount);
        result.putInt("state", clickCount);
        result.putInt("renderedCounter", renderedCounter);
        result.putInt("centerColorArgb", CENTER_COLOR);
        result.putString("events", events.toString());
        result.putString("geometry", geometry.toString());
        result.putString("ioError", ioError);
        result.putString("json", snapshotJson().toString());
        return result;
    }

    private JSONObject snapshotJson() {
        return json("schema", 1, "sessionId", sessionId, "generation", generation,
                "ready", ready, "attached", attached, "focused", focused,
                "static", staticMode, "clickCount", clickCount, "state", clickCount,
                "renderedCounter", renderedCounter, "button1Count", button1Count,
                "button2Count", button2Count, "drawnUptimeMs", drawnUptimeMs,
                "drawnElapsedRealtimeNs", drawnElapsedRealtimeNs,
                "geometry", geometry, "events", events, "ioError", ioError);
    }

    private void persistSnapshot() {
        writeJson("snapshot.json", snapshotJson());
    }

    private void writeJson(String name, JSONObject value) {
        AtomicFile file = new AtomicFile(new File(filesDir, name));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException failure) {
            if (output != null) file.failWrite(output);
            recordIoFailure(failure);
        }
    }

    private void recordIoFailure(IOException failure) {
        ioError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        Log.e(LOG_TAG, json("schema", 1, "kind", "ioError", "sessionId", sessionId,
                "error", ioError).toString());
    }

    static JSONObject json(Object... fields) {
        JSONObject value = new JSONObject();
        try {
            for (int index = 0; index < fields.length; index += 2) {
                value.put((String) fields[index], fields[index + 1]);
            }
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Invalid fixture JSON", invalid);
        }
        return value;
    }
}
