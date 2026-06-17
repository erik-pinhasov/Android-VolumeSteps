package com.volumesteps;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private SharedPreferences prefs;
    private EditText stepsInput, stepSizeInput;
    private TextView statusText, accessibilityStatus, overlayStatus, batteryStatus, volumePreviewText;
    private Button toggleBtn;
    private SeekBar volumePreview;
    private AudioManager audioManager;
    private android.os.PowerManager powerManager;
    private VolumeLockController lockController;
    private LinearLayout lockContainer, setupSection;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("volume_steps", Context.MODE_PRIVATE);
        stepsInput = (EditText) findViewById(R.id.steps_input);
        stepSizeInput = (EditText) findViewById(R.id.stepsize_input);
        statusText = (TextView) findViewById(R.id.status_text);
        accessibilityStatus = (TextView) findViewById(R.id.accessibility_status);
        overlayStatus = (TextView) findViewById(R.id.overlay_status);
        batteryStatus = (TextView) findViewById(R.id.battery_status);
        setupSection = (LinearLayout) findViewById(R.id.setup_section);
        powerManager = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        toggleBtn = (Button) findViewById(R.id.toggle_btn);
        volumePreview = (SeekBar) findViewById(R.id.volume_preview);
        volumePreviewText = (TextView) findViewById(R.id.volume_preview_text);
        stepsInput.setText(String.valueOf(prefs.getInt("total_steps", 200)));
        stepSizeInput.setText(String.valueOf(prefs.getInt("step_size", 1)));

        findViewById(R.id.apply_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applySteps(); } });
        findViewById(R.id.apply_stepsize_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyStepSize(); } });
        toggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleEnabled(); } });
        findViewById(R.id.accessibility_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } });
        findViewById(R.id.battery_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception e2) {}
                }
            } });
        findViewById(R.id.overlay_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); } catch (Exception e) {}
            } });
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        lockController = VolumeLockController.getInstance(this);
        lockContainer = (LinearLayout) findViewById(R.id.lock_container);
        setupVolumePreview(prefs.getInt("total_steps", 200));
        buildLockRows();
    }

    @Override protected void onResume() { super.onResume(); updateStatus(); buildLockRows(); }

    private static final int LOCK_ON = 0xFF6C63FF;   // accent purple
    private static final int LOCK_OFF = 0xFF8A8AA8;   // muted grey

    private void buildLockRows() {
        if (lockContainer == null) return;
        lockContainer.removeAllViews();
        final float d = getResources().getDisplayMetrics().density;
        for (final int stream : VolumeLockController.MANAGED) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = (int)(14*d);

            ImageView icon = new ImageView(this);
            icon.setImageResource(VolumeLockController.iconResFor(stream));
            icon.setColorFilter(0xFFBFBFE0, PorterDuff.Mode.SRC_IN);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams((int)(30*d), (int)(30*d));
            ip.rightMargin = (int)(12*d);

            TextView label = new TextView(this);
            label.setText(VolumeLockController.labelFor(stream));
            label.setTextColor(0xFFE2E2F2);
            label.setTextSize(15);
            label.setWidth((int)(104*d));

            final boolean locked = lockController.isLocked(stream);
            final SeekBar bar = new SeekBar(this);
            int max = lockController.getMaxVolume(stream);
            bar.setMax(max);
            bar.setProgress(Math.min(locked ? lockController.getLockedLevel(stream)
                    : lockController.getVolume(stream), max));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bp.leftMargin = (int)(4*d); bp.rightMargin = (int)(10*d);

            final ImageButton lockBtn = new ImageButton(this);
            lockBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int lpad = (int)(10*d); lockBtn.setPadding(lpad, lpad, lpad, lpad);
            styleLock(lockBtn, locked, d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int)(52*d), (int)(48*d));

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) return;
                    if (lockController.isLocked(stream)) lockController.setLockedLevel(stream, p); // updates pin + applies
                    else try { audioManager.setStreamVolume(stream, p, 0); } catch (Exception e) {}
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });

            lockBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean locked2 = !lockController.isLocked(stream);
                    lockController.setLock(stream, locked2);
                    styleLock(lockBtn, locked2, d);
                    Toast.makeText(MainActivity.this,
                        VolumeLockController.labelFor(stream) + (locked2 ? " locked" : " unlocked"),
                        Toast.LENGTH_SHORT).show();
                }
            });

            row.addView(icon, ip);
            row.addView(label);
            row.addView(bar, bp);
            row.addView(lockBtn, lp);
            lockContainer.addView(row, rp);
        }
    }

    /** Lock toggle: closed padlock on an accent pill when locked, open padlock on a faint pill when not. */
    private void styleLock(ImageButton b, boolean locked, float d) {
        b.setImageResource(locked ? R.drawable.ic_lock_closed : R.drawable.ic_lock_open);
        b.setColorFilter(locked ? 0xFFFFFFFF : LOCK_OFF, PorterDuff.Mode.SRC_IN);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12*d);
        bg.setColor(locked ? LOCK_ON : 0x22FFFFFF);
        bg.setStroke((int)(1*d), locked ? LOCK_ON : 0x44FFFFFF);
        b.setBackground(bg);
    }

    private void applySteps() {
        int v = parseInput(stepsInput, 15, 1000); if (v < 0) return;
        prefs.edit().putInt("total_steps", v).apply();
        try { VolumeStepController c = VolumeStepController.getInstance(this); c.rebuildStepTable(); c.syncFromSystem(); } catch (Exception e) {}
        setupVolumePreview(v); Toast.makeText(this, "Total steps: " + v, Toast.LENGTH_SHORT).show(); updateStatus();
    }
    private void applyStepSize() {
        int v = parseInput(stepSizeInput, 1, 50); if (v < 0) return;
        prefs.edit().putInt("step_size", v).apply();
        Toast.makeText(this, "Step size: " + v, Toast.LENGTH_SHORT).show(); updateStatus();
    }
    private int parseInput(EditText input, int min, int max) {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) { Toast.makeText(this, "Enter a number", Toast.LENGTH_SHORT).show(); return -1; }
        try { int v = Integer.parseInt(t);
            if (v < min || v > max) { Toast.makeText(this, "Between " + min + " and " + max, Toast.LENGTH_SHORT).show(); return -1; }
            return v;
        } catch (NumberFormatException e) { Toast.makeText(this, "Invalid", Toast.LENGTH_SHORT).show(); return -1; }
    }
    private void toggleEnabled() {
        boolean was = prefs.getBoolean("enabled", false);
        prefs.edit().putBoolean("enabled", !was).apply();
        Toast.makeText(this, was ? "Disabled" : "Enabled", Toast.LENGTH_SHORT).show(); updateStatus();
    }
    private boolean isAccessibilityEnabled() {
        try { String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName() + "/"); } catch (Exception e) { return false; }
    }
    private void updateStatus() {
        boolean en = prefs.getBoolean("enabled", false);
        int steps = prefs.getInt("total_steps", 200), sz = prefs.getInt("step_size", 1);
        boolean acc = isAccessibilityEnabled(), ovl = Settings.canDrawOverlays(this);
        statusText.setText("Steps: " + steps + "  |  Size: " + sz + (acc && en ? "  \u2022  Active" : "  \u2022  Inactive"));
        toggleBtn.setText(acc ? (en ? "Disable" : "Enable") : "Enable accessibility first");
        toggleBtn.setBackgroundColor(acc ? (en ? 0xFFF44336 : 0xFF4CAF50) : 0xFF555555);
        accessibilityStatus.setText(acc ? "\u2713 Accessibility service enabled" : "\u2717 Accessibility service NOT enabled");
        accessibilityStatus.setTextColor(acc ? 0xFF4CAF50 : 0xFFF44336);
        overlayStatus.setText(ovl ? "\u2713 Overlay permission granted" : "\u2717 Overlay permission needed");
        overlayStatus.setTextColor(ovl ? 0xFF4CAF50 : 0xFFF44336);
        findViewById(R.id.overlay_btn).setVisibility(ovl ? View.GONE : View.VISIBLE);
        findViewById(R.id.accessibility_btn).setVisibility(acc ? View.GONE : View.VISIBLE);

        boolean batt = isBatteryUnrestricted();
        batteryStatus.setText(batt ? "\u2713 Battery unrestricted" : "\u2717 Battery optimization is on (may stop the service)");
        batteryStatus.setTextColor(batt ? 0xFF4CAF50 : 0xFFFF9800);
        findViewById(R.id.battery_btn).setVisibility(batt ? View.GONE : View.VISIBLE);

        // Setup card sits at the top on a fresh install; hide it once everything is granted.
        if (setupSection != null) setupSection.setVisibility((ovl && acc && batt) ? View.GONE : View.VISIBLE);
    }
    private boolean isBatteryUnrestricted() {
        try { return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName()); }
        catch (Exception e) { return true; }
    }
    private void setupVolumePreview(final int totalSteps) {
        volumePreview.setMax(totalSteps);
        int cur = Math.min(prefs.getInt("current_step", 0), totalSteps);
        volumePreview.setProgress(cur);
        volumePreviewText.setText("Step " + cur + " / " + totalSteps);
        volumePreview.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fu) {
                volumePreviewText.setText("Step " + p + " / " + totalSteps);
                if (fu) { try { VolumeStepController.getInstance(MainActivity.this).setStep(p); } catch (Exception e) {} }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }
}
