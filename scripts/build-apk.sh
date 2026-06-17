#!/usr/bin/env bash
#
# Compiles the unsigned, zipaligned APK to build/app-aligned.apk.
# Builds against android-23 on purpose: the project targets that platform JAR and
# reaches newer APIs via reflection, so newer android.jars are not required.
#
set -euo pipefail

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
DX=${DX:-/usr/lib/android-sdk/build-tools/debian/dx}

rm -rf build/gen build/classes
mkdir -p build/gen build/classes

aapt package -f -m -S res -J build/gen -M AndroidManifest.xml -I "$ANDROID_JAR"

javac -source 1.8 -target 1.8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d build/classes -Xlint:-options \
  build/gen/com/volumesteps/R.java src/com/volumesteps/*.java

"$DX" --dex --output=build/classes.dex build/classes/

aapt package -f -S res -M AndroidManifest.xml -I "$ANDROID_JAR" -F build/app.apk
(cd build && aapt add app.apk classes.dex)

zipalign -f 4 build/app.apk build/app-aligned.apk
echo "✅ Built build/app-aligned.apk"
