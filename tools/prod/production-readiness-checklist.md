# Production Readiness Checklist

Use this checklist before every production deployment.

## 1. Secrets and environment

- [ ] Set `ENVIRONMENT=production` (or `prod`) for Camera-Site backend.
- [ ] Set a strong `SECRET_KEY` in Camera-Site.
- [ ] Set a strong `DROOL_SALT` in Camera-Site.
- [ ] Ensure `MOCK_AUTH` is not enabled in production.
- [ ] Set `CONTROL_API_TOKEN` for Node command-route authorization.
- [ ] Set a strong `PAIRING_TOKEN` for Node pairing.
- [ ] Configure MQTT connectivity (`MQTT_BROKER_URL` or host/port credentials).

## 2. Backend startup checks

- [ ] Camera-Site starts cleanly (`uvicorn main:app`) with no fatal errors.
- [ ] Node backend starts cleanly (`node server.js`).
- [ ] Logs show no default-secret warnings in production.

## 3. Security behavior checks

- [ ] Camera-Site `/api/handler/*` rejects unauthenticated requests.
- [ ] Node command routes reject unauthorized calls.
- [ ] Partner-only navigation in app requires valid PIN.

## 4. Functional smoke checks

- [ ] Public watch session creation works and returns `ws_url`.
- [ ] Public watch websocket path accepts valid session IDs.
- [ ] Pair endpoint responds predictably for invalid token.
- [ ] Command routes respond with auth errors unless valid bearer token is supplied.

## 5. Persistence and backup

- [ ] Node `data/paired_devices.json` path is on persistent storage.
- [ ] Camera-Site SQLite DB file has backup/restore policy.
- [ ] Log retention policy is configured.

## 6. Release gates

- [ ] Run env validation script:
  - `powershell -ExecutionPolicy Bypass -File tools/prod/validate-env.ps1`
- [ ] Run smoke test script:
  - `powershell -ExecutionPolicy Bypass -File tools/prod/smoke-test.ps1`
- [ ] Verify no diagnostics errors in changed source files.
- [ ] Tag release and capture deployment notes.
