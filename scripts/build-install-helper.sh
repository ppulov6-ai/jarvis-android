#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
build_dir="$RUNNER_TEMP/install-check"
mkdir -p "$build_dir/classes" "$build_dir/dex" "$build_dir/assets"
sdk="$ANDROID_HOME"
tools="$sdk/build-tools/36.0.0"
curl --fail --location --retry 3 https://github.com/ppulov6-ai/jarvis-android/releases/download/android-42a6de892603/Jarvisjon-ru-arm64.apk -o "$build_dir/assets/jarvis.apk"
echo "cf31e3dc73b6b5965cc39441a8b937a4a9a59ef8b9fa7b938402a65e6aa3f30f  $build_dir/assets/jarvis.apk" | sha256sum --check
javac -source 17 -target 17 -classpath "$sdk/platforms/android-36/android.jar" -d "$build_dir/classes" tools/install-check/*.java
mapfile -t classes < <(find "$build_dir/classes" -name '*.class')
"$tools/d8" --lib "$sdk/platforms/android-36/android.jar" --min-api 30 --output "$build_dir/dex" "${classes[@]}"
"$tools/aapt2" link -I "$sdk/platforms/android-36/android.jar" --manifest tools/install-check/AndroidManifest.xml -A "$build_dir/assets" -o "$build_dir/unsigned.apk"
(cd "$build_dir/dex" && zip -q "$build_dir/unsigned.apk" classes.dex)
"$tools/zipalign" -p -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
# One-use signing key for this diagnostic tool only; never reuse for Jarvis releases.
helper_password="$(openssl rand -hex 32)"
echo "::add-mask::$helper_password"
export helper_password
keytool -genkeypair -noprompt -keystore "$build_dir/diagnostic.keystore" -storepass:env helper_password -keypass:env helper_password -alias diagnostic -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Jarvis Installation Diagnostics"
mkdir -p diagnostic-apk
"$tools/apksigner" sign --ks "$build_dir/diagnostic.keystore" --ks-pass env:helper_password --key-pass env:helper_password --ks-key-alias diagnostic --out diagnostic-apk/Jarvis-install-check.apk "$build_dir/aligned.apk"
"$tools/apksigner" verify --verbose --print-certs diagnostic-apk/Jarvis-install-check.apk
rm -f "$build_dir/diagnostic.keystore"
unset helper_password
sha256sum diagnostic-apk/Jarvis-install-check.apk
