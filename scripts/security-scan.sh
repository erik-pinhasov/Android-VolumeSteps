#!/usr/bin/env bash
#
# Static privacy/security checks run on every build.
# Any finding fails the build, so a published release has provably passed them all.
#
set -euo pipefail

echo "== Security scan: no INTERNET permission =="
if grep -i "android.permission.INTERNET" AndroidManifest.xml; then
  echo "❌ FAIL: INTERNET permission found!"
  exit 1
fi
echo "✅ PASS: No INTERNET permission"

echo "== Security scan: no network code =="
NETWORK="HttpURLConnection\|OkHttp\|Retrofit\|java\.net\.URL\|Socket(\|ServerSocket"
NETWORK="$NETWORK\|DatagramSocket\|HttpClient\|javax\.net"
if grep -rn "$NETWORK" src/ 2>/dev/null; then
  echo "❌ FAIL: Network code found!"
  exit 1
fi
echo "✅ PASS: No network code"

echo "== Security scan: no data collection / dynamic code loading =="
COLLECT="getDeviceId\|getSubscriberId\|ANDROID_ID\|advertisingId"
COLLECT="$COLLECT\|firebase\|analytics\|mixpanel\|amplitude\|appsflyer"
COLLECT="$COLLECT\|DexClassLoader\|InMemoryDexClassLoader\|PathClassLoader"
if grep -rn "$COLLECT" src/ 2>/dev/null; then
  echo "❌ FAIL: Data collection or dynamic code loading found!"
  exit 1
fi
echo "✅ PASS: No data collection or dynamic code loading"

echo "== Security scan: permission count =="
COUNT=$(grep -c "uses-permission" AndroidManifest.xml)
echo "Permissions declared: $COUNT"
grep "uses-permission" AndroidManifest.xml
if [ "$COUNT" -gt 5 ]; then
  echo "❌ FAIL: Too many permissions ($COUNT)"
  exit 1
fi
echo "✅ PASS: Permission count OK ($COUNT)"
