# VolumeSteps

A minimal, open-source Android app that replaces the system's coarse ~15 volume levels with fine-grained control. Each hardware volume button press moves by a configurable step size.

No internet permission. No data collection. No analytics. 3 permissions total.

<img width="204" height="378" alt="Screenshot 2026-05-23 043637" src="https://github.com/user-attachments/assets/9f0b7e0a-d157-407c-b85b-0ad706559135" />
<img width="200" height="378" alt="Screenshot_2026-05-23-03-35-12-034_com miui global packageinstaller" src="https://github.com/user-attachments/assets/981b3dfd-070b-458f-9bfa-389e0b278bae" />
<img width="200" height="378" alt="Screenshot_2026-05-23-03-36-39-383_com volumesteps" src="https://github.com/user-attachments/assets/c2096e95-2bf3-43fb-998a-670dc3cbd39d" />
<img width="200" height="378" alt="Screenshot_2026-05-23-03-36-59-274_com mi android globallauncher" src="https://github.com/user-attachments/assets/e7aac792-9bb9-4d72-9d14-9b72e36a1f8e" />

## Is this safe?

| Check | How to verify |
|---|---|
| **APK built from source** | Every release is compiled by GitHub Actions from this code. Click **Actions** tab to see the full public build log |
| **No INTERNET permission** | Read `AndroidManifest.xml` — or run `aapt d permissions VolumeSteps.apk` |
| **No network code** | The CI security scan checks every build and fails if any network code is found |
| **VirusTotal scan** | Each release includes a VirusTotal report link |
| **Build it yourself** | See build instructions below |

## Features

- Configurable total steps (15–1000, default 200)
- Configurable step size per key press (1–50, default 1)
- Vertical volume bar overlay on the right side, touch-draggable
- **Expandable overlay** — tap the expand button to reveal side-by-side sliders for every stream (Media, Ring, Notification, Alarm, Call), like the native expanded volume panel
- **Volume locking** — pin any stream's volume so it can't be changed except from within the app (VolumeLockr-style)
- Screen-off volume control via MediaSession
- Hold-to-repeat with acceleration
- Haptic feedback
- No notification — runs inside AccessibilityService, no foreground service needed

## Volume locking

Open VolumeSteps and use the **Volume Lock** section: each stream has a slider and a lock
toggle. Tapping the lock pins that stream at its current level — any later change (hardware
keys, Settings, another app) is immediately reverted. Adjust a locked stream's level from the
slider here, or tap the lock again to release it. Locks are also reachable from the expanded
overlay panel.

Locking the **Media** stream pins it and disables the hardware-key stepping until you unlock it.

Enforcement runs inside the accessibility service, so it is active whenever the service is
enabled (no extra permissions). Reverting Ring/Notification while Do-Not-Disturb is active is
best-effort and may not apply on every device.

## Permissions

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Volume bar overlay |
| `MODIFY_AUDIO_SETTINGS` | Read/write system volume |
| `VIBRATE` | Haptic tick |

## Setup

1. Download APK from [Releases](../../releases)
2. Install, open VolumeSteps
3. Grant overlay permission
4. Enable accessibility service
**If grayed out, tap "Open App Info" button → ⋮ menu → Allow restricted settings → go back and enable
5. Set total steps and step size, tap Apply

## How it works

Maps N custom steps across the system's ~15 volume levels using `android.media.audiofx.Equalizer` gain offsets for sub-level granularity. A `MediaSession` with `VolumeProvider` handles screen-off volume keys.

## Building from source

```bash
sudo apt install android-sdk-platform-23 dalvik-exchange aapt zipalign apksigner default-jdk

ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
mkdir -p build/gen build/classes

aapt package -f -m -S res -J build/gen -M AndroidManifest.xml -I $ANDROID_JAR
javac -source 1.8 -target 1.8 -bootclasspath $ANDROID_JAR -classpath $ANDROID_JAR \
  -d build/classes build/gen/com/volumesteps/R.java src/com/volumesteps/*.java
dx --dex --output=build/classes.dex build/classes/
aapt package -f -S res -M AndroidManifest.xml -I $ANDROID_JAR -F build/app.apk
(cd build && aapt add app.apk classes.dex)
zipalign -f 4 build/app.apk build/app-aligned.apk
apksigner sign --ks your-key.jks --out build/VolumeSteps.apk build/app-aligned.apk
```
