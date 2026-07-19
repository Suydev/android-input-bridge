# BUILD.md — APK Build Instructions

---

## Requirements

- JDK 17 (Temurin recommended)
- Android SDK (API 29–35)
- Gradle 8.7 (managed by gradlew)
- Internet connection (first build only — downloads dependencies)

---

## Debug APKs (no signing needed)

```bash
# From the project root
./gradlew :app-bridge:assembleDebug :app-receiver:assembleDebug

# Output locations
app-bridge/build/outputs/apk/debug/app-bridge-debug.apk
app-receiver/build/outputs/apk/debug/app-receiver-debug.apk
```

---

## Release APKs (requires signing)

### 1. Create a keystore (one-time)

```bash
keytool -genkeypair \
  -v \
  -keystore release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias inputbridge \
  -storepass <store_password> \
  -keypass <key_password>
```

**Never commit release.jks to git.**

### 2. Create secrets.properties (gitignored)

```properties
# secrets.properties (in project root, never committed)
SIGNING_KEYSTORE_PATH=/absolute/path/to/release.jks
SIGNING_KEY_ALIAS=inputbridge
SIGNING_KEY_PASSWORD=<key_password>
SIGNING_STORE_PASSWORD=<store_password>
```

### 3. Build release APKs

```bash
./gradlew :app-bridge:assembleRelease :app-receiver:assembleRelease
```

---

## GitHub Actions

### Required secrets (for release builds)

| Secret | Description |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Base64-encoded keystore: `base64 -w0 release.jks` |
| `SIGNING_KEY_ALIAS` | Key alias (e.g. `inputbridge`) |
| `SIGNING_KEY_PASSWORD` | Key password |
| `SIGNING_STORE_PASSWORD` | Store password |

Signing secrets are optional — if absent, debug APKs are still produced.

### Downloading APKs from CI

1. Go to the repository on GitHub
2. Click **Actions** tab
3. Click the latest successful **Android CI** workflow run
4. Scroll to **Artifacts** at the bottom
5. Download `bridge-debug-apk-*` and `receiver-debug-apk-*`

---

## Versioning

Version is set in the convention plugin `AndroidAppConventionPlugin.kt`:

```kotlin
versionCode = 1
versionName = "0.1.0"
```

Increment `versionCode` by 1 for every release. Use semantic versioning for `versionName`.

---

## Module dependencies

```
app-bridge ──► shared-core, protocol, input-capture, transport-wifi,
               transport-bluetooth-hid, diagnostics
app-receiver ► shared-core, protocol, transport-wifi,
               accessibility-receiver, diagnostics
```

All modules build independently. No circular dependencies.

---

## Common build failures

**`SDK location not found`**
→ Create `local.properties` with `sdk.dir=/path/to/Android/Sdk`

**`Unsupported class file major version`**
→ Use JDK 17, not JDK 8 or JDK 11

**`Could not resolve com.android.tools.build:gradle`**
→ Check internet connection. First build downloads ~500MB of dependencies.

**`Configuration cache problems`**
→ Delete `.gradle/configuration-cache/` and retry

**`BUILD FAILED: Duplicate class`**
→ Run `./gradlew dependencies` to find conflicting transitive deps
