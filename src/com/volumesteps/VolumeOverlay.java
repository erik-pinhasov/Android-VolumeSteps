package com.volumesteps;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class VolumeOverlay {
    private static final String TAG = "VolumeOverlay";

    private static final int COL_ICON_TINT = 0xFFE6E6FF;
    private static final int FILL_COLOR = 0xFF5E9CFF;
    private static final int LABEL_COLOR = 0xFFD5D5F0;

    private final Context context;
    private final WindowManager wm;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View overlayView;
    private View trackFill;
    private TextView stepLabel;
    private boolean isShowing = false, isDragging = false, expanded = false;
    private Runnable hideRunnable, collapseRunnable;
    private int currentStep = 0, totalSteps = 200, trackHeight = 0;

    private VolumeLockController lockController;

    // Columns shown in the expanded panel (one per UNLOCKED managed stream).
    private final List<Column> columns = new ArrayList<>();
    private static class Column {
        int stream; boolean isMedia; int max;
        FrameLayout trackFrame; View fill; TextView valueLabel; int trackH;
    }

    public interface OnStepSeekListener { void onStepSeek(int step); }
    private OnStepSeekListener seekListener;
    public void setOnStepSeekListener(OnStepSeekListener l) { seekListener = l; }
    public void setLockController(VolumeLockController l) { lockController = l; }

    public VolumeOverlay(Context ctx) {
        context = ctx;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    public void show(int step, int total) {
        currentStep = step; totalSteps = total;
        handler.post(new Runnable() { @Override public void run() {
            try { showInternal(); } catch (Exception e) { Log.e(TAG, "show", e); }
        }});
    }

    private void showInternal() {
        if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
        if (!isShowing) {
            rebuild();
        } else if (expanded) {
            updateMediaColumn();
        } else {
            if (!isDragging) { updateFill(); if (stepLabel != null) stepLabel.setText(String.valueOf(currentStep)); }
        }
        if (expanded) { if (!isDragging) scheduleCollapse(); }
        else if (!isDragging) scheduleHide();
    }

    /** Tear down and re-add the window for the current (collapsed/expanded) mode. */
    private void rebuild() {
        if (isShowing && overlayView != null) {
            try { wm.removeView(overlayView); } catch (Exception e) {}
            overlayView = null; isShowing = false;
        }
        trackFill = null; stepLabel = null; columns.clear();
        overlayView = expanded ? buildExpandedView() : buildCollapsedView();
        // Tap anywhere off the bar closes it (collapsed) or collapses it (expanded).
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) { handleOutsideTap(); return true; }
                return false;
            }
        });
        try { wm.addView(overlayView, buildParams()); isShowing = true; }
        catch (Exception e) { Log.e(TAG, "addView", e); overlayView = null; return; }
        if (!expanded) { updateFill(); if (stepLabel != null) stepLabel.setText(String.valueOf(currentStep)); }
    }

    private void handleOutsideTap() {
        if (expanded) {
            expanded = false;
            if (collapseRunnable != null) handler.removeCallbacks(collapseRunnable);
            rebuild();
            scheduleHide();
        } else {
            hide();
        }
    }

    private void toggleExpanded() {
        expanded = !expanded;
        if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
        if (collapseRunnable != null) handler.removeCallbacks(collapseRunnable);
        rebuild();
        if (expanded) scheduleCollapse(); else scheduleHide();
    }

    // ---- Collapsed view: single media bar + expand toggle ----

    private View buildCollapsedView() {
        float d = density();
        int barH = computeBarHeight();
        int barW = (int)(54*d);

        FrameLayout root = new FrameLayout(context);
        LinearLayout container = panel(d);

        ImageView expandBtn = new ImageView(context);
        expandBtn.setImageResource(R.drawable.ic_tune);
        expandBtn.setColorFilter(COL_ICON_TINT, PorterDuff.Mode.SRC_IN);
        int ip = (int)(4*d); expandBtn.setPadding(ip, ip, ip, ip);
        expandBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleExpanded(); } });
        container.addView(expandBtn, new LinearLayout.LayoutParams((int)(34*d), (int)(34*d)));

        stepLabel = new TextView(context);
        stepLabel.setTextColor(LABEL_COLOR);
        stepLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        stepLabel.setGravity(Gravity.CENTER);
        container.addView(stepLabel, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(24*d)));

        final FrameLayout trackFrame = new FrameLayout(context);
        // Weighted track fills the leftover space exactly, so the rounded bottom is never clipped.
        LinearLayout.LayoutParams tfp = new LinearLayout.LayoutParams((int)(34*d), 0, 1f);
        tfp.gravity = Gravity.CENTER_HORIZONTAL; tfp.topMargin = (int)(4*d); tfp.bottomMargin = (int)(2*d);

        trackFrame.addView(trackBg(d), new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        trackFill = trackFillView(d);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 0);
        fp.gravity = Gravity.BOTTOM;
        trackFrame.addView(trackFill, fp);
        container.addView(trackFrame, tfp);
        // Measure the track after layout, then size the fill.
        trackFrame.post(new Runnable() { @Override public void run() {
            trackHeight = trackFrame.getHeight(); updateFill();
        }});

        trackFrame.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        isDragging = true; if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
                        handleMediaTouch(v, e.getY()); return true;
                    case MotionEvent.ACTION_MOVE: handleMediaTouch(v, e.getY()); return true;
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                        isDragging = false; scheduleHide(); return true;
                }
                return false;
            }
        });

        root.addView(container, new FrameLayout.LayoutParams(barW, barH));
        return root;
    }

    private void handleMediaTouch(View v, float y) {
        int h = v.getHeight(); if (h <= 0) return;
        float frac = Math.max(0f, Math.min(1f, 1f - y/h));
        int step = Math.round(frac * totalSteps);
        currentStep = step; updateFill(); if (stepLabel != null) stepLabel.setText(String.valueOf(step));
        if (seekListener != null) seekListener.onStepSeek(step);
    }

    private void updateFill() {
        if (trackFill == null || trackHeight <= 0) return;
        float frac = totalSteps > 0 ? (float)currentStep / totalSteps : 0;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) trackFill.getLayoutParams();
        p.height = (int)(frac * trackHeight); trackFill.setLayoutParams(p);
    }

    // ---- Expanded view: side-by-side sliders for UNLOCKED streams only ----

    private View buildExpandedView() {
        float d = density();
        int barH = computeBarHeight();
        int colW = (int)(50*d), pad = (int)(8*d);

        FrameLayout root = new FrameLayout(context);
        LinearLayout container = panel(d);

        ImageView collapseBtn = new ImageView(context);
        collapseBtn.setImageResource(R.drawable.ic_tune);
        collapseBtn.setColorFilter(COL_ICON_TINT, PorterDuff.Mode.SRC_IN);
        int ip = (int)(5*d); collapseBtn.setPadding(ip, ip, ip, ip);
        collapseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleExpanded(); } });
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams((int)(36*d), (int)(36*d));
        cbp.bottomMargin = (int)(4*d);
        container.addView(collapseBtn, cbp);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(row, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f));

        for (int stream : VolumeLockController.MANAGED) {
            if (lockController != null && lockController.isLocked(stream)) continue; // hide locked streams
            row.addView(buildColumn(stream, d, colW), new LinearLayout.LayoutParams(
                colW, LinearLayout.LayoutParams.MATCH_PARENT));
        }

        int cols = Math.max(columns.size(), 1);
        int totalW = colW * cols + pad*2 + (int)(8*d);
        root.addView(container, new FrameLayout.LayoutParams(totalW, barH));
        return root;
    }

    private View buildColumn(final int stream, float d, int colW) {
        final Column col = new Column();
        col.stream = stream;
        col.isMedia = stream == AudioManager.STREAM_MUSIC;
        col.max = col.isMedia ? totalSteps : audioManager.getStreamMaxVolume(stream);

        LinearLayout colLayout = new LinearLayout(context);
        colLayout.setOrientation(LinearLayout.VERTICAL);
        colLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        colLayout.setPadding((int)(2*d), 0, (int)(2*d), 0);

        col.valueLabel = new TextView(context);
        col.valueLabel.setTextColor(LABEL_COLOR);
        col.valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        col.valueLabel.setGravity(Gravity.CENTER);
        colLayout.addView(col.valueLabel, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(20*d)));

        final FrameLayout trackFrame = new FrameLayout(context);
        col.trackFrame = trackFrame;
        trackFrame.addView(trackBg(d), new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        col.fill = trackFillView(d);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 0);
        fp.gravity = Gravity.BOTTOM;
        trackFrame.addView(col.fill, fp);
        LinearLayout.LayoutParams tfp = new LinearLayout.LayoutParams((int)(40*d), 0, 1f);
        tfp.gravity = Gravity.CENTER_HORIZONTAL; tfp.topMargin = (int)(4*d); tfp.bottomMargin = (int)(6*d);
        colLayout.addView(trackFrame, tfp);

        ImageView icon = new ImageView(context);
        icon.setImageResource(VolumeLockController.iconResFor(stream));
        icon.setColorFilter(COL_ICON_TINT, PorterDuff.Mode.SRC_IN);
        colLayout.addView(icon, new LinearLayout.LayoutParams((int)(22*d), (int)(22*d)));

        trackFrame.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        isDragging = true; if (collapseRunnable != null) handler.removeCallbacks(collapseRunnable);
                        handleColumnTouch(col, e.getY()); return true;
                    case MotionEvent.ACTION_MOVE: handleColumnTouch(col, e.getY()); return true;
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                        isDragging = false; scheduleCollapse(); return true;
                }
                return false;
            }
        });

        columns.add(col);
        // Defer fill sizing until the frame has a measured height.
        col.trackFrame.post(new Runnable() { @Override public void run() {
            col.trackH = col.trackFrame.getHeight();
            updateColumnFill(col, col.isMedia ? currentStep : currentValue(col));
        }});
        col.valueLabel.setText(String.valueOf(col.isMedia ? currentStep : currentValue(col)));
        return colLayout;
    }

    private int currentValue(Column col) {
        if (col.isMedia) return currentStep;
        return audioManager.getStreamVolume(col.stream);
    }

    private void handleColumnTouch(Column col, float y) {
        int h = col.trackFrame.getHeight(); if (h <= 0) return;
        float frac = Math.max(0f, Math.min(1f, 1f - y/h));
        int value = Math.round(frac * col.max);
        updateColumnFill(col, value);
        col.valueLabel.setText(String.valueOf(value));
        if (col.isMedia) {
            currentStep = value;
            if (seekListener != null) seekListener.onStepSeek(value);
        } else {
            try { audioManager.setStreamVolume(col.stream, value, 0); } catch (Exception e) {}
        }
    }

    private void updateColumnFill(Column col, int value) {
        if (col.fill == null) return;
        int h = col.trackH > 0 ? col.trackH : col.trackFrame.getHeight();
        if (h <= 0) return;
        float frac = col.max > 0 ? (float)value / col.max : 0;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) col.fill.getLayoutParams();
        p.height = (int)(frac * h); col.fill.setLayoutParams(p);
    }

    /** While expanded, reflect hardware-key media changes in the media column. */
    private void updateMediaColumn() {
        for (Column col : columns) {
            if (col.isMedia) {
                col.max = totalSteps;
                updateColumnFill(col, currentStep);
                col.valueLabel.setText(String.valueOf(currentStep));
                break;
            }
        }
    }

    // ---- Shared builders / params ----

    private LinearLayout panel(float d) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int)(8*d);
        container.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE1B1B33); bg.setCornerRadius(20*d); bg.setStroke((int)(1*d), 0x55FFFFFF);
        container.setBackground(bg);
        return container;
    }

    private View trackBg(float d) {
        View v = new View(context);
        GradientDrawable g = new GradientDrawable(); g.setColor(0x33FFFFFF); g.setCornerRadius(10*d);
        v.setBackground(g);
        return v;
    }

    private View trackFillView(float d) {
        View v = new View(context);
        GradientDrawable g = new GradientDrawable(); g.setColor(FILL_COLOR); g.setCornerRadius(10*d);
        v.setBackground(g);
        return v;
    }

    private float density() { return context.getResources().getDisplayMetrics().density; }

    /** Bar height that stays usable in landscape (where screen height is small). */
    private int computeBarHeight() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float d = dm.density;
        boolean landscape = dm.widthPixels > dm.heightPixels;
        // 20% shorter than before (portrait 0.40 -> 0.32, landscape 0.64 -> 0.51).
        double frac = landscape ? 0.51 : 0.32;
        int barH = (int)(dm.heightPixels * frac);
        int minH = Math.min((int)(192*d), dm.heightPixels - (int)(40*d));
        return Math.max(barH, minH);
    }

    private WindowManager.LayoutParams buildParams() {
        float d = density();
        int w = overlayView instanceof FrameLayout && ((FrameLayout) overlayView).getChildCount() > 0
            ? ((FrameLayout.LayoutParams) ((FrameLayout) overlayView).getChildAt(0).getLayoutParams()).width + (int)(8*d)
            : (int)(60*d);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            w, computeBarHeight(),
            Build.VERSION.SDK_INT >= 26 ? 2038 : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        p.x = (int)(8*d);
        return p;
    }

    private void scheduleHide() {
        if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
        hideRunnable = new Runnable() { @Override public void run() { hide(); } };
        handler.postDelayed(hideRunnable, 2500);
    }

    private void scheduleCollapse() {
        if (collapseRunnable != null) handler.removeCallbacks(collapseRunnable);
        collapseRunnable = new Runnable() { @Override public void run() {
            if (isDragging) return;
            expanded = false; rebuild(); scheduleHide();
        }};
        handler.postDelayed(collapseRunnable, 5000);
    }

    public void hide() {
        handler.post(new Runnable() { @Override public void run() {
            if (isDragging) return;
            if (isShowing && overlayView != null) {
                try { wm.removeView(overlayView); } catch (Exception e) {}
                overlayView = null; trackFill = null; stepLabel = null; isShowing = false; expanded = false; columns.clear();
            }
        }});
    }
}
