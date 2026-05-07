# TPEApp — Feature-First Overview

TPEApp is an accountability platform with three core pieces:

- **Android device app** (`app/`) for on-device enforcement and telemetry
- **Flutter UI layer** (`flutter_app/`) for modern app screens and channel bridges
- **Partner backend** (`backend/`) for pairing, command dispatch, and event intake

This README focuses on what the system can do.

---

## What the platform delivers

### 1) Secure device onboarding
- QR-based pairing flow
- Per-device identity stored and reused across app sessions
- Partner endpoint and webhook settings persisted during pairing

### 2) Real-time partner control loop
- FCM-driven command and settings updates
- Immediate device-side reaction to partner actions
- Shared command bridge between Flutter UI and native services

### 3) Content filtering and consequence engine
- Long-running filter service for image/content policy checks
- Unified punishment/reward dispatcher for compliance outcomes
- Configurable thresholds and behavior from partner-side updates

### 4) Language and behavior enforcement
- Accessibility-based text interception and replacement
- Restricted vocabulary updates pushed remotely
- Strict vs soft enforcement behavior

### 5) Adherence workflows
- Scheduled adherence sessions with guided capture and verification
- Routine audit uploads to backend endpoints
- Automated pass/fail handling with consequence integration

### 6) Daily accountability interactions
- Mood check-in submissions
- Task assignment, task status tracking, and photo verification
- Missed-deadline enforcement hooks

### 7) Live review capabilities
- Peer review screen-sharing flow
- Signaling-driven review session start
- Optional user-confirmed remote input handling

### 8) Remote device management
- Screen, audio, lock, notification, and connectivity controls
- Device actions triggered through push command routing
- Root/device-admin assisted control paths where applicable

### 9) Device inventory and app governance
- App inventory snapshots and install/uninstall deltas
- Remote app lifecycle controls
- Webhook reporting for auditability

### 10) Hardware integrations
- BLE integrations for external stimulus devices
- Standardized command dispatch for connected accessories
- Reward/punishment profile mapping into BLE operations

### 11) Security and admin controls
- Device admin integration for lock/control features
- Partner PIN protection for sensitive screens
- Encrypted preference storage for sensitive local credentials

### 12) Event and webhook pipeline
- Centralized outbound webhook dispatch
- Structured event reporting for partner-side monitoring
- Consistent event flow across filters, tasks, reviews, and device commands

---

## High-level architecture

```text
Partner Backend (Node/Express + Firebase Admin)
        ↑                        ↓
  webhook events            FCM actions/settings
        ↑                        ↓
Android services + Flutter UI + native bridges
        ↓
System integrations (Accessibility, Device Admin, BLE, MediaProjection)
```

---

## Repository layout

```text
tpeapp/
├── app/         # Native Android services, receivers, managers, bridges
├── flutter_app/ # Flutter UI, channels, and Dart services
├── backend/     # Partner control API and event intake
└── xposed/      # LSPosed/Xposed module
```

---

## Minimal development setup (brief)

- **Backend:** Node.js 18+, Firebase service account, `PAIRING_TOKEN`
- **Android app:** API 31+ device, rooted workflow where required
- **Build tooling:** Android Studio/Gradle + Flutter SDK for `flutter_app`

If you need full setup details for local deployment, see module-level docs and project configuration files in each directory.
