# TPEApp — Accountability Partner Suite for Android

An Android application (with companion Node.js backend) that implements a full **Total Power Exchange (TPE)** accountability framework.  The app runs on a **rooted** Android device and gives an Accountability Partner comprehensive remote oversight and control capabilities.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Requirements](#requirements)
4. [Setup](#setup)
   - [Backend](#backend-setup)
   - [Android App](#android-app-setup)
5. [Feature Reference](#feature-reference)
   - [QR-Code Pairing](#1-qr-code-pairing)
   - [AI Content Filter](#2-ai-content-filter-nudenet)
   - [Consequence Dispatcher](#3-consequence-dispatcher)
   - [BLE Toy Integration](#4-ble-toy-integration)
   - [Tone Enforcement](#5-tone-enforcement)
   - [Adherence Kiosk](#6-adherence-kiosk--daily-health-routine)
   - [Daily Mood Check-In](#7-daily-mood-check-in)
   - [Task Assignment](#8-task-assignment--verification)
   - [Peer Review Screen-Sharing](#9-peer-review-screen-sharing)
   - [Remote Device Control](#10-remote-device-control-fcm)
   - [App Inventory Management](#11-app-inventory-management)
   - [Questions / Puppy Pouch](#12-questions--puppy-pouch)
   - [MDM / Device Admin](#13-mdm--device-admin)
   - [Webhook System](#14-webhook-system)
   - [FCM Push Commands](#15-fcm-push-commands)
6. [Backend API Reference](#backend-api-reference)
7. [Security Notes](#security-notes)
8. [Building](#building)

---

## Overview

TPEApp turns an Android device into a remotely-managed accountability device.  An **Accountability Partner** (Dom/Keyholder) operates a lightweight Node.js dashboard and uses Firebase Cloud Messaging (FCM) to push rules and commands in real time.  The submissive device enforces those rules autonomously — filtering content, enforcing vocabulary, triggering physical stimuli via Bluetooth toys, and reporting compliance events back to the partner.

---

## Architecture

```
┌───────────────────────────────┐        FCM        ┌─────────────────────┐
│       Android Device          │◄─────────────────►│  Partner Backend    │
│  (rooted, minSdk 31)          │                   │  (Node.js / Express)│
│                               │   Webhooks (HTTP) │                     │
│  ┌──────────────────────┐     │──────────────────►│  /api/pair          │
│  │  FilterService       │     │                   │  /api/settings/update│
│  │  (AIDL bound svc)    │     │                   │  /api/audit/upload  │
│  └──────────────────────┘     │                   └─────────────────────┘
│  ┌──────────────────────┐     │
│  │  LSPosed / Xposed    │     │   BLE
│  │  module              │     │◄──────────────────  Lovense toy
│  └──────────────────────┘     │◄──────────────────  Pavlok wristband
└───────────────────────────────┘
```

- **FilterService** — a long-lived foreground service that hosts the TFLite NudeNet classifier and exposes an AIDL interface.
- **LSPosed module** — hooks into target apps and submits screenshots to `FilterService` for asynchronous scanning.
- **PartnerFcmService** — receives FCM data messages and routes them to the appropriate subsystem.
- **ConsequenceDispatcher** — single entry point for all punishment and reward stimuli.

---

## Requirements

| Requirement | Details |
|---|---|
| Android | API 31+ (Android 12) — rooted |
| Root | Magisk or equivalent; `su` must be available in PATH |
| LSPosed | Required for the content-filter Xposed module |
| Firebase | A Firebase project with FCM enabled |
| Backend | Node.js 18+ for the partner dashboard |

---

## Setup

### Backend Setup

```bash
cd backend
npm install

# Generate a long random pairing secret
export PAIRING_TOKEN=$(openssl rand -hex 32)

# Place your Firebase service-account JSON in the same directory
# (or set GOOGLE_APPLICATION_CREDENTIALS to its absolute path)
cp /path/to/serviceAccountKey.json .

npm start          # default port 3000
# PORT=8080 npm start   # custom port
```

The backend stores paired devices in memory.  For production, replace the `pairedDevices` array with a database.

### Android App Setup

1. Build and install the APK (see [Building](#building)).
2. Grant Device Admin rights to TPEApp in **Settings → Security → Device Admin Apps**.
3. Enable the **ToneEnforcementService** accessibility service.
4. Enable the **MindfulNotificationService** notification-listener service.
5. Launch the app — the pairing screen opens automatically on first run.
6. Scan the partner's QR code (which encodes `{ endpoint, pairing_token, webhook_secret, session_id, signaling_url }`).
7. The app registers its FCM token with the backend and is ready.

---

## Feature Reference

### 1. QR-Code Pairing

**File:** `app/…/pairing/PairingActivity.kt`

On first launch the app opens a camera view and scans the partner's QR code.  The QR payload is a JSON object:

```json
{
  "endpoint":       "https://your-backend.example.com",
  "pairing_token":  "<PAIRING_TOKEN>",
  "webhook_secret": "<bearer token for outbound webhooks>",
  "session_id":     "<WebRTC session>",
  "signaling_url":  "wss://signaling.example.com"
}
```

After a successful `POST /api/pair`, the `is_paired` flag is persisted so the pairing screen is never shown again.  The webhook bearer token and signaling URL are stored automatically.

---

### 2. AI Content Filter (NudeNet)

**Files:** `app/…/service/FilterService.kt`, `app/…/ml/NudeNetClassifier.kt`

A persistent foreground service loads a TFLite NudeNet model and exposes an AIDL interface (`IFilterService`) that the LSPosed module calls for every image rendered by target apps.

| Setting | Default | FCM key |
|---|---|---|
| Confidence threshold | 0.55 | `filter_confidence_threshold` |
| Strict mode threshold | 0.30 | `filter_strict_mode` |
| Blocked NudeNet classes | *(all)* | `filter_blocked_classes` (JSON array) |

- **Violation**: calls `ConsequenceDispatcher.punish()` and fires an `app_blocked` webhook event.
- **Clean scan reward**: calls `ConsequenceDispatcher.reward()` at most once every 30 minutes.
- Settings changes pushed via FCM take effect immediately without restarting the service.

---

### 3. Consequence Dispatcher

**File:** `app/…/consequence/ConsequenceDispatcher.kt`

Centralised singleton that routes every punishment and reward stimulus.  All violation paths (content filter, tone enforcement, missed check-ins, …) call `punish()`; all compliance paths call `reward()`.

#### Punishment profile

| Channel | Action |
|---|---|
| Lovense toy | Vibrate at level **20** (maximum) for **3 seconds** |
| Pavlok wristband | Zap at intensity **64** for **3 seconds** |
| Webhook | `{ "event": "punishment", "reason": "…", "timestamp": … }` |

#### Reward profile

| Channel | Action |
|---|---|
| Lovense toy | Vibrate at level **5** for **1 second** |
| Pavlok wristband | Beep at intensity **64** for **1 second** |
| Webhook | `{ "event": "reward", "reason": "…", "timestamp": … }` |

---

### 4. BLE Toy Integration

**Files:** `app/…/ble/LovenseManager.kt`, `app/…/ble/PavlokManager.kt`, `app/…/ble/BleManager.kt`

Both managers use a shared `BleManager` base that scans for, connects to, and writes GATT characteristics over BLE.

#### Lovense
- Service UUID: `0000fff0-…` · TX char: `0000fff2-…`
- Commands are ASCII strings: `"Vibrate:10;"`, `"Rotate:5;"`, `"Pump:1;"`, `"Stop:0;"`
- Vibration/rotation level: **0–20**; pump level: **0–3**

#### Pavlok 2/3
- Service UUID: `0000fee9-…` · TX char: `d44bc439-…`
- Commands are 3-byte arrays: `[stimulusType, intensity, durationUnit]`
- Stimulus types: **Zap** (0x04) · **Vibrate** (0x01) · **Beep** (0x02)
- Intensity: **0–255**; duration unit: 100 ms each

---

### 5. Tone Enforcement

**File:** `app/…/mindful/ToneEnforcementService.kt`

An `AccessibilityService` that monitors every `TYPE_VIEW_TEXT_CHANGED` event system-wide.  When text contains a word from the partner-configured restricted vocabulary, the word is replaced with `[Redacted]` via `ACTION_SET_TEXT`.

| Mode | Behaviour |
|---|---|
| **Soft mode** | If the user deletes the correction and retypes the original word within **3 seconds**, that word is whitelisted for the session and a `punishment` + `override_used` webhook event fires |
| **Strict mode** | Corrections applied unconditionally; session whitelist and bypass are ignored |

- Vocabulary list pushed via FCM (`mindful_restricted_vocabulary` — JSON array).
- Strict tone mode pushed via FCM (`compliance_strict_tone_mode`).
- Detection is debounced (80 ms) to prevent keyboard lag on fast typing.
- Session state resets when the user switches app or focused EditText.

---

### 6. Adherence Kiosk / Daily Health Routine

**Files:** `app/…/adherence/AdherenceKioskActivity.kt`, `app/…/adherence/AdherenceVisionAnalyzer.kt`, `app/…/adherence/AuditUploadWorker.kt`

Launched by `AdherenceAlarmReceiver` at a partner-configured time via a full-screen intent notification.  The activity is **un-dismissible** (kiosk mode):

- Back gesture / button swallowed on all API levels.
- Screen stays on; shows over lock screen.
- All hardware keys consumed.

**Flow:**
1. User taps **Start Recording**.
2. CameraX records a **15-second** HD video while `AdherenceVisionAnalyzer` inspects each frame (ML Kit object detection) for required objects ("person" + "medical equipment").
3. After recording:
   - **Pass** (≥ required detection ratio) → `reward()` fired, video + ML scores queued for upload via `AuditUploadWorker` (WorkManager, requires network, exponential backoff).
   - **Fail** → `punish()` fired, user shown the detection percentage and allowed to retry.

**Audit upload payload** (`POST /api/audit/upload`, multipart):

| Field | Content |
|---|---|
| `video` | `.mp4` file (≤ 200 MB) |
| `scores` | JSON: `{ detection_ratio, last_label, last_score, session_ts }` |

---

### 7. Daily Mood Check-In

**File:** `app/…/checkin/CheckInActivity.kt`

Allows the device owner to submit a daily mood score and free-text note to the partner backend.

```json
POST /api/tpe/checkin
Authorization: Bearer <webhook_secret>

{ "mood_score": 7, "note": "Feeling good today." }
```

Reachable from the main dashboard or via a `REQUEST_CHECKIN` FCM notification.

---

### 8. Task Assignment & Verification

**Files:** `app/…/tasks/AssignTaskActivity.kt`, `app/…/tasks/TaskListActivity.kt`, `app/…/tasks/TaskVerificationActivity.kt`, `app/…/tasks/TaskPhotoUploadWorker.kt`

Partners assign tasks (title, description, deadline) from a PIN-protected screen.  The task is sent to `POST /api/admin/tpe/tasks` with HTTP Basic Auth and forwarded to the device via a `TASK_ASSIGNED` FCM push.

**On the device:**
- Tasks are persisted locally and shown in a **Pending / Completed / Missed** list.
- Tapping a pending task opens the verification screen where the user records photo proof.
- `TaskPhotoUploadWorker` uploads the image to `POST /api/tpe/task/status` using the webhook bearer token.
- Missed deadlines are detected by `TaskDeadlineReceiver` (AlarmManager) and trigger `punish()`.

---

### 9. Peer Review Screen-Sharing

**Files:** `app/…/review/ReviewActivity.kt`, `app/…/review/ScreencastService.kt`, `app/…/review/StreamCoordinator.kt`, `app/…/review/RemoteInputDispatcher.kt`

Enables the partner to watch the device screen live via WebRTC.

**Flow:**
1. Partner sends a `START_REVIEW` FCM message (includes signaling server URL).
2. Device receives the message → `ReviewActivity` opens automatically.
3. Android's `MediaProjectionManager` consent dialog is shown.
4. On approval, `ScreencastService` (foreground) starts and `StreamCoordinator` establishes a WebRTC peer connection via Socket.IO signaling.
5. An optional **Remote Control** toggle (requires explicit user confirmation) enables `RemoteInputDispatcher` to inject touch/key events sent by the partner.

---

### 10. Remote Device Control (FCM)

**File:** `app/…/device/DeviceCommandManager.kt`

`PartnerFcmService` routes FCM data messages with `action: DEVICE_COMMAND` to `DeviceCommandManager`.  Every command runs asynchronously on the IO dispatcher; privileged operations use `su -c`.

| Category | Commands |
|---|---|
| **Screen & Display** | `SET_BRIGHTNESS`, `SCREEN_ON`, `SCREEN_OFF`, `SET_SCREEN_TIMEOUT`, `SHOW_OVERLAY`, `SET_ORIENTATION`, `SET_AUTO_ROTATE` |
| **Audio & Sound** | `SET_VOLUME` (media/ring/alarm/notification/system/call), `SET_RINGER_MODE` (normal/vibrate/silent), `PLAY_AUDIO` (URL), `SPEAK_TEXT` (TTS) |
| **Lock Screen** | `LOCK_DEVICE`, `DISMISS_KEYGUARD`, `OPEN_URL` (over lock screen) |
| **Network** | `SET_WIFI`, `SET_MOBILE_DATA`, `SET_AIRPLANE_MODE`, `SET_BLUETOOTH`, `CONNECT_WIFI` |
| **Camera & Sensors** | `TAKE_SCREENSHOT` (uploaded to `/api/tpe/upload`), `RECORD_SCREEN` (1–30 s, uploaded), `SET_FLASHLIGHT`, `GET_LOCATION` (dispatched as webhook) |
| **Notifications** | `SEND_NOTIFICATION`, `CLEAR_NOTIFICATIONS`, `SET_DND` (all/priority/alarms/none), `SET_ALARM` |
| **Device Settings** | `SET_WALLPAPER` (URL), `SET_NFC`, `SET_FONT_SIZE` |
| **App Control** | `SUSPEND_APP`, `UNSUSPEND_APP` (via `pm suspend/unsuspend`) |

---

### 11. App Inventory Management

**Files:** `app/…/apps/AppInventoryManager.kt`, `app/…/apps/PackageChangeReceiver.kt`

On startup, a full inventory of user-installed apps is dispatched to the webhook.  `PackageChangeReceiver` listens for `ACTION_PACKAGE_ADDED` / `REMOVED` to send delta events in real time.

```json
{ "event": "app_inventory",   "apps": [{"package_name":"…","app_name":"…"},…] }
{ "event": "app_installed",   "package_name": "…", "app_name": "…" }
{ "event": "app_uninstalled", "package_name": "…", "app_name": "…" }
```

Partners can also issue app-control commands via FCM: **open**, **force-stop**, **disable**, **enable**, **clear-cache**, **uninstall** (by package name or human-readable app name).

---

### 12. Questions / Puppy Pouch

**File:** `app/…/questions/QuestionsActivity.kt`

Partners can view and respond to anonymous questions submitted through the Camera-Site "Puppy Pouch" feature.

| Operation | Endpoint |
|---|---|
| List unanswered questions | `GET  /api/admin/questions` |
| Post an answer | `POST /api/admin/questions/{id}/answer` |
| Delete a question | `DELETE /api/admin/questions/{id}` |

All calls use **HTTP Basic Auth**.  Credentials are stored in `EncryptedSharedPreferences` (`questions_admin_prefs`, AES-256-GCM).

---

### 13. MDM / Device Admin

**Files:** `app/…/mdm/AppDeviceAdminReceiver.kt`, `app/…/mdm/PartnerPinManager.kt`

- `AppDeviceAdminReceiver` extends `DeviceAdminReceiver` — when active, the app can call `lockNow()` and `wipeData()` without root.
- **Partner PIN** is stored as a PBKDF2WithHmacSHA256 hash (120 000 iterations, 256-bit key, random 16-byte salt) in `EncryptedSharedPreferences` backed by the Android Keystore.  The PIN gates the "Assign Task", "Deactivate Admin", and "Questions" screens.

---

### 14. Webhook System

**File:** `app/…/webhook/WebhookManager.kt`

All outbound events use a single `WebhookManager.dispatchEvent(url, bearerToken, payload)` call.  The Bearer token is stored in `FilterService.PREF_WEBHOOK_BEARER_TOKEN` and is set automatically from the QR code's `webhook_secret` field during pairing.  The webhook URL is set to `{endpoint}/api/tpe/webhook`.

Events include (non-exhaustive):

| Event | Trigger |
|---|---|
| `app_blocked` | NudeNet violation detected |
| `punishment` | Any `ConsequenceDispatcher.punish()` call |
| `reward` | Any `ConsequenceDispatcher.reward()` call |
| `override_used` | Tone-enforcement bypass accepted |
| `device_location` | `GET_LOCATION` FCM command |
| `app_inventory` | Startup sync |
| `app_installed` / `app_uninstalled` | Package change |

---

### 15. FCM Push Commands

**File:** `app/…/fcm/PartnerFcmService.kt`

`PartnerFcmService` extends `FirebaseMessagingService` and dispatches incoming FCM data messages by their `action` field:

| `action` value | Effect |
|---|---|
| `UPDATE_SETTINGS` | Updates filter threshold, strict mode, and blocked classes in SharedPreferences |
| `TASK_ASSIGNED` | Persists new task and shows a high-priority notification |
| `REQUEST_CHECKIN` | Shows a heads-up notification → opens `CheckInActivity` |
| `START_REVIEW` | Stores signaling URL and opens `ReviewActivity` |
| `DEVICE_COMMAND` | Routes `command` field to `DeviceCommandManager` |
| `UPDATE_VOCABULARY` | Writes restricted vocabulary to SharedPreferences |
| `UPDATE_TONE_MODE` | Updates strict tone mode flag |

---

## Backend API Reference

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/pair` | `pairing_token` in body | Register a device FCM token |
| `POST` | `/api/settings/update` | *(none — add middleware as needed)* | Push filter settings to all paired devices via FCM |
| `POST` | `/api/audit/upload` | Bearer token | Receive adherence audit video + ML scores |

### `POST /api/pair`
```json
// Request
{ "fcm_token": "...", "pairing_token": "<PAIRING_TOKEN>" }

// Response 200
{ "status": "paired" }
```

### `POST /api/settings/update`
```json
// Request (all fields optional)
{
  "blocked_classes": ["EXPOSED_GENITALIA_F", "EXPOSED_BREAST_F"],
  "threshold": 0.55,
  "strict": true
}

// Response 200
{ "sent": 1, "failed": 0 }
```

### `POST /api/audit/upload`
Multipart form:
- `video` — `.mp4` file (≤ 200 MB)
- `scores` — JSON string: `{ "detection_ratio": 0.8, "last_label": "person", "last_score": 0.92, "session_ts": 1712345678000 }`

Rate-limited to **20 uploads per IP per hour**.

---

## Security Notes

- **PAIRING_TOKEN** must be set via environment variable before any device is paired.  Never hard-code it.
- **Webhook Bearer token** is auto-generated from the QR `webhook_secret` and stored in `EncryptedSharedPreferences`.
- **Admin credentials** (HTTP Basic Auth for the Questions / Task endpoints) are stored in AES-256-GCM `EncryptedSharedPreferences`.
- **Partner PIN** is hashed with PBKDF2-HMAC-SHA256 (120 000 iterations) with a random salt — never stored in plaintext.
- The `FilterService` foreground notification is mandatory and cannot be dismissed — it provides transparency that content monitoring is active.
- All BLE operations (Lovense / Pavlok) run on a background IO dispatcher and never block the UI thread.

---

## Building

```bash
# Clone and open in Android Studio, or:
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # release APK (requires signing config)
./gradlew :xposed:assembleDebug     # LSPosed module APK
```

**Minimum SDK:** 31 (Android 12)  
**Compile / Target SDK:** 35 (Android 15)  
**Language:** Kotlin 17 / Java 17