# TPE App — Flutter Front-End

This directory contains the Flutter (Dart) UI layer for the TPE Accountability App.

## Architecture

```
tpeapp/
├── app/                          ← Existing Android module (native Kotlin)
│   └── src/main/java/com/tpeapp/
│       ├── bridge/               ← NEW: MethodChannel host bridges
│       │   ├── TpeFlutterActivity.kt     Flutter entry-point (extends FlutterActivity)
│       │   ├── FilterServiceChannel.kt   com.tpeapp/filter_service
│       │   ├── DeviceAdminChannel.kt     com.tpeapp/device_admin
│       │   ├── PartnerPinChannel.kt      com.tpeapp/partner_pin
│       │   ├── BleChannel.kt             com.tpeapp/ble + com.tpeapp/ble_events
│       │   ├── FcmChannel.kt             com.tpeapp/fcm + com.tpeapp/fcm_events
│       │   └── DeviceCommandChannel.kt   com.tpeapp/device_commands
│       ├── service/FilterService.kt      ← KEPT NATIVE (AIDL + TFLite)
│       ├── mdm/                          ← KEPT NATIVE (Device Admin)
│       ├── fcm/PartnerFcmService.kt      ← KEPT NATIVE (FCM handler)
│       ├── device/DeviceCommandManager.kt← KEPT NATIVE (root commands)
│       ├── consequence/                  ← KEPT NATIVE (punishment/reward)
│       ├── ble/                          ← KEPT NATIVE (used by FilterService)
│       └── xposed/ (separate module)     ← KEPT NATIVE (LSPosed hooks)
│
└── flutter_app/                  ← NEW: Flutter project
    ├── pubspec.yaml
    └── lib/
        ├── main.dart
        ├── app.dart
        ├── models/               ← Dart data models (Task, ChatMessage, RitualStep, …)
        ├── channels/             ← Dart-side MethodChannel / EventChannel clients
        │   ├── filter_service_channel.dart
        │   ├── device_admin_channel.dart
        │   ├── partner_pin_channel.dart
        │   ├── ble_channel.dart
        │   ├── fcm_channel.dart
        │   └── device_command_channel.dart
        ├── services/             ← Pure Dart services (replaces Kotlin repositories)
        │   ├── api_service.dart          HTTP calls (replaces OkHttp)
        │   ├── ble_service.dart          Dart BLE (replaces BleManager for UI layer)
        │   ├── task_repository.dart
        │   ├── chat_repository.dart
        │   └── ritual_repository.dart
        └── screens/              ← Flutter UI screens (replaces Android Activities)
            ├── pairing_screen.dart       PairingActivity → Flutter + mobile_scanner
            ├── home_screen.dart          HandlerChatActivity → Flutter chat UI
            ├── check_in_screen.dart      CheckInActivity
            ├── task_list_screen.dart     TaskListActivity
            ├── task_verification_screen.dart  TaskVerificationActivity
            ├── assign_task_screen.dart   AssignTaskActivity (partner)
            ├── questions_screen.dart     QuestionsActivity (partner)
            ├── ritual_checklist_screen.dart   RitualChecklistActivity
            └── settings_screen.dart      MainActivity (admin settings)
```

## What stays in native Kotlin (unchanged)

| Component | Reason |
|-----------|--------|
| `xposed/` module | LSPosed hooks must run inside target app processes |
| `FilterService` + AIDL | Long-lived background service; NudeNetClassifier (TFLite) |
| `AppDeviceAdminReceiver` | Must extend `DeviceAdminReceiver` |
| `PartnerPinManager` | EncryptedSharedPreferences + PBKDF2 — stays in Keystore |
| `PartnerFcmService` | Extends `FirebaseMessagingService` |
| `DeviceCommandManager` | Privileged root shell commands |
| `ConsequenceDispatcher` | Called from background services, not UI |
| `BleManager` / `LovenseManager` / `PavlokManager` | Shared with ConsequenceDispatcher |
| All background workers / receivers | Boot, alarms, oversight, adherence, WebRTC |

## MethodChannels

| Dart channel name | Kotlin bridge | Purpose |
|---|---|---|
| `com.tpeapp/filter_service` | `FilterServiceChannel` | Start service, threshold, strict mode, webhook |
| `com.tpeapp/device_admin` | `DeviceAdminChannel` | Admin status, activate/deactivate, PIN ops |
| `com.tpeapp/partner_pin` | `PartnerPinChannel` | Standalone PIN management |
| `com.tpeapp/ble` | `BleChannel` | Lovense & Pavlok commands |
| `com.tpeapp/ble_events` | `BleChannel` (EventChannel) | BLE connection state → Dart |
| `com.tpeapp/fcm` | `FcmChannel` | FCM token get/refresh |
| `com.tpeapp/fcm_events` | `FcmChannel` (EventChannel) | FCM pushes → Dart UI |
| `com.tpeapp/device_commands` | `DeviceCommandChannel` | Remote device controls |

## Building

1. Install Flutter SDK ≥ 3.22.0.
2. Run `flutter pub get` inside `flutter_app/`.
3. Build via `flutter build apk` from `flutter_app/` **or** use the existing
   Gradle wrapper from the root: the Flutter Gradle plugin bridges both build
   systems (see `settings.gradle.kts` for configuration notes).
4. The APK includes both the Flutter engine and all native Kotlin services.

## Key Flutter packages

| Package | Replaces |
|---------|---------|
| `http` | OkHttp (HTTP API calls) |
| `flutter_blue_plus` | BleManager / LovenseManager / PavlokManager (UI layer) |
| `mobile_scanner` | CameraX + ML Kit barcode (QR pairing) |
| `shared_preferences` | SharedPreferences (local storage) |
| `flutter_secure_storage` | EncryptedSharedPreferences (admin credentials) |
| `image_picker` | `ActivityResultContracts.TakePicture` (task photo proof) |
| `provider` | ViewModel / LiveData (state management) |
