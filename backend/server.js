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
const http        = require('http');
const path        = require('path');
const fs          = require('fs');
const crypto      = require('crypto');
const { WebSocketServer } = require('ws');

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
const PAIRED_DEVICES_PATH = path.join(__dirname, 'data', 'paired_devices.json');
fs.mkdirSync(path.dirname(PAIRED_DEVICES_PATH), { recursive: true });

function loadPairedDevices() {
  try {
    if (!fs.existsSync(PAIRED_DEVICES_PATH)) return [];
    const raw = fs.readFileSync(PAIRED_DEVICES_PATH, 'utf8');
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((d) =>
      d && typeof d.mqttClientId === 'string' && d.mqttClientId.trim() !== ''
    );
  } catch (err) {
    console.error('[pair] Failed to load paired devices store:', err?.message ?? err);
    return [];
  }
}

function savePairedDevices() {
  try {
    fs.writeFileSync(PAIRED_DEVICES_PATH, JSON.stringify(pairedDevices, null, 2), 'utf8');
  } catch (err) {
    console.error('[pair] Failed to persist paired devices store:', err?.message ?? err);
  }
}

const pairedDevices = loadPairedDevices();

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
const CONTROL_API_TOKEN = process.env.CONTROL_API_TOKEN;
const WEBHOOK_BEARER_TOKEN = process.env.WEBHOOK_BEARER_TOKEN || CONTROL_API_TOKEN;
const PAIRING_CODE = (process.env.PAIRING_CODE || '').trim().toUpperCase();
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || '';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '';
if (!CONTROL_API_TOKEN) {
  console.warn(
    '[warn] CONTROL_API_TOKEN env var is not set. Command routes will reject requests until it is configured.'
  );
}
if (!WEBHOOK_BEARER_TOKEN) {
  console.warn(
    '[warn] WEBHOOK_BEARER_TOKEN env var is not set. Device-report routes will reject requests until it is configured.'
  );
}

const BACKEND_STATE_PATH = path.join(__dirname, 'data', 'partner_state.json');
const BACKEND_STATE_DEFAULT = {
  checkins: [],
  taskStatusReports: [],
  webhookEvents: [],
  uploads: [],
  vitals: [],
  deviceStatus: [],
  questions: [],
  assignedTasks: [],
  commandAcks: [],
};

function loadBackendState() {
  try {
    if (!fs.existsSync(BACKEND_STATE_PATH)) return { ...BACKEND_STATE_DEFAULT };
    const raw = fs.readFileSync(BACKEND_STATE_PATH, 'utf8');
    const parsed = JSON.parse(raw);
    return {
      checkins: Array.isArray(parsed?.checkins) ? parsed.checkins : [],
      taskStatusReports: Array.isArray(parsed?.taskStatusReports) ? parsed.taskStatusReports : [],
      webhookEvents: Array.isArray(parsed?.webhookEvents) ? parsed.webhookEvents : [],
      uploads: Array.isArray(parsed?.uploads) ? parsed.uploads : [],
      vitals: Array.isArray(parsed?.vitals) ? parsed.vitals : [],
      deviceStatus: Array.isArray(parsed?.deviceStatus) ? parsed.deviceStatus : [],
      questions: Array.isArray(parsed?.questions) ? parsed.questions : [],
      assignedTasks: Array.isArray(parsed?.assignedTasks) ? parsed.assignedTasks : [],
      commandAcks: Array.isArray(parsed?.commandAcks) ? parsed.commandAcks : [],
    };
  } catch (err) {
    console.error('[state] Failed to load partner state store:', err?.message ?? err);
    return { ...BACKEND_STATE_DEFAULT };
  }
}

function saveBackendState() {
  try {
    fs.writeFileSync(BACKEND_STATE_PATH, JSON.stringify(backendState, null, 2), 'utf8');
  } catch (err) {
    console.error('[state] Failed to persist partner state store:', err?.message ?? err);
  }
}

const backendState = loadBackendState();

function requireControlAuth(req, res, next) {
  if (!CONTROL_API_TOKEN) {
    return res.status(503).json({ error: 'Server control auth is not configured' });
  }

  const authHeader = typeof req.headers.authorization === 'string'
    ? req.headers.authorization
    : '';
  const [scheme, token] = authHeader.split(' ');
  if (scheme !== 'Bearer' || !token || token !== CONTROL_API_TOKEN) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  return next();
}

function requireWebhookAuth(req, res, next) {
  if (!WEBHOOK_BEARER_TOKEN) {
    return res.status(503).json({ error: 'Server webhook auth is not configured' });
  }

  const authHeader = typeof req.headers.authorization === 'string'
    ? req.headers.authorization
    : '';
  const [scheme, token] = authHeader.split(' ');
  if (scheme !== 'Bearer' || !token || token !== WEBHOOK_BEARER_TOKEN) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  return next();
}

function requireAdminBasicAuth(req, res, next) {
  if (!ADMIN_USERNAME || !ADMIN_PASSWORD) {
    return res.status(503).json({ error: 'Server admin basic auth is not configured' });
  }

  const authHeader = typeof req.headers.authorization === 'string'
    ? req.headers.authorization
    : '';
  if (!authHeader.startsWith('Basic ')) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  const encoded = authHeader.slice('Basic '.length).trim();
  let decoded;
  try {
    decoded = Buffer.from(encoded, 'base64').toString('utf8');
  } catch (_err) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  const idx = decoded.indexOf(':');
  const user = idx >= 0 ? decoded.slice(0, idx) : '';
  const pass = idx >= 0 ? decoded.slice(idx + 1) : '';

  if (user !== ADMIN_USERNAME || pass !== ADMIN_PASSWORD) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  return next();
}

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
app.use(express.urlencoded({ extended: false }));
app.set('trust proxy', true);

// Active /ws device sockets keyed by device_id.
const hotMicSockets = new Map();

function registerHotMicSocket(deviceId, ws) {
  const set = hotMicSockets.get(deviceId) || new Set();
  set.add(ws);
  hotMicSockets.set(deviceId, set);
}

function unregisterHotMicSocket(deviceId, ws) {
  const set = hotMicSockets.get(deviceId);
  if (!set) return;
  set.delete(ws);
  if (set.size === 0) {
    hotMicSockets.delete(deviceId);
  }
}

function countHotMicSockets() {
  let total = 0;
  for (const set of hotMicSockets.values()) {
    total += set.size;
  }
  return total;
}

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
    savePairedDevices();
    console.log(`[pair] New device registered. Total paired: ${pairedDevices.length}`);
  } else {
    // Device refresh — update routing metadata and timestamp.
    pairedDevices[existing].topicPrefix = topicPrefix;
    pairedDevices[existing].pairedAt = new Date().toISOString();
    savePairedDevices();
    console.log('[pair] Known device re-paired (MQTT identity refresh).');
  }

  return res.status(200).json({ status: 'paired' });
});

// -------------------------------------------------------------------
// POST /api/pair/code
//
// Allows manual pairing via short pairing code entry.
// -------------------------------------------------------------------
app.post('/api/pair/code', (req, res) => {
  const { pairing_code, mqtt_client_id, mqtt_topic_prefix } = req.body ?? {};
  const code = typeof pairing_code === 'string' ? pairing_code.trim().toUpperCase() : '';
  const clientId = typeof mqtt_client_id === 'string' ? mqtt_client_id.trim() : '';

  if (!clientId) {
    return res.status(400).json({ error: 'Missing or invalid mqtt_client_id' });
  }

  if (!code) {
    return res.status(400).json({ error: 'Missing pairing_code' });
  }

  const tokenAsCode = PAIRING_TOKEN ? PAIRING_TOKEN.trim().toUpperCase() : '';
  const validCode = (PAIRING_CODE && code === PAIRING_CODE) || (tokenAsCode && code === tokenAsCode);
  if (!validCode) {
    return res.status(403).json({ error: 'Invalid pairing_code' });
  }

  const topicPrefix = typeof mqtt_topic_prefix === 'string' && mqtt_topic_prefix.trim() !== ''
    ? mqtt_topic_prefix.trim()
    : MQTT_TOPIC_PREFIX_DEFAULT;
  const existing = pairedDevices.findIndex(d => d.mqttClientId === clientId);
  if (existing === -1) {
    pairedDevices.push({ mqttClientId: clientId, topicPrefix, pairedAt: new Date().toISOString() });
  } else {
    pairedDevices[existing].topicPrefix = topicPrefix;
    pairedDevices[existing].pairedAt = new Date().toISOString();
  }
  savePairedDevices();

  return res.status(200).json({
    status: 'paired',
    mqtt_topic_prefix: topicPrefix,
  });
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
// Device-facing TPE routes expected by Flutter ApiService
// -------------------------------------------------------------------

const taskStatusUpload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 25 * 1024 * 1024 },
});

const TPE_UPLOAD_DIR = path.join(__dirname, 'uploads', 'tpe');
fs.mkdirSync(TPE_UPLOAD_DIR, { recursive: true });

const tpeUploadStorage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, TPE_UPLOAD_DIR),
  filename: (_req, file, cb) => {
    const ext = path.extname(file.originalname || '').trim() || '.bin';
    cb(null, `upload_${Date.now()}_${crypto.randomUUID()}${ext}`);
  },
});

const tpeUploadMulter = multer({
  storage: tpeUploadStorage,
  limits: { fileSize: 50 * 1024 * 1024 },
});

const tpeUploadRawParser = express.raw({
  type: ['image/*', 'video/*', 'application/octet-stream'],
  limit: '50mb',
});

function inferExtFromMime(contentType) {
  const type = (contentType || '').toLowerCase();
  if (type.includes('image/jpeg')) return '.jpg';
  if (type.includes('image/png')) return '.png';
  if (type.includes('image/webp')) return '.webp';
  if (type.includes('video/mp4')) return '.mp4';
  if (type.includes('video/webm')) return '.webm';
  return '.bin';
}

function parseNumber(v) {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string' && v.trim() !== '') {
    const n = Number(v);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

function appendBounded(list, value, limit = 2000) {
  list.push(value);
  if (list.length > limit) {
    list.splice(0, list.length - limit);
  }
}

function selectDeviceId(reqBody, reqHeaders) {
  const bodyId = reqBody?.device_id ?? reqBody?.deviceId;
  const headerId = reqHeaders['x-device-id'];
  const asString = (val) => (typeof val === 'string' ? val.trim() : '');
  return asString(bodyId) || asString(headerId) || null;
}

function latestDeviceStatusById(deviceId) {
  for (let i = backendState.deviceStatus.length - 1; i >= 0; i -= 1) {
    const row = backendState.deviceStatus[i];
    if (row?.device_id === deviceId) return row;
  }
  return null;
}

// -------------------------------------------------------------------
// Device event and telemetry ingestion routes used by app/webhook stack
// -------------------------------------------------------------------

app.post('/api/tpe/webhook', requireWebhookAuth, (req, res) => {
  const payload = (req.body && typeof req.body === 'object') ? req.body : {};
  const entry = {
    id: crypto.randomUUID(),
    event: typeof payload.event === 'string' ? payload.event : 'unknown',
    reason: typeof payload.reason === 'string' ? payload.reason : null,
    payload,
    device_id: selectDeviceId(payload, req.headers),
    created_at: new Date().toISOString(),
  };
  appendBounded(backendState.webhookEvents, entry, 5000);
  saveBackendState();
  return res.status(200).json({ status: 'received', event_id: entry.id });
});

app.post(
  '/api/tpe/upload',
  requireWebhookAuth,
  (req, res, next) => {
    const contentType = String(req.headers['content-type'] || '').toLowerCase();
    if (contentType.startsWith('multipart/form-data')) {
      return tpeUploadMulter.any()(req, res, next);
    }
    return tpeUploadRawParser(req, res, next);
  },
  (req, res) => {
    const contentType = String(req.headers['content-type'] || '').toLowerCase();
    const files = Array.isArray(req.files) ? req.files : [];

    let storedFile = null;
    let sizeBytes = 0;
    let mimeType = null;

    if (files.length > 0) {
      const file = files[0];
      storedFile = file.filename;
      sizeBytes = Number(file.size) || 0;
      mimeType = file.mimetype || null;
    } else if (Buffer.isBuffer(req.body) && req.body.length > 0) {
      const ext = inferExtFromMime(contentType);
      const filename = `upload_${Date.now()}_${crypto.randomUUID()}${ext}`;
      fs.writeFileSync(path.join(TPE_UPLOAD_DIR, filename), req.body);
      storedFile = filename;
      sizeBytes = req.body.length;
      mimeType = contentType || null;
    } else {
      return res.status(400).json({ error: 'Missing upload payload' });
    }

    const meta = {
      id: crypto.randomUUID(),
      file: storedFile,
      kind: typeof req.body?.kind === 'string' ? req.body.kind : null,
      timestamp: typeof req.body?.timestamp === 'string' ? req.body.timestamp : null,
      content_type: mimeType,
      size_bytes: sizeBytes,
      device_id: selectDeviceId(req.body, req.headers),
      created_at: new Date().toISOString(),
    };
    appendBounded(backendState.uploads, meta, 5000);
    saveBackendState();

    return res.status(200).json({
      status: 'received',
      file: storedFile,
      size_bytes: sizeBytes,
    });
  }
);

app.post('/api/vitals/sync', requireWebhookAuth, (req, res) => {
  const payload = (req.body && typeof req.body === 'object') ? req.body : {};
  const deviceId = selectDeviceId(payload, req.headers);
  const nowIso = new Date().toISOString();

  const records = [];

  if (Array.isArray(payload.readings)) {
    for (const r of payload.readings) {
      if (!r || typeof r !== 'object') continue;
      records.push({
        type: 'reading',
        heart_rate: parseNumber(r.heart_rate),
        steps: parseNumber(r.steps),
        timestamp: typeof r.timestamp === 'string' ? r.timestamp : nowIso,
        raw: r,
      });
    }
  }

  if (Array.isArray(payload.vitals)) {
    for (const r of payload.vitals) {
      if (!r || typeof r !== 'object') continue;
      records.push({
        type: typeof r.type === 'string' ? r.type : 'unknown',
        value: parseNumber(r.value),
        unit: typeof r.unit === 'string' ? r.unit : null,
        start_ms: parseNumber(r.start_ms),
        end_ms: parseNumber(r.end_ms),
        raw: r,
      });
    }
  }

  if (records.length === 0) {
    return res.status(400).json({ error: 'No vitals/readings provided' });
  }

  for (const r of records) {
    appendBounded(backendState.vitals, {
      id: crypto.randomUUID(),
      device_id: deviceId,
      created_at: nowIso,
      ...r,
    }, 10000);
  }

  const hrSamples = backendState.vitals
    .filter((r) => (r.type === 'heart_rate' && Number.isFinite(r.value)) || Number.isFinite(r.heart_rate))
    .map((r) => Number.isFinite(r.value) ? r.value : r.heart_rate);
  const baseline = hrSamples.length > 0
    ? Number((hrSamples.reduce((sum, v) => sum + v, 0) / hrSamples.length).toFixed(2))
    : null;

  let alertStatus = null;
  const latestHr = [...records].reverse().find((r) =>
    (r.type === 'heart_rate' && Number.isFinite(r.value)) || Number.isFinite(r.heart_rate)
  );
  const latestHrValue = latestHr
    ? (Number.isFinite(latestHr.value) ? latestHr.value : latestHr.heart_rate)
    : null;
  if (baseline !== null && latestHrValue !== null) {
    if (latestHrValue >= baseline * 1.35) alertStatus = 'high_heart_rate';
    if (latestHrValue <= baseline * 0.65) alertStatus = 'low_heart_rate';
  }

  saveBackendState();
  return res.status(200).json({
    stored: records.length,
    baseline,
    alert_status: alertStatus,
  });
});

app.post('/api/handler/device-status', requireWebhookAuth, (req, res) => {
  const payload = (req.body && typeof req.body === 'object') ? req.body : {};
  const deviceId = selectDeviceId(payload, req.headers);
  if (!deviceId) {
    return res.status(400).json({ error: 'device_id is required' });
  }

  const battery =
    parseNumber(payload.battery_pct) ??
    parseNumber(payload.battery) ??
    parseNumber(payload.battery_level) ??
    parseNumber(payload.batteryPercent) ??
    parseNumber(payload.battery_percentage);
  const lat = parseNumber(payload.lat) ?? parseNumber(payload.latitude);
  const lon =
    parseNumber(payload.lon) ??
    parseNumber(payload.lng) ??
    parseNumber(payload.longitude);
  const aiLabel =
    (typeof payload.ai_label === 'string' ? payload.ai_label : null) ??
    (typeof payload.aiLabel === 'string' ? payload.aiLabel : null) ??
    (typeof payload.label === 'string' ? payload.label : null);
  const aiScore =
    parseNumber(payload.ai_score) ??
    parseNumber(payload.aiScore) ??
    parseNumber(payload.score);

  let aiAlert = null;
  if (typeof payload.ai_alert === 'boolean') aiAlert = payload.ai_alert;
  if (typeof payload.aiAlert === 'boolean') aiAlert = payload.aiAlert;
  if (typeof payload.ai_filter_hit === 'boolean') aiAlert = payload.ai_filter_hit;
  if (typeof payload.alert === 'boolean') aiAlert = payload.alert;

  const entry = {
    id: crypto.randomUUID(),
    device_id: deviceId,
    device_name: typeof payload.device_name === 'string' ? payload.device_name : (typeof payload.deviceName === 'string' ? payload.deviceName : null),
    battery_pct: battery,
    lat,
    lon,
    ai_alert: aiAlert,
    ai_label: aiLabel,
    ai_score: aiScore,
    raw: payload,
    created_at: new Date().toISOString(),
  };

  appendBounded(backendState.deviceStatus, entry, 5000);
  saveBackendState();

  return res.status(200).json({ status: 'received', device_id: deviceId });
});

app.post('/api/tpe/checkin', requireWebhookAuth, (req, res) => {
  const moodScore = Number(req.body?.mood_score);
  const note = typeof req.body?.note === 'string' ? req.body.note : '';
  if (!Number.isFinite(moodScore)) {
    return res.status(400).json({ error: 'mood_score is required' });
  }

  const entry = {
    id: crypto.randomUUID(),
    mood_score: moodScore,
    note,
    device_id: req.headers['x-device-id'] || null,
    created_at: new Date().toISOString(),
  };
  backendState.checkins.push(entry);
  saveBackendState();
  return res.status(200).json({ status: 'received', checkin_id: entry.id });
});

app.post('/api/tpe/task/status', requireWebhookAuth, taskStatusUpload.single('photo'), (req, res) => {
  const taskId = typeof req.body?.task_id === 'string' ? req.body.task_id.trim() : '';
  const status = typeof req.body?.status === 'string' ? req.body.status.trim().toUpperCase() : '';
  if (!taskId || !status) {
    return res.status(400).json({ error: 'task_id and status are required' });
  }

  const entry = {
    id: crypto.randomUUID(),
    task_id: taskId,
    status,
    has_photo: Boolean(req.file),
    photo_filename: req.file?.originalname ?? null,
    device_id: req.headers['x-device-id'] || null,
    created_at: new Date().toISOString(),
  };
  backendState.taskStatusReports.push(entry);
  saveBackendState();
  return res.status(200).json({ status: 'received', report_id: entry.id });
});

app.post('/api/tpe/commands/:commandId/ack', requireWebhookAuth, (req, res) => {
  const commandId = req.params.commandId;
  const status = typeof req.body?.status === 'string' ? req.body.status : '';
  if (!commandId || !status) {
    return res.status(400).json({ error: 'commandId and status are required' });
  }

  const entry = {
    id: crypto.randomUUID(),
    command_id: commandId,
    status,
    error_code: req.body?.error_code ?? null,
    error_message: req.body?.error_message ?? null,
    telemetry: req.body?.telemetry ?? null,
    device_id: req.headers['x-device-id'] || null,
    created_at: new Date().toISOString(),
  };
  backendState.commandAcks.push(entry);
  saveBackendState();
  return res.status(200).json({ status: 'received', ack_id: entry.id });
});

// -------------------------------------------------------------------
// Admin routes expected by Flutter ApiService
// -------------------------------------------------------------------

app.get('/api/admin/questions', requireAdminBasicAuth, (_req, res) => {
  const unanswered = backendState.questions.filter(q => !q.answer);
  return res.status(200).json(unanswered);
});

app.post('/api/admin/questions/:id/answer', requireAdminBasicAuth, (req, res) => {
  const id = req.params.id;
  const answer = typeof req.body?.answer === 'string' ? req.body.answer.trim() : '';
  if (!answer) {
    return res.status(400).json({ error: 'answer is required' });
  }

  const idx = backendState.questions.findIndex(q => q.id === id);
  if (idx === -1) {
    return res.status(404).json({ error: 'Question not found' });
  }

  backendState.questions[idx].answer = answer;
  backendState.questions[idx].answered_at = new Date().toISOString();
  saveBackendState();
  return res.status(200).json({ status: 'answered', id });
});

app.delete('/api/admin/questions/:id', requireAdminBasicAuth, (req, res) => {
  const id = req.params.id;
  const before = backendState.questions.length;
  backendState.questions = backendState.questions.filter(q => q.id !== id);
  if (backendState.questions.length === before) {
    return res.status(404).json({ error: 'Question not found' });
  }
  saveBackendState();
  return res.status(200).json({ status: 'deleted', id });
});

app.post('/api/admin/tpe/tasks', requireAdminBasicAuth, (req, res) => {
  const title = typeof req.body?.title === 'string' ? req.body.title.trim() : '';
  const description = typeof req.body?.description === 'string' ? req.body.description.trim() : '';
  const deadlineMs = Number(req.body?.deadline_ms);
  if (!title || !Number.isFinite(deadlineMs)) {
    return res.status(400).json({ error: 'title and deadline_ms are required' });
  }

  const task = {
    id: crypto.randomUUID(),
    title,
    description,
    deadline_ms: deadlineMs,
    created_at: new Date().toISOString(),
  };
  backendState.assignedTasks.push(task);
  saveBackendState();
  return res.status(201).json({ status: 'created', task });
});

// -------------------------------------------------------------------
// Handler visibility and websocket command relay endpoints
// -------------------------------------------------------------------

app.get('/api/handler/status', requireControlAuth, (_req, res) => {
  return res.status(200).json({
    mqtt_connected: mqttClient.connected,
    paired_devices: pairedDevices.length,
    ws_connections: countHotMicSockets(),
    recent: {
      webhook_events: backendState.webhookEvents.length,
      uploads: backendState.uploads.length,
      vitals: backendState.vitals.length,
      checkins: backendState.checkins.length,
      task_status_reports: backendState.taskStatusReports.length,
      command_acks: backendState.commandAcks.length,
    },
  });
});

app.get('/api/handler/devices', requireControlAuth, (_req, res) => {
  const rows = pairedDevices.map((d) => {
    const latest = latestDeviceStatusById(d.mqttClientId);
    return {
      device_id: d.mqttClientId,
      topic_prefix: d.topicPrefix,
      paired_at: d.pairedAt,
      ws_connected: hotMicSockets.has(d.mqttClientId),
      last_status: latest,
    };
  });
  return res.status(200).json(rows);
});

app.get('/api/handler/tpe/events', requireControlAuth, (req, res) => {
  const limit = Math.min(Math.max(parseInt(String(req.query.limit || '100'), 10) || 100, 1), 500);
  const rows = backendState.webhookEvents.slice(-limit).reverse();
  return res.status(200).json(rows);
});

app.get('/api/handler/tpe/audits', requireControlAuth, (req, res) => {
  const limit = Math.min(Math.max(parseInt(String(req.query.limit || '100'), 10) || 100, 1), 500);
  const rows = backendState.uploads
    .filter((u) => (u?.kind || '').toLowerCase() === 'silent_selfie' || String(u?.file || '').startsWith('audit_'))
    .slice(-limit)
    .reverse();
  return res.status(200).json(rows);
});

app.get('/api/vitals/history', requireControlAuth, (req, res) => {
  const limit = Math.min(Math.max(parseInt(String(req.query.limit || '250'), 10) || 250, 1), 2000);
  const filterDevice = typeof req.query.device_id === 'string' ? req.query.device_id.trim() : '';
  const rows = backendState.vitals
    .filter((r) => !filterDevice || r.device_id === filterDevice)
    .slice(-limit)
    .reverse();
  return res.status(200).json(rows);
});

app.post('/api/handler/ws/command', requireControlAuth, (req, res) => {
  const command = typeof req.body?.command === 'string' ? req.body.command.trim() : '';
  const deviceId = typeof req.body?.device_id === 'string' ? req.body.device_id.trim() : '';
  const payload = req.body?.payload && typeof req.body.payload === 'object' ? req.body.payload : {};

  if (!command) {
    return res.status(400).json({ error: 'command is required' });
  }

  const frame = JSON.stringify({ command, ...payload });
  let targets = [];
  if (deviceId) {
    targets = Array.from(hotMicSockets.get(deviceId) || []);
  } else {
    for (const set of hotMicSockets.values()) {
      targets.push(...Array.from(set));
    }
  }

  targets.forEach((ws) => {
    try {
      ws.send(frame);
    } catch (err) {
      console.error('[ws] Failed to relay command frame:', err?.message ?? err);
    }
  });

  return res.status(200).json({
    status: 'sent',
    command,
    device_id: deviceId || null,
    sent: targets.length,
  });
});

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
app.post('/api/settings/update', requireControlAuth, async (req, res) => {
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

app.post('/api/command/open-url', requireControlAuth, async (req, res) => {
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
app.post('/api/command/set-wallpaper', requireControlAuth, async (req, res) => {
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
app.post('/api/command/play-audio', requireControlAuth, async (req, res) => {
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
app.post('/api/command/stop-audio', requireControlAuth, async (req, res) => {
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
const server = http.createServer(app);
const wsServer = new WebSocketServer({ server, path: '/ws' });

wsServer.on('connection', (ws, req) => {
  let query;
  try {
    query = new URL(req.url, 'http://localhost').searchParams;
  } catch (_err) {
    ws.close(1008, 'invalid_request');
    return;
  }

  const providedSecret = (query.get('secret') || '').trim();
  const deviceId = (query.get('device_id') || '').trim();

  if (WEBHOOK_BEARER_TOKEN && providedSecret !== WEBHOOK_BEARER_TOKEN) {
    ws.close(4001, 'unauthorized');
    return;
  }
  if (!deviceId) {
    ws.close(1008, 'missing_device_id');
    return;
  }

  registerHotMicSocket(deviceId, ws);
  console.log(`[ws] Connected device ${deviceId}; active sockets=${countHotMicSockets()}`);

  ws.on('message', (message, isBinary) => {
    if (isBinary) {
      return;
    }
    try {
      const parsed = JSON.parse(String(message));
      if (parsed?.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong', ts: Date.now() }));
      }
    } catch (_err) {
      // Ignore malformed text frames.
    }
  });

  ws.on('close', () => {
    unregisterHotMicSocket(deviceId, ws);
    console.log(`[ws] Disconnected device ${deviceId}; active sockets=${countHotMicSockets()}`);
  });

  ws.on('error', (err) => {
    console.error(`[ws] Socket error for ${deviceId}:`, err?.message ?? err);
  });
});

const PORT = parseInt(process.env.PORT ?? '3000', 10);
server.listen(PORT, () => {
  console.log(`TPE Partner Dashboard listening on port ${PORT}`);
});
