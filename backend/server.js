'use strict';

/**
 * TPE Accountability Partner Dashboard — Node.js / Express backend
 *
 * Provides two API endpoints:
 *
 *   POST /api/pair
 *     Called by the Android app after it scans the partner QR code.
 *     Registers the device's FCM token so the dashboard can push settings.
 *
 *   POST /api/settings/update
 *     Called by the Accountability Partner to push new filter settings to all
 *     paired devices via Firebase Cloud Messaging (FCM) data messages.
 *
 * Setup:
 *   1. Place your Firebase service-account JSON at ./serviceAccountKey.json
 *      OR set the GOOGLE_APPLICATION_CREDENTIALS environment variable to its
 *      absolute path.
 *   2. Set the PAIRING_TOKEN environment variable to a long random secret
 *      (e.g. `openssl rand -hex 32`).  This token is encoded in the QR code
 *      you show to the device being paired.
 *   3. Run:  npm install && npm start
 */

const express = require('express');
const admin   = require('firebase-admin');
const multer  = require('multer');
const path    = require('path');
const fs      = require('fs');

// -------------------------------------------------------------------
// Firebase Admin SDK initialisation
// Uses Application Default Credentials when GOOGLE_APPLICATION_CREDENTIALS
// is set; falls back to an explicit service-account file if present.
// -------------------------------------------------------------------
const serviceAccountPath = path.join(__dirname, 'serviceAccountKey.json');
let credential;
try {
  // Explicit service-account file takes priority (easier local dev).
  credential = admin.credential.cert(require(serviceAccountPath));
} catch (_) {
  // Fall back to ADC (Cloud Run, GitHub Actions, etc.)
  credential = admin.credential.applicationDefault();
}

admin.initializeApp({ credential });

// -------------------------------------------------------------------
// In-memory device registry
// Replace with SQLite / PostgreSQL in production.
// -------------------------------------------------------------------

/**
 * @typedef {{ fcmToken: string, pairedAt: string }} DeviceRecord
 * @type {DeviceRecord[]}
 */
const pairedDevices = [];

// -------------------------------------------------------------------
// Pre-shared pairing tokens
// Generate with:  openssl rand -hex 32
// Store in the PAIRING_TOKEN env var; never hard-code in source.
// -------------------------------------------------------------------
const PAIRING_TOKEN = process.env.PAIRING_TOKEN;
if (!PAIRING_TOKEN) {
  console.warn(
    '[warn] PAIRING_TOKEN env var is not set.\n' +
    '       Set it to a long random secret before pairing any devices.'
  );
}
const VALID_PAIRING_TOKENS = new Set([PAIRING_TOKEN].filter(Boolean));

// -------------------------------------------------------------------
// Multer — multipart storage for adherence audit video uploads
// Files are stored under ./uploads/audit/ and named by device + timestamp.
// -------------------------------------------------------------------
const AUDIT_UPLOAD_DIR = path.join(__dirname, 'uploads', 'audit');
fs.mkdirSync(AUDIT_UPLOAD_DIR, { recursive: true });

const auditStorage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, AUDIT_UPLOAD_DIR),
  filename:    (req, file, cb) => {
    const ts   = Date.now();
    const ext  = path.extname(file.originalname) || '.mp4';
    cb(null, `audit_${ts}${ext}`);
  }
});

/**
 * Allow only video/mp4 files (the recorded adherence video) and
 * application/json (the ML scores part).  Reject everything else so that
 * clients cannot upload arbitrary file types.
 */
const auditFileFilter = (req, file, cb) => {
  const allowed = ['video/mp4', 'application/json', 'application/octet-stream'];
  if (allowed.includes(file.mimetype)) {
    cb(null, true);
  } else {
    cb(new Error(`Unsupported file type: ${file.mimetype}`), false);
  }
};

const auditUpload = multer({
  storage:    auditStorage,
  fileFilter: auditFileFilter,
  limits:     { fileSize: 200 * 1024 * 1024 } // 200 MB max per file
});

// -------------------------------------------------------------------
// Express application
// -------------------------------------------------------------------
const app = express();
app.use(express.json());

// -------------------------------------------------------------------
// POST /api/pair
//
// Body (JSON):
//   {
//     "fcm_token":     "<Firebase registration token from the Android device>",
//     "pairing_token": "<secret shared via QR code>"
//   }
//
// Response 200: { "status": "paired" }
// Response 400: missing / invalid fields
// Response 403: invalid pairing_token
// -------------------------------------------------------------------
app.post('/api/pair', (req, res) => {
  const { fcm_token, pairing_token } = req.body ?? {};

  if (!fcm_token || typeof fcm_token !== 'string' || fcm_token.trim() === '') {
    return res.status(400).json({ error: 'Missing or invalid fcm_token' });
  }

  if (!VALID_PAIRING_TOKENS.has(pairing_token)) {
    return res.status(403).json({ error: 'Invalid pairing_token' });
  }

  const token = fcm_token.trim();
  const existing = pairedDevices.findIndex(d => d.fcmToken === token);

  if (existing === -1) {
    pairedDevices.push({ fcmToken: token, pairedAt: new Date().toISOString() });
    console.log(`[pair] New device registered. Total paired: ${pairedDevices.length}`);
  } else {
    // Token refresh — update the timestamp.
    pairedDevices[existing].pairedAt = new Date().toISOString();
    console.log('[pair] Known device re-paired (token refresh).');
  }

  return res.status(200).json({ status: 'paired' });
});

// -------------------------------------------------------------------
// POST /api/audit/upload
//
// Receives the adherence audit artifact from AuditUploadWorker:
//   - video  : multipart file field (the recorded .mp4)
//   - scores : multipart field containing a JSON string with ML scores:
//              {
//                "detection_ratio": <float>,
//                "last_label":      <string>,
//                "last_score":      <float>,
//                "session_ts":      <epoch ms>
//              }
//
// Response 200: { "status": "received", "file": "<stored filename>", "scores": {...} }
// Response 400: missing fields / JSON parse error
// Response 500: storage error
// -------------------------------------------------------------------
app.post(
  '/api/audit/upload',
  auditUpload.fields([
    { name: 'video',  maxCount: 1 },
    { name: 'scores', maxCount: 1 }
  ]),
  (req, res) => {
    const videoFiles  = req.files?.video;
    const scoresFiles = req.files?.scores;

    // scores may also arrive as a text field (FormData string part).
    const scoresText = req.body?.scores;

    if (!videoFiles || videoFiles.length === 0) {
      return res.status(400).json({ error: 'Missing video file part' });
    }

    let scores = null;
    try {
      // scores part can be a saved temp file (application/json) or a plain
      // text body field — try both.
      if (scoresFiles && scoresFiles.length > 0) {
        const raw = fs.readFileSync(scoresFiles[0].path, 'utf8');
        scores = JSON.parse(raw);
        // Clean up temp scores file; only the .mp4 is kept for audit.
        fs.unlink(scoresFiles[0].path, () => {});
      } else if (scoresText) {
        scores = JSON.parse(scoresText);
      }
    } catch (e) {
      return res.status(400).json({ error: `Invalid scores JSON: ${e.message}` });
    }

    const savedFilename = videoFiles[0].filename;
    const detectionRatio = scores?.detection_ratio ?? null;

    console.log(
      `[audit/upload] Received: file=${savedFilename} ` +
      `detection_ratio=${detectionRatio} ` +
      `session_ts=${scores?.session_ts ?? 'unknown'}`
    );

    return res.status(200).json({
      status: 'received',
      file:   savedFilename,
      scores
    });
  }
);

// -------------------------------------------------------------------
// POST /api/settings/update
//
// Called by the Accountability Partner to push updated filter settings
// to all registered devices via FCM data messages.
//
// Body (JSON) — all fields optional:
//   {
//     "blocked_classes": ["EXPOSED_GENITALIA_F", "EXPOSED_BREAST_F"],
//     "threshold":       0.55,
//     "strict":          true
//   }
//
// Response 200: { "sent": <n>, "failed": <n> }
// Response 404: no paired devices
// -------------------------------------------------------------------
app.post('/api/settings/update', async (req, res) => {
  if (pairedDevices.length === 0) {
    return res.status(404).json({ error: 'No paired devices registered' });
  }

  const { blocked_classes, threshold, strict } = req.body ?? {};

  // FCM data messages only support string values.
  const dataPayload = { action: 'UPDATE_SETTINGS' };

  if (Array.isArray(blocked_classes) && blocked_classes.length > 0) {
    // Serialise the label array so the Android app can parse it with JSONArray.
    dataPayload.blocked_classes = JSON.stringify(blocked_classes);
  }

  if (typeof threshold === 'number' && threshold >= 0 && threshold <= 1) {
    dataPayload.threshold = String(threshold);
  }

  if (typeof strict === 'boolean') {
    dataPayload.strict = String(strict);
  }

  // Dispatch to every paired device; collect outcomes.
  const results = await Promise.allSettled(
    pairedDevices.map(device =>
      admin.messaging().send({ token: device.fcmToken, data: dataPayload })
    )
  );

  const sent   = results.filter(r => r.status === 'fulfilled').length;
  const failed = results.length - sent;

  results.forEach((result, idx) => {
    if (result.status === 'rejected') {
      console.error(
        `[settings/update] FCM delivery failed for device ${idx}:`,
        result.reason?.message ?? result.reason
      );
    }
  });

  console.log(`[settings/update] Dispatched — sent: ${sent}, failed: ${failed}`);
  return res.status(200).json({ sent, failed });
});

// -------------------------------------------------------------------
// Start server
// -------------------------------------------------------------------
const PORT = parseInt(process.env.PORT ?? '3000', 10);
app.listen(PORT, () => {
  console.log(`TPE Partner Dashboard listening on port ${PORT}`);
});
