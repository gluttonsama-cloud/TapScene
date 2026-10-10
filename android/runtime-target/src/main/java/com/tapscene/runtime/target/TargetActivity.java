package com.tapscene.runtime.target;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import org.json.JSONObject;

/** Native, animation-free synthetic screen. No widget click listeners or test input endpoint. */
public final class TargetActivity extends Activity {
    private TargetState state;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BuildConfig.DEBUG) throw new SecurityException("Debug fixture only");
        getWindow().setDecorFitsSystemWindows(false);
        state = TargetState.get(this);
        if (savedInstanceState == null && getIntent().hasExtra("sessionId")) {
            configureRound(getIntent());
        } else if (!state.isConfigured()) {
            state.reset("unconfigured", false);
        }
        setContentView(new TargetView(this, state));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Production's ordinary launcher intent must not erase the harness's prepared round.
        if (intent.hasExtra("sessionId")) {
            setIntent(intent);
            configureRound(intent);
        }
    }

    private void configureRound(Intent intent) {
        state.reset(intent.getStringExtra("sessionId"), intent.getBooleanExtra("static", false));
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (state != null) state.setFocused(hasFocus);
    }

    static final class TargetView extends View {
        private static final int[] STATE_COLORS = {
                0xff263c68, 0xff923d30, 0xff72409a, 0xffd09c2a, 0xff315786
        };
        private final TargetActivity activity;
        private final TargetState state;
        private final Paint paint = new Paint();
        private final Rect button1 = new Rect();
        private final Rect button2 = new Rect();
        private final Rect stateRegion = new Rect();
        private final Rect staticRegion = new Rect();
        private final int[] origin = new int[2];
        private Insets safeInsets = Insets.NONE;
        private int centerRadius;
        private int pressedButton;
        private int pressedPointerId = -1;
        private long pressedDownTime;

        TargetView(TargetActivity activity, TargetState state) {
            super(activity);
            this.activity = activity;
            this.state = state;
            paint.setAntiAlias(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setSoundEffectsEnabled(false);
            setHapticFeedbackEnabled(false);
            setOnApplyWindowInsetsListener((view, insets) -> {
                safeInsets = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                updateGeometry();
                return insets;
            });
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            state.attach(this);
            requestApplyInsets();
        }

        @Override protected void onDetachedFromWindow() {
            state.detach(this);
            super.onDetachedFromWindow();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            updateGeometry();
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            updateGeometry();
        }

        private void updateGeometry() {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            getLocationOnScreen(origin);
            Rect display = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
            Rect window = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
            int left = Math.max(0, safeInsets.left - origin[0]);
            int top = Math.max(0, safeInsets.top - origin[1]);
            int right = Math.min(getWidth(), display.width() - safeInsets.right - origin[0]);
            int bottom = Math.min(getHeight(), display.height() - safeInsets.bottom - origin[1]);
            int width = Math.max(1, right - left);
            int height = Math.max(1, bottom - top);
            int margin = Math.max(8, width / 20);
            int gap = Math.max(8, width / 20);
            int half = (width - 2 * margin - gap) / 2;
            int buttonTop = top + height / 12;
            int buttonBottom = top + height * 7 / 20;
            button1.set(left + margin, buttonTop, left + margin + half, buttonBottom);
            button2.set(button1.right + gap, buttonTop, right - margin, buttonBottom);
            stateRegion.set(left + margin, top + height * 9 / 20,
                    right - margin, top + height * 13 / 20);
            staticRegion.set(left + margin, top + height * 3 / 4,
                    right - margin, bottom - height / 20);
            centerRadius = Math.min(Math.round(24 * getResources().getDisplayMetrics().density),
                    Math.min(half, button1.height()) / 3);
            JSONObject geometry = TargetState.json("schema", 1, "coordinateSpace", "screen_raw_px",
                    "width", display.width(), "height", display.height(),
                    "rotation", getDisplay().getRotation(), "displayId", getDisplay().getDisplayId(),
                    "density", (double) getResources().getDisplayMetrics().density,
                    "viewWidth", getWidth(), "viewHeight", getHeight(),
                    "viewOriginX", origin[0], "viewOriginY", origin[1],
                    "windowBounds", rectJson(window, 0, 0), "fullScreen", display.equals(window),
                    "insets", TargetState.json("left", safeInsets.left, "top", safeInsets.top,
                            "right", safeInsets.right, "bottom", safeInsets.bottom),
                    "button1", rectJson(button1, origin[0], origin[1]),
                    "button2", rectJson(button2, origin[0], origin[1]),
                    "button1X", button1.centerX() + origin[0],
                    "button1Y", button1.centerY() + origin[1],
                    "button2X", button2.centerX() + origin[0],
                    "button2Y", button2.centerY() + origin[1],
                    "centerColorArgb", TargetState.CENTER_COLOR, "centerRadiusPx", centerRadius,
                    "centerStableRoiHalfSizePx", Math.max(1, centerRadius / 2),
                    "stateRegion", rectJson(stateRegion, origin[0], origin[1]),
                    "staticRegion", rectJson(staticRegion, origin[0], origin[1]),
                    "staticColorArgb", 0xffdadada, "staticPatternColorArgb", 0xff252525,
                    "drawDelayAfterUpMs", TargetState.DRAW_DELAY_MS);
            state.updateGeometry(geometry);
        }

        private static JSONObject rectJson(Rect rect, int dx, int dy) {
            return TargetState.json("left", rect.left + dx, "top", rect.top + dy,
                    "right", rect.right + dx, "bottom", rect.bottom + dy,
                    "centerX", rect.centerX() + dx, "centerY", rect.centerY() + dy);
        }

        void resetDrawing() {
            pressedButton = 0;
            pressedPointerId = -1;
            pressedDownTime = 0;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int counter = state.renderedCounter();
            canvas.drawColor(0xff14233c);
            fill(canvas, button1, 0xffb06a2c);
            fill(canvas, button2, 0xff3559a3);
            paint.setColor(TargetState.CENTER_COLOR);
            canvas.drawCircle(button1.centerX(), button1.centerY(), centerRadius, paint);
            canvas.drawCircle(button2.centerX(), button2.centerY(), centerRadius, paint);
            fill(canvas, stateRegion, STATE_COLORS[counter % STATE_COLORS.length]);
            // Eight deterministic state bits, no text, clocks, ripple, or animation.
            int cell = Math.max(1, Math.min(stateRegion.width() / 17, stateRegion.height() / 4));
            for (int index = 0; index < 8; index++) {
                paint.setColor(((counter >>> index) & 1) == 0 ? 0xff202020 : 0xffeeeeee);
                int x = stateRegion.left + (2 * index + 1) * cell;
                canvas.drawRect(x, stateRegion.centerY() - cell / 2f,
                        x + cell, stateRegion.centerY() + cell / 2f, paint);
            }
            // This lower checkerboard is invariant in every mode and for every touch.
            fill(canvas, staticRegion, 0xffdadada);
            int staticCell = Math.max(1, Math.min(staticRegion.width() / 12, staticRegion.height() / 4));
            paint.setColor(0xff252525);
            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 12; column++) {
                    if (((row + column) & 1) != 0) continue;
                    int x = staticRegion.left + column * staticCell;
                    int y = staticRegion.top + row * staticCell;
                    canvas.drawRect(x, y, x + staticCell, y + staticCell, paint);
                }
            }
            state.didDraw();
        }

        private void fill(Canvas canvas, Rect rect, int color) {
            paint.setColor(color);
            canvas.drawRect(rect, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            long receivedUptimeMs = SystemClock.uptimeMillis();
            long receivedElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos();
            int action = event.getActionMasked();
            int button = button1.contains((int) event.getX(), (int) event.getY()) ? 1
                    : button2.contains((int) event.getX(), (int) event.getY()) ? 2 : 0;
            if (action == MotionEvent.ACTION_DOWN) {
                pressedButton = button;
                pressedPointerId = event.getPointerId(0);
                pressedDownTime = event.getDownTime();
                state.record(event, button, false, receivedUptimeMs, receivedElapsedRealtimeNs);
            } else if (action == MotionEvent.ACTION_UP) {
                boolean accepted = button != 0 && button == pressedButton
                        && event.getPointerCount() == 1 && event.getPointerId(0) == pressedPointerId
                        && event.getDownTime() == pressedDownTime;
                state.record(event, button, accepted, receivedUptimeMs, receivedElapsedRealtimeNs);
                pressedButton = 0;
                pressedPointerId = -1;
            } else if (action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                pressedButton = 0;
                pressedPointerId = -1;
                state.record(event, button, false, receivedUptimeMs, receivedElapsedRealtimeNs);
            }
            // Do not call super: it can set pressed state, play a ripple, or schedule click callbacks.
            return true;
        }

        @Override public boolean performClick() {
            // Accessibility ACTION_CLICK or direct performClick is never evidence of an input event.
            return false;
        }
    }
}
