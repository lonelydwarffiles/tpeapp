'use strict';

/**
 * TPE Accountability Partner Dashboard — Node.js / Express backend
 *
 * Provides two API endpoints:
 *
 *   POST /api/pair
 *     Called by the Android app after it scans the partner QR code.
 *     Registers the device's MQTT client identity for command routing.
 *
 *   POST /api/settings/update
 *     Called by the Accountability Partner to push new filter settings to all
 *     paired devices via MQTT JSON payloads.
 *
 * Setup:
 *   1. Set the PAIRING_TOKEN environment variable to a long random secret
 *      (e.g. `openssl rand -hex 32`).  This token is encoded in the QR code
 *      you show to the device being paired.
 *   2. Configure MQTT_BROKER_URL (or host/port vars below).
 *   3. Run: npm install && npm start
 */

const express     = require('express');
const multer      = require('multer');
const rateLimit   = require('express-rate-limit');
const mqtt        = require('mqtt');
const path        = require('path');
const fs          = require('fs');

// -------------------------------------------------------------------
// MQTT transport configuration
// -------------------------------------------------------------------
const MQTT_BROKER_URL = process.env.MQTT_BROKER_URL ||
  `${process.env.MQTT_PROTOCOL || 'mqtt'}://${process.env.MQTT_BROKER_HOST || 'mqtt.mochii.live'}:${process.env.MQTT_BROKER_PORT || '1883'}`;
const MQTT_USERNAME = process.env.MQTT_USERNAME || undefined;
const MQTT_PASSWORD = process.env.MQTT_PASSWORD || undefined;
const MQTT_TOPIC_PREFIX_DEFAULT = process.env.MQTT_TOPIC_PREFIX || 'tpeapp/device';

const mqttClient = mqtt.connect(MQTT_BROKER_URL, {
  username: MQTT_USERNAME,
  password: MQTT_PASSWORD,
  reconnectPeriod: 5_000,
  connectTimeout: 10_000,
});

mqttClient.on('connect', () => {
  console.log(`[mqtt] Connected: ${MQTT_BROKER_URL}`);
});

mqttClient.on('reconnect', () => {
  console.log('[mqtt] Reconnecting...');
});

mqttClient.on('error', (err) => {
  console.error('[mqtt] Client error:', err?.message ?? err);
});

// -------------------------------------------------------------------
// In-memory device registry
// Replace with SQLite / PostgreSQL in production.
// -------------------------------------------------------------------

/**
 * @typedef {{ mqttClientId: string, topicPrefix: string, pairedAt: string }} DeviceRecord
 * @type {DeviceRecord[]}
 */
const pairedDevices = [];

/**
 * Ensures command payload values are strings to match the Android command map.
 */
function toStringMap(payload) {
  return Object.fromEntries(
    Object.entries(payload)
      .filter(([, value]) => value !== undefined && value !== null)
      .map(([key, value]) => {
        if (typeof value === 'string') return [key, value];
        if (Array.isArray(value) || (typeof value === 'object' && value !== null)) {
          return [key, JSON.stringify(value)];
        }
        return [key, String(value)];
      })
  );
}

function publishCommand(device, payload) {
  const topicPrefix = (device.topicPrefix || MQTT_TOPIC_PREFIX_DEFAULT).replace(/\/+$/, '');
  const topic = `${topicPrefix}/${device.mqttClientId}/commands`;
  const message = JSON.stringify(toStringMap(payload));

  return new Promise((resolve, reject) => {
    if (!mqttClient.connected) {
      reject(new Error('MQTT broker is not connected'));
      return;
    }
    mqttClient.publish(topic, message, { qos: 1, retain: false }, (err) => {
      if (err) return reject(err);
      return resolve(topic);
    });
  });
}

async function dispatchToPairedDevices(payload, logPrefix) {
  const results = await Promise.allSettled(
    pairedDevices.map(device => publishCommand(device, payload))
  );

  const sent = results.filter(r => r.status === 'fulfilled').length;
  const failed = results.length - sent;

  results.forEach((result, idx) => {
    if (result.status === 'rejected') {
      console.error(
        `[${logPrefix}] MQTT publish failed for device ${idx}:`,
        result.reason?.message ?? result.reason
      );
    }
  });

  console.log(`[${logPrefix}] Dispatched over MQTT — sent: ${sent}, failed: ${failed}`);
  return { sent, failed };
}

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
 * Allow only video/mp4 (the recorded adherence video).
 * The ML scores are sent as a plain text form field and handled via req.body,
 * so they are not subject to file filtering.
 */
const auditFileFilter = (req, file, cb) => {
  if (file.mimetype === 'video/mp4') {
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
//     "mqtt_client_id": "<stable MQTT client id from Android device>",
//     "pairing_token": "<secret shared via QR code>"
//   }
//
// Response 200: { "status": "paired" }
// Response 400: missing / invalid fields
// Response 403: invalid pairing_token
// -------------------------------------------------------------------
app.post('/api/pair', (req, res) => {
  const { mqtt_client_id, pairing_token, mqtt_topic_prefix } = req.body ?? {};
  const clientId = typeof mqtt_client_id === 'string' ? mqtt_client_id.trim() : '';

  if (!clientId) {
    return res.status(400).json({ error: 'Missing or invalid mqtt_client_id' });
  }

  if (!VALID_PAIRING_TOKENS.has(pairing_token)) {
    return res.status(403).json({ error: 'Invalid pairing_token' });
  }

  const topicPrefix = typeof mqtt_topic_prefix === 'string' && mqtt_topic_prefix.trim() !== ''
    ? mqtt_topic_prefix.trim()
    : MQTT_TOPIC_PREFIX_DEFAULT;
  const existing = pairedDevices.findIndex(d => d.mqttClientId === clientId);

  if (existing === -1) {
    pairedDevices.push({ mqttClientId: clientId, topicPrefix, pairedAt: new Date().toISOString() });
    console.log(`[pair] New device registered. Total paired: ${pairedDevices.length}`);
  } else {
    // Device refresh — update routing metadata and timestamp.
    pairedDevices[existing].topicPrefix = topicPrefix;
    pairedDevices[existing].pairedAt = new Date().toISOString();
    console.log('[pair] Known device re-paired (MQTT identity refresh).');
  }

  return res.status(200).json({ status: 'paired' });
});

// -------------------------------------------------------------------
// Rate limiter for the audit upload endpoint
// Paired devices upload once per day; allow a small burst for retries.
// -------------------------------------------------------------------
const auditUploadLimiter = rateLimit({
  windowMs: 60 * 60 * 1000, // 1 hour
  max:      20,              // 20 uploads per IP per hour
  standardHeaders: true,
  legacyHeaders:   false,
  message: { error: 'Too many audit upload requests. Please try again later.' }
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
  auditUploadLimiter,
  auditUpload.fields([{ name: 'video', maxCount: 1 }]),
  (req, res) => {
    const videoFiles = req.files?.video;

    if (!videoFiles || videoFiles.length === 0) {
      return res.status(400).json({ error: 'Missing video file part' });
    }

    // scores is a plain text form field (no filename), placed in req.body by multer.
    const scoresText = req.body?.scores;
    let scores = null;
    if (scoresText) {
      try {
        scores = JSON.parse(scoresText);
      } catch (e) {
        return res.status(400).json({ error: `Invalid scores JSON: ${e.message}` });
      }
    }

    const savedFilename  = videoFiles[0].filename;
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
// to all registered devices via MQTT command topic.
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

  const { sent, failed } = await dispatchToPairedDevices(dataPayload, 'settings/update');
  return res.status(200).json({ sent, failed });
});

// -------------------------------------------------------------------
// POST /api/command/open-url
//
// Sends an OPEN_URL MQTT command to all paired devices.
//
// Body (JSON):
//   { "url": "https://..." }
//
// The URL must use the http or https scheme.
//
// Response 200: { "sent": <n>, "failed": <n> }
// Response 400: missing or invalid url
// Response 404: no paired devices
// -------------------------------------------------------------------
const URL_REGEX = /^https?:\/\/.+/;

app.post('/api/command/open-url', async (req, res) => {
  const { url } = req.body ?? {};

  if (!url || typeof url !== 'string' || !URL_REGEX.test(url)) {
    return res.status(400).json({ error: 'url must be a valid http(s) URL' });
  }

  if (pairedDevices.length === 0) {
    return res.status(404).json({ error: 'No paired devices registered' });
  }

  const dataPayload = { action: 'OPEN_URL', url };
  const { sent, failed } = await dispatchToPairedDevices(dataPayload, 'command/open-url');
  return res.status(200).json({ sent, failed });
});

// -------------------------------------------------------------------
// POST /api/command/set-wallpaper
//
// Sends a SET_WALLPAPER MQTT command to all paired devices.
//
// Body (JSON):
//   {
//     "url":      "https://…",   // single image for home + lock (backward-compat)
//     "target":   "home|lock|both", // optional, default "both"
//     "home_url": "https://…",   // optional — home-screen image (overrides url)
//     "lock_url": "https://…"    // optional — lock-screen image (overrides url)
//   }
//
// At least one of url, home_url, or lock_url must be present.
// All supplied URLs must use the http or https scheme.
//
// Response 200: { "sent": <n>, "failed": <n> }
// Response 400: missing/invalid url(s) or invalid target
// Response 404: no paired devices
// -------------------------------------------------------------------
app.post('/api/command/set-wallpaper', async (req, res) => {
  const { url, target, home_url, lock_url } = req.body ?? {};

  // effectiveHomeUrl falls back to the legacy `url` so a single-URL request sets
  // the home screen.  effectiveLockUrl does NOT fall back: when lock_url is absent
  // the app-side logic (DeviceCommandManager) applies homeUrl to both surfaces,
  // so there is no need to duplicate it in the command payload.
  const effectiveHomeUrl = home_url || url || null;
  const effectiveLockUrl = lock_url || null;

  if (!effectiveHomeUrl && !effectiveLockUrl) {
    return res.status(400).json({ error: 'At least one of url, home_url, or lock_url is required' });
  }

  const allowedTargets = ['home', 'lock', 'both'];
  const resolvedTarget = allowedTargets.includes(target) ? target : 'both';

  for (const u of [effectiveHomeUrl, effectiveLockUrl].filter(Boolean)) {
    if (!URL_REGEX.test(u)) {
      return res.status(400).json({ error: `Invalid URL: ${u}` });
    }
  }

  if (pairedDevices.length === 0) {
    return res.status(404).json({ error: 'No paired devices registered' });
  }

  // Build command payload.
  const dataPayload = { action: 'SET_WALLPAPER', target: resolvedTarget };
  if (effectiveHomeUrl) dataPayload.home_url = effectiveHomeUrl;
  if (effectiveLockUrl) dataPayload.lock_url = effectiveLockUrl;
  // Preserve legacy `url` field so older app builds continue to work.
  if (url) dataPayload.url = url;

  const { sent, failed } = await dispatchToPairedDevices(dataPayload, 'command/set-wallpaper');
  return res.status(200).json({ sent, failed });
});

// -------------------------------------------------------------------
// POST /api/command/play-audio
//
// Sends a PLAY_AUDIO MQTT command to all paired devices.
//
// Body (JSON):
//   {
//     "url":  "https://…/clip.mp3",  // required — http(s) URL to audio file
//     "loop": true                    // optional, default false
//   }
//
// When loop=true the clip plays continuously over any other media until a
// STOP_AUDIO command is received.
//
// Response 200: { "sent": <n>, "failed": <n> }
// Response 400: missing or invalid url
// Response 404: no paired devices
// -------------------------------------------------------------------
app.post('/api/command/play-audio', async (req, res) => {
  const { url, loop = false } = req.body ?? {};

  if (!url || typeof url !== 'string' || !URL_REGEX.test(url)) {
    return res.status(400).json({ error: 'url must be a valid http(s) URL' });
  }

  if (pairedDevices.length === 0) {
    return res.status(404).json({ error: 'No paired devices registered' });
  }

  const dataPayload = { action: 'PLAY_AUDIO', url, loop: String(Boolean(loop)) };
  const { sent, failed } = await dispatchToPairedDevices(dataPayload, 'command/play-audio');
  return res.status(200).json({ sent, failed });
});

// -------------------------------------------------------------------
// POST /api/command/stop-audio
//
// Sends a STOP_AUDIO MQTT command to all paired devices, stopping any
// audio clip currently playing via play-audio (including looping clips).
//
// Response 200: { "sent": <n>, "failed": <n> }
// Response 404: no paired devices
// -------------------------------------------------------------------
app.post('/api/command/stop-audio', async (req, res) => {
  if (pairedDevices.length === 0) {
    return res.status(404).json({ error: 'No paired devices registered' });
  }

  const dataPayload = { action: 'STOP_AUDIO' };
  const { sent, failed } = await dispatchToPairedDevices(dataPayload, 'command/stop-audio');
  return res.status(200).json({ sent, failed });
});

// -------------------------------------------------------------------
// Start server
// -------------------------------------------------------------------
const PORT = parseInt(process.env.PORT ?? '3000', 10);
app.listen(PORT, () => {
  console.log(`TPE Partner Dashboard listening on port ${PORT}`);
});
