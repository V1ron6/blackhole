#!/usr/bin/env bash
#
# builder.sh — one-command setup + build for Blackhole.
#
# Handles everything that used to be manual: picking JDK 17 (Gradle 8.7
# doesn't support the newer JDK Codespaces defaults to), installing the
# Android SDK if it's missing, fixing local.properties if it's stale or
# missing, then running the actual Gradle build. Safe to re-run any time -
# every step checks whether it's already done before doing it again, so a
# second run just builds, it doesn't redownload the SDK.
#
# Usage:
#   ./builder.sh                # builds debug APK (assembleDebug)
#   ./builder.sh assembleDebug  # same, explicit
#   ./builder.sh installDebug   # build AND install to a connected device/emulator
#
set -euo pipefail

GRADLE_TASK="${1:-assembleDebug}"
JDK_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
SDK_HOME="$HOME/android-sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

info()  { echo -e "\033[1;36m==>\033[0m $1"; }
ok()    { echo -e "\033[1;32m ok\033[0m $1"; }
warn()  { echo -e "\033[1;33m !! \033[0m $1"; }

# --- 1. JDK 17 -------------------------------------------------------------
info "Checking JDK 17..."
if [ ! -x "$JDK_HOME/bin/java" ]; then
    warn "JDK 17 not found at $JDK_HOME - installing"
    sudo apt-get update -qq
    sudo apt-get install -y -qq openjdk-17-jdk
else
    ok "JDK 17 already installed"
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
ok "$(java -version 2>&1 | head -n1)"

# --- 2. Android SDK ----------------------------------------------------------
info "Checking Android SDK..."
if [ ! -d "$SDK_HOME/cmdline-tools/latest" ]; then
    warn "Android SDK not found at $SDK_HOME - downloading command-line tools"
    mkdir -p "$SDK_HOME/cmdline-tools"
    TMP_ZIP="$(mktemp)"
    curl -sL -o "$TMP_ZIP" "$CMDLINE_TOOLS_URL"
    unzip -q "$TMP_ZIP" -d "$SDK_HOME/cmdline-tools"
    mv "$SDK_HOME/cmdline-tools/cmdline-tools" "$SDK_HOME/cmdline-tools/latest"
    rm -f "$TMP_ZIP"
else
    ok "Android SDK command-line tools already present"
fi
export ANDROID_HOME="$SDK_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

if [ ! -d "$SDK_HOME/platforms/android-34" ] || [ ! -d "$SDK_HOME/build-tools/34.0.0" ]; then
    warn "Installing required SDK packages (platform-tools, android-34, build-tools 34.0.0)"
    yes | sdkmanager --licenses > /dev/null 2>&1 || true
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
else
    ok "Required SDK packages already installed"
fi

# --- 3. local.properties ----------------------------------------------------
info "Checking local.properties..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_PROPS="$SCRIPT_DIR/local.properties"
if [ ! -f "$LOCAL_PROPS" ] || ! grep -q "sdk.dir=$SDK_HOME" "$LOCAL_PROPS" 2>/dev/null; then
    warn "local.properties missing or stale - writing sdk.dir=$SDK_HOME"
    echo "sdk.dir=$SDK_HOME" > "$LOCAL_PROPS"
else
    ok "local.properties already correct"
fi

# --- 4. Build ----------------------------------------------------------------
info "Running ./gradlew $GRADLE_TASK ..."
cd "$SCRIPT_DIR"
./gradlew "$GRADLE_TASK"

APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo ""
    ok "Build complete: $APK_PATH"
else
    echo ""
    info "Build finished (task: $GRADLE_TASK) - check output above for the result."
fi

