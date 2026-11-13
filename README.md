# SdVPN — Android

VPN client for Android: Kotlin + Jetpack Compose + Android VpnService + sing-box (libbox).

It takes single keys and subscriptions in these formats: vless, vmess, trojan, ss, hysteria2 and tuic.
Supported transports are tcp, ws, grpc, httpupgrade and h2. xhttp is listed in the app but not supported.
Auto mode keeps the fastest working server and fails over on its own.

The app has three modes:
- VPN: full tunnel.
- VPN + Zapret: selected services go direct, through TLS fragmentation.
- DPI only.

## Build

The APK is built by GitHub Actions (`.github/workflows/build.yml`):
- A push to `main` produces a signed release APK as the `SdVPN` artifact.
- A tag `android-vX.Y.Z` also publishes a GitHub Release.
  The app updates from releases whose tag starts with `android-`.

The signing key isn't stored in the repo. It comes from the Actions secrets `ANDROID_KEYSTORE_B64` and `ANDROID_KEYSTORE_PASSWORD`.
Without that key, updates can't be installed over an existing install.

For a local build you need JDK 17 and the Android SDK; run `./gradlew assembleDebug`.
