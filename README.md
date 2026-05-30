# TPEApp — Current Feature Map

This repository contains an Android accountability app with a Flutter UI, a minimal partner backend example, and an LSPosed module.

## Repository structure

```text
tpeapp/
├── app/         # Native Android services, receivers, workers, managers, bridges
├── flutter_app/ # Flutter UI + Dart services + MethodChannel clients
├── backend/     # Example Node/Express partner backend
└── xposed/      # LSPosed hooks that integrate with FilterService via AIDL
```

## What is implemented today

### 1) Pairing and device identity
- QR pairing flow in Flutter (`PairingScreen`) and native (`PairingActivity`).
- Persists:
  - `is_paired`
  - `partner_endpoint_url`
  - `webhook_url`
  - `mqtt_client_id`
  - stable `device_id` (UUID v4)
- Pairing posts to `POST /api/pair` and starts `FilterService` + persistent MQTT service.

### 2) Native command + settings control (via MQTT)
- `PartnerMqttService` handles many partner actions, including:
  - filter/tone/vocabulary updates
  - task/check-in/review triggers
  - app lifecycle controls (open/disable/enable/uninstall/suspend)
  - device controls (screen, volume, lock, connectivity, DND, alarms, wallpaper, etc.)
  - vault updates and locks
- Commands execute through native managers such as `DeviceCommandManager`, `TaskRepository`, `RitualRepository`, and others.

### 3) Content filtering + consequence pipeline
- Foreground `FilterService` exposes AIDL (`IFilterService`) for image scanning.
- NudeNet TFLite classifier with runtime threshold + strict mode preferences.
- Webhook dispatch for filter events via `WebhookManager`.
- Consequence actions (reward/punishment) integrated with BLE managers.

### 4) Tone/language enforcement
- `ToneEnforcementService` + `TpeCapabilityService` accessibility paths.
- Restricted vocabulary + strict/soft behavior persisted in preferences.
- Xposed `InputConnectionHook` enforces redaction behavior and emits violation broadcasts to the app.

### 5) Review / screen-share / remote input
- Native review stack: `ReviewActivity`, `ScreencastService`, `StreamCoordinator`, `TpeCapabilityService` (gesture injector).
- Flutter review stack: `ScreenShareScreen` + `ScreenShareService`.
- WebRTC signaling uses plain WebSocket envelopes (`join`, `offer`, `answer`, `ice-candidate`) at `/api/tpe/signal/{sessionId}`.

### 6) Accountability workflows
- Daily check-ins (`CheckInScreen` / `CheckInActivity`).
- Tasks:
  - assign tasks
  - list pending/completed/missed tasks
  - submit completion with optional photo proof
- Questions module for partner-side fetch/answer/delete.
- Ritual checklist flow with optional photo-required steps.
- Adherence kiosk/alarm/vision/audit worker components are present in native code.

### 7) Password vault
- Flutter `PasswordVaultScreen` and native vault channel/manager support:
  - add/edit/delete credentials (PIN-gated)
  - timed reveal
  - lock entry / lock all
  - CSV/JSON import
  - autofill service integration (`VaultAutofillService`)
- Vault command handling is also present in `PartnerMqttService`.

### 8) Health + vitals sync
- Flutter `HealthService` reads Health Connect Heart Rate + Steps.
- `VitalsSyncService` schedules 15-minute WorkManager sync to `POST /api/vitals/sync` when enabled.

### 9) BLE and device integrations
- Native BLE managers for Lovense/Pavlok (`BleManager`, `LovenseManager`, `PavlokManager`).
- Flutter BLE service (`BleService`) for direct UI-layer scanning/commands.
- Scheduled Lovense receiver and consequence integration are implemented.

### 10) Telemetry and monitoring components
- App inventory and package-change monitoring.
- Battery and location reporting components.
- Boot receiver and multiple alarm/worker pipelines.
- Central webhook utilities with device ID injection.

## Flutter UI currently included

Primary screens in `flutter_app/lib/screens`:
- Pairing
- Home/Handler chat
- Daily Check-In
- Task list / task verification / assign task
- Questions
- Settings (admin/filter/handler/health/remote-control/text-replacement/vault settings)
- Screen share
- Password vault
- Ritual checklist

## Backend in this repository (important)

`backend/server.js` currently exposes:
- `POST /api/pair`
- `POST /api/pair/code`
- `POST /api/audit/upload`
- `POST /api/tpe/webhook`
- `POST /api/tpe/upload`
- `POST /api/vitals/sync`
- `POST /api/handler/device-status`
- `POST /api/settings/update`
- `POST /api/command/open-url`
- `POST /api/command/set-wallpaper`
- `POST /api/command/play-audio`
- `POST /api/command/stop-audio`
- `POST /api/tpe/checkin`
- `POST /api/tpe/task/status`
- `POST /api/tpe/commands/:commandId/ack`
- `GET /api/admin/questions`
- `POST /api/admin/questions/:id/answer`
- `DELETE /api/admin/questions/:id`
- `POST /api/admin/tpe/tasks`
- `GET /api/handler/status`
- `GET /api/handler/devices`
- `GET /api/handler/tpe/events`
- `GET /api/handler/tpe/audits`
- `GET /api/vitals/history`
- `POST /api/handler/ws/command`
- `WS /ws?secret=<token>&device_id=<device_id>`

These routes cover the partner flows used by Flutter `ApiService`.

## High-level architecture

```text
Flutter UI (screens/services/channels)
        ↓↑ MethodChannels
Native Android services/managers/workers
        ↓↑
MQTT commands, WebRTC/WebSocket signaling, webhook/event HTTP
        ↓
Partner backend APIs
```
