# Family Location Sender (Android Native)

A standalone, lightweight Android application whose **only job** is to push
GPS coordinates to a configurable HTTP endpoint at a chosen interval. It is
designed for use in family-tracking scenarios where the device is operated by
a child or a senior, and the operator wants to make sure tracking cannot be
disabled, removed or tampered with easily.

> This project is **completely independent** of the *My Family My Life* web
> system. It only acts as a sender; all storage, visualization and history
> belong to the server.

---

## Highlights

- **100% native Android** (Kotlin, Material 3, View Binding).
- **Foreground Service** + persistent notification — keeps sending location
  even when the app is closed, the screen is locked, or other apps are open.
- **Boot Receiver** — automatically restarts tracking after a reboot.
- **Smart Mode** (default): high-frequency updates when moving, low-frequency
  when stationary, immediate update when the user moves more than 100 m.
- **Encrypted local storage** — settings and password hash live in
  `EncryptedSharedPreferences` (AES-256). **No location history** is ever
  stored on the device.
- **Password gate** — default password is `1001`, configurable in settings.
  Auto-locks on background / screen off / 1-minute idle.
- **Device Admin** receiver — optional, makes it harder to uninstall the app
  without first disabling the policy.
- **English + Arabic** with full RTL support.
- **Configurable API endpoint** — default
  `https://family.kvd.dscloud.me/api/location/update`; user-editable.
- Ready-to-use **GitHub Actions** workflow that produces a debug APK.

---

## Project layout

```
family-location-sender/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/family/locationsender/
│       │   ├── App.kt
│       │   ├── data/        # Prefs.kt, LocationPayload.kt
│       │   ├── network/     # ApiClient.kt (OkHttp)
│       │   ├── receiver/    # BootReceiver, AppDeviceAdminReceiver
│       │   ├── service/     # LocationForegroundService, SmartLocationStrategy
│       │   ├── ui/          # LockActivity, SetupActivity, MainActivity, SettingsActivity
│       │   └── util/        # LocaleHelper, DeviceUtils, InactivityTracker, SessionState
│       └── res/
│           ├── layout/      # activity_lock|setup|main|settings.xml
│           ├── values/      # strings, colors, themes (English)
│           ├── values-ar/   # strings (Arabic)
│           ├── drawable/    # vector icons + backgrounds
│           ├── mipmap-*/    # launcher icons (vector)
│           └── xml/         # device_admin.xml, backup_rules.xml, data_extraction_rules.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── .github/workflows/build.yml
```

---

## Building the APK

### Option A — GitHub Actions (recommended)

1. Push this repository to GitHub.
2. Open the **Actions** tab → run **Build Android APK** (it also runs on every
   push).
3. Download the artifact `family-location-sender-debug-apk` from the workflow
   summary.
4. Install on a device:
   ```bash
   adb install family-location-sender-debug.apk
   ```

The workflow uses Gradle 8.7 / JDK 17 and auto-generates the Gradle wrapper.

### Option B — Local build

Requirements:
- Android SDK with platform 34, build-tools 34.x
- JDK 17
- Gradle 8.7 (or use `./gradlew` after generating the wrapper)

```bash
cd family-location-sender

# Generate the gradle wrapper jar locally (only once)
gradle wrapper --gradle-version 8.7 --distribution-type bin

# Point the build at your Android SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Build a debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

For a release build, configure a keystore (`keystore.properties` or env vars)
and run `./gradlew assembleRelease`.

---

## First-run flow

1. App opens at **Lock Screen** → enter password `1001`.
2. **Setup Screen** appears (first run only) — fill in:
   - Profile photo (camera or gallery)
   - Full name
   - Family code
   - API endpoint (defaults to
     `https://family.kvd.dscloud.me/api/location/update`)
   - Update interval (default: Smart Mode)
   - Optional: change the password
3. **Main Dashboard** shows live status. Press **Start Tracking** to begin.

The app will then:
- Ask for **fine location** permission.
- Recommend **background location** ("Allow all the time").
- Recommend exemption from **battery optimization**.
- Recommend enabling **Device Admin** (shown as a warning card).

---

## JSON payload

Every successful tick POSTs the following JSON body (UTF-8,
`Content-Type: application/json`) to the configured endpoint:

```json
{
  "familyCode": "ABC123",
  "memberId": "and_abcdef0123456789",
  "memberName": "Mohammed",
  "profileImage": "data:image/jpeg;base64,...",
  "deviceId": "and_abcdef0123456789",
  "latitude": 24.7136,
  "longitude": 46.6753,
  "accuracy": 12.5,
  "speed": 1.2,
  "battery": 78,
  "timestamp": 1739200000000,
  "trackingStatus": "active",
  "networkStatus": "online",
  "connectionType": "wifi"
}
```

The server is expected to respond with any 2xx status code.

---

## Security & protection

- Password stored as **SHA-256 hash** inside
  `EncryptedSharedPreferences` (AES-256-GCM, AES-256-SIV).
- The lock screen sets `FLAG_SECURE` to block screenshots and screen recording
  while the password is being typed.
- The session is wiped:
  - immediately when the app goes to the background,
  - after 1 minute of UI idleness,
  - via the **Lock Now** button.
- **Device Admin** receiver is declared but only takes effect after the user
  enables it manually (this is the only path Android allows for non-MDM apps).
- No third-party analytics. No location history stored locally.

---

## Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | core feature |
| `ACCESS_BACKGROUND_LOCATION` | sending while screen is off |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | persistent service |
| `POST_NOTIFICATIONS` | ongoing notification (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | auto-start after reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | reliability |
| `INTERNET`, `ACCESS_NETWORK_STATE` | sending payloads, network status |
| `CAMERA`, `READ_MEDIA_IMAGES` | profile picture |
| `BIND_DEVICE_ADMIN` | optional uninstall protection |

---

## Roadmap (intentionally out of scope right now)

- ❌ SOS feature
- ❌ Maps inside the app
- ❌ Medical / health features
- ❌ On-device location history

This app is intentionally **send-only**. All analysis stays on the server.
