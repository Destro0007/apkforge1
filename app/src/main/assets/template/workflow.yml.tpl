name: Build APK
on:
  push:
    branches: ['**']
  workflow_dispatch:
permissions:
  contents: write
jobs:
  build:
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Create signing key (first run only)
        run: |
          if [ ! -f signing/debug.keystore ]; then
            mkdir -p signing
            keytool -genkeypair -keystore signing/debug.keystore -storepass android -keypass android \
              -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
              -dname "CN=APK Forge,O=APK Forge,C=US"
            git config user.name "apkforge"
            git config user.email "apkforge@users.noreply.github.com"
            git add signing/debug.keystore
            git commit -m "Add signing key"
            git push origin HEAD
          fi
      - name: Android SDK
        run: |
          yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
          "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0" > /dev/null
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'
      - name: Build
        run: gradle assembleDebug --no-daemon --console=plain
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/*.apk
