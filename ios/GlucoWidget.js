// GlucoWidget para iPhone — script para la app Scriptable.
// Muestra la última lectura de glucosa de una cuenta LibreLinkUp.
//
// Uso:
//   1. Instala "Scriptable" desde la App Store.
//   2. Crea un script nuevo, pega este archivo completo y ejecútalo una vez
//      dentro de Scriptable para configurar tu cuenta.
//   3. Añade un widget de Scriptable en la pantalla de inicio o de bloqueo
//      y elige este script.
//
// AVISO: usa la API no oficial de LibreLinkUp. Puede fallar o ir con retraso.
// No la uses para decisiones de tratamiento.

const DEFAULT_BASE = "https://api.libreview.io";
const PRODUCT = "llu.android";
const APP_VERSION = "4.16.0"; // súbela si LibreLinkUp deja de responder tras una actualización
const KEY = "glucowidget.settings";
const STALE_MIN = 20;

const COLORS = {
  low: new Color("#E53935"),
  high: new Color("#FB8C00"),
  ok: new Color("#43A047"),
  stale: new Color("#9E9E9E"),
  bg: new Color("#202124"),
  text: Color.white(),
  sub: new Color("#FFFFFF", 0.75),
};

const ARROWS = { 1: "↓", 2: "↘", 3: "→", 4: "↗", 5: "↑" };

// ---------------------------------------------------------------- ajustes

function loadSettings() {
  if (!Keychain.contains(KEY)) return {};
  try {
    return JSON.parse(Keychain.get(KEY));
  } catch (e) {
    return {};
  }
}

function saveSettings(s) {
  Keychain.set(KEY, JSON.stringify(s));
}

// ---------------------------------------------------------------- API

class LluError extends Error {}

async function request(base, path, method, body, session) {
  const req = new Request(base + path);
  req.method = method;
  req.timeoutInterval = 15;
  req.headers = {
    product: PRODUCT,
    version: APP_VERSION,
    Accept: "application/json",
    "Cache-Control": "no-cache",
    "Content-Type": "application/json",
  };
  if (session) {
    req.headers.Authorization = "Bearer " + session.token;
    req.headers["Account-Id"] = session.accountId;
  }
  if (body) req.body = JSON.stringify(body);

  let text;
  try {
    text = await req.loadString();
  } catch (e) {
    throw new LluError("Sin conexión");
  }
  const code = req.response ? req.response.statusCode : 0;
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch (e) {
    json = null;
  }
  return { code, json };
}

async function login(s) {
  let base = s.baseUrl || DEFAULT_BASE;
  for (let attempt = 0; attempt < 3; attempt++) {
    const { code, json } = await request(base, "/llu/auth/login", "POST",
      { email: s.email, password: s.password }, null);
    if (code === 429) throw new LluError("Demasiados intentos; espera unos minutos");
    if (!json) throw new LluError(`Respuesta inválida del servidor (HTTP ${code})`);

    const data = json.data || {};
    if (data.redirect) {
      if (!data.region) throw new LluError("Redirección regional sin región");
      base = `https://api-${data.region}.libreview.io`;
      continue;
    }

    switch (json.status) {
      case 0: {
        const token = data.authTicket && data.authTicket.token;
        const userId = data.user && data.user.id;
        if (!token || !userId) {
          throw new LluError("Inicio de sesión incompleto; abre LibreLinkUp y revisa avisos pendientes");
        }
        s.baseUrl = base;
        s.session = {
          baseUrl: base,
          token,
          accountId: sha256(userId),
          expires: (data.authTicket.expires || 0),
        };
        saveSettings(s);
        return s.session;
      }
      case 2:
        throw new LluError("Correo o contraseña incorrectos");
      case 4:
        throw new LluError("Abre LibreLinkUp y acepta los términos o avisos pendientes");
      default: {
        const msg = json.error && json.error.message;
        throw new LluError(msg || `Error de inicio de sesión (estado ${json.status})`);
      }
    }
  }
  throw new LluError("Demasiadas redirecciones regionales");
}

async function fetchConnections(s) {
  if (!s.email || !s.password) throw new LluError("Abre el script en Scriptable para configurarlo");

  const now = Date.now() / 1000;
  let session = s.session && s.session.expires > now + 60 ? s.session : await login(s);

  let res = await request(session.baseUrl, "/llu/connections", "GET", null, session);
  if (res.code !== 429 && !isGoodConnections(res)) {
    // A veces LibreView responde HTTP 200 con un error dentro o sin la lista:
    // la sesión guardada ya no sirve. Nueva sesión y un solo reintento.
    session = await login(s);
    res = await request(session.baseUrl, "/llu/connections", "GET", null, session);
  }
  if (res.code === 429) throw new LluError("LibreView limita las peticiones; espera unos minutos");
  if (res.code < 200 || res.code > 299 || !res.json) {
    throw new LluError(`Error al leer datos (HTTP ${res.code})`);
  }
  if (res.json.status && res.json.status !== 0) {
    const msg = res.json.error && res.json.error.message;
    throw new LluError(msg || `LibreView devolvió un error (estado ${res.json.status})`);
  }
  if (!Array.isArray(res.json.data)) throw new LluError("Respuesta inesperada de LibreView");

  // LibreView renueva el token en cada respuesta; guardamos el nuevo.
  const t = res.json.ticket;
  if (t && t.token) {
    s.session = { ...session, token: t.token, expires: t.expires || session.expires };
    saveSettings(s);
  }

  return (res.json.data || []).map((c) => ({
    patientId: c.patientId,
    name: [c.firstName, c.lastName].filter(Boolean).join(" "),
    reading: parseReading(c.glucoseMeasurement),
  }));
}

function isGoodConnections(res) {
  return res.code >= 200 && res.code <= 299 && res.json && (res.json.status || 0) === 0 &&
    Array.isArray(res.json.data) && res.json.data.length > 0;
}

function parseReading(m) {
  if (!m || typeof m.ValueInMgPerDl !== "number") return null;
  return {
    mgdl: Math.round(m.ValueInMgPerDl),
    trend: m.TrendArrow || 0,
    time: parseFactoryTimestamp(m.FactoryTimestamp),
  };
}

// FactoryTimestamp está en UTC con formato "M/d/yyyy h:mm:ss AM".
function parseFactoryTimestamp(str) {
  const m = /^(\d+)\/(\d+)\/(\d+) (\d+):(\d+):(\d+) (AM|PM)$/i.exec(str || "");
  if (!m) return 0;
  let hour = parseInt(m[4], 10) % 12;
  if (m[7].toUpperCase() === "PM") hour += 12;
  return Date.UTC(+m[3], +m[1] - 1, +m[2], hour, +m[5], +m[6]);
}

// SHA-256 en hexadecimal (la API exige el hash del id de usuario en "Account-Id").
function sha256(msg) {
  const K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ];
  const bytes = [];
  const utf8 = unescape(encodeURIComponent(msg));
  for (let i = 0; i < utf8.length; i++) bytes.push(utf8.charCodeAt(i));
  const bitLen = bytes.length * 8;
  bytes.push(0x80);
  while (bytes.length % 64 !== 56) bytes.push(0);
  const hi = Math.floor(bitLen / 0x100000000);
  const lo = bitLen >>> 0;
  for (const w of [hi, lo]) bytes.push((w >>> 24) & 255, (w >>> 16) & 255, (w >>> 8) & 255, w & 255);

  let H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  const W = new Array(64);
  const rotr = (x, n) => (x >>> n) | (x << (32 - n));
  for (let i = 0; i < bytes.length; i += 64) {
    for (let t = 0; t < 16; t++) {
      W[t] = (bytes[i + 4 * t] << 24) | (bytes[i + 4 * t + 1] << 16) | (bytes[i + 4 * t + 2] << 8) | bytes[i + 4 * t + 3];
    }
    for (let t = 16; t < 64; t++) {
      const s0 = rotr(W[t - 15], 7) ^ rotr(W[t - 15], 18) ^ (W[t - 15] >>> 3);
      const s1 = rotr(W[t - 2], 17) ^ rotr(W[t - 2], 19) ^ (W[t - 2] >>> 10);
      W[t] = (W[t - 16] + s0 + W[t - 7] + s1) | 0;
    }
    let [a, b, c, d, e, f, g, h] = H;
    for (let t = 0; t < 64; t++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const t1 = (h + S1 + ch + K[t] + W[t]) | 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0;
      d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    H = [H[0] + a, H[1] + b, H[2] + c, H[3] + d, H[4] + e, H[5] + f, H[6] + g, H[7] + h].map((x) => x | 0);
  }
  return H.map((x) => (x >>> 0).toString(16).padStart(8, "0")).join("");
}

// ---------------------------------------------------------------- lectura

async function refresh(s) {
  try {
    const list = await fetchConnections(s);
    const conn = list.find((c) => c.patientId === s.patientId) || list[0];
    if (!conn) throw new LluError("No sigues a nadie en LibreLinkUp");
    if (!conn.reading) throw new LluError("Sin lectura disponible");
    s.last = { ...conn.reading, name: conn.name };
    s.error = null;
  } catch (e) {
    s.error = e instanceof LluError ? e.message : String(e.message || e);
  }
  saveSettings(s);
  return s;
}

function formatValue(s, mgdl) {
  return s.mmol ? (mgdl / 18.0182).toFixed(1) : String(mgdl);
}

function colorFor(s, r) {
  if (!r.time || Date.now() - r.time > STALE_MIN * 60000) return COLORS.stale;
  if (r.mgdl < (s.low || 70)) return COLORS.low;
  if (r.mgdl > (s.high || 180)) return COLORS.high;
  return COLORS.ok;
}

// ---------------------------------------------------------------- widget

function buildWidget(s) {
  const family = config.widgetFamily || "small";
  const r = s.last;
  const w = new ListWidget();
  w.refreshAfterDate = new Date(Date.now() + 5 * 60000);

  if (family.startsWith("accessory")) return buildAccessory(w, s, r, family);

  w.backgroundColor = COLORS.bg;
  w.setPadding(14, 14, 14, 14);

  const unit = s.mmol ? "mmol/L" : "mg/dL";

  if (r && r.name) {
    const name = w.addText(r.name);
    name.font = Font.mediumSystemFont(12);
    name.textColor = COLORS.sub;
    name.lineLimit = 1;
  }
  w.addSpacer();

  if (!r) {
    const t = w.addText("---");
    t.font = Font.boldSystemFont(36);
    t.textColor = COLORS.text;
  } else {
    const color = colorFor(s, r);
    const row = w.addStack();
    row.centerAlignContent();
    const dot = row.addText("●");
    dot.font = Font.systemFont(14);
    dot.textColor = color;
    row.addSpacer(6);
    const value = row.addText(formatValue(s, r.mgdl));
    value.font = Font.boldRoundedSystemFont(family === "small" ? 38 : 44);
    value.textColor = color === COLORS.stale ? COLORS.stale : COLORS.text;
    value.minimumScaleFactor = 0.6;
    row.addSpacer(4);
    const arrow = row.addText(ARROWS[r.trend] || "");
    arrow.font = Font.systemFont(28);
    arrow.textColor = COLORS.text;

    const info = w.addStack();
    const u = info.addText(unit + " · ");
    u.font = Font.systemFont(12);
    u.textColor = COLORS.sub;
    if (r.time) {
      const d = info.addDate(new Date(r.time));
      d.applyRelativeStyle(); // "hace 3 min", se actualiza solo
      d.font = Font.systemFont(12);
      d.textColor = COLORS.sub;
    }
  }

  w.addSpacer();
  if (s.error) {
    const e = w.addText(s.error);
    e.font = Font.systemFont(10);
    e.textColor = COLORS.high;
    e.lineLimit = 2;
  }
  return w;
}

function buildAccessory(w, s, r, family) {
  const value = r ? formatValue(s, r.mgdl) : "---";
  const arrow = r ? ARROWS[r.trend] || "" : "";
  if (family === "accessoryInline") {
    w.addText(`${value} ${arrow}`);
    return w;
  }
  if (family === "accessoryCircular") {
    w.addAccessoryWidgetBackground = true;
    const v = w.addText(value);
    v.font = Font.boldRoundedSystemFont(18);
    v.centerAlignText();
    v.minimumScaleFactor = 0.5;
    const a = w.addText(arrow);
    a.font = Font.systemFont(14);
    a.centerAlignText();
    return w;
  }
  // accessoryRectangular
  const row = w.addStack();
  const v = row.addText(`${value} ${arrow}`);
  v.font = Font.boldRoundedSystemFont(26);
  const info = w.addStack();
  if (r && r.time) {
    const d = info.addDate(new Date(r.time));
    d.applyRelativeStyle();
    d.font = Font.systemFont(12);
  } else if (s.error) {
    const e = info.addText(s.error);
    e.font = Font.systemFont(11);
    e.lineLimit = 1;
  }
  return w;
}

// ---------------------------------------------------------------- configuración

async function ask(title, message, fields, actions) {
  const a = new Alert();
  a.title = title;
  if (message) a.message = message;
  for (const f of fields || []) {
    const tf = f.secure ? a.addSecureTextField(f.placeholder, f.value || "") : a.addTextField(f.placeholder, f.value || "");
    if (f.email) tf.setEmailAddressKeyboard();
    if (f.number) tf.setNumberPadKeyboard();
  }
  for (const act of actions) a.addAction(act);
  a.addCancelAction("Cancelar");
  const idx = await a.presentAlert();
  if (idx === -1) return null;
  return { idx, values: (fields || []).map((_, i) => a.textFieldValue(i)) };
}

async function setup(s) {
  const acc = await ask(
    "Cuenta LibreLinkUp",
    "Se guarda en el Llavero de este iPhone y solo se envía a LibreView.",
    [
      { placeholder: "Correo electrónico", value: s.email, email: true },
      { placeholder: "Contraseña", value: s.password, secure: true },
    ],
    ["Siguiente"],
  );
  if (!acc) return false;
  const [email, password] = acc.values;
  if (email.trim() !== s.email || password !== s.password) {
    s.session = null;
    s.baseUrl = null;
    s.last = null;
  }
  s.email = email.trim();
  s.password = password;

  const units = await ask("Unidades", null, [], ["mg/dL", "mmol/L"]);
  if (!units) return false;
  s.mmol = units.idx === 1;

  const lim = await ask(
    "Límites (mg/dL)",
    "Por debajo: rojo. Por encima: naranja.",
    [
      { placeholder: "Bajo", value: String(s.low || 70), number: true },
      { placeholder: "Alto", value: String(s.high || 180), number: true },
    ],
    ["Siguiente"],
  );
  if (!lim) return false;
  s.low = parseInt(lim.values[0], 10) || 70;
  s.high = parseInt(lim.values[1], 10) || 180;
  saveSettings(s);

  let list;
  try {
    list = await fetchConnections(s);
  } catch (e) {
    await ask("No se pudo conectar", e.message, [], ["OK"]);
    return false;
  }
  if (list.length === 0) {
    await ask("Conectado", "Pero no sigues a nadie en LibreLinkUp.", [], ["OK"]);
    return false;
  }
  if (list.length === 1) {
    s.patientId = list[0].patientId;
  } else {
    const pick = await ask("¿A quién mostrar?", null, [],
      list.map((c) => c.name || c.patientId));
    if (!pick) return false;
    s.patientId = list[pick.idx].patientId;
  }
  saveSettings(s);
  return true;
}

// ---------------------------------------------------------------- main

let settings = loadSettings();

if (config.runsInWidget) {
  settings = await refresh(settings);
  Script.setWidget(buildWidget(settings));
} else {
  const first = !settings.email;
  let doSetup = first;
  if (!first) {
    const choice = await ask("GlucoWidget", "¿Qué quieres hacer?", [], ["Ver vista previa", "Cambiar configuración", "Borrar mis datos"]);
    if (!choice) {
      Script.complete();
      return;
    }
    if (choice.idx === 1) doSetup = true;
    if (choice.idx === 2) {
      Keychain.remove(KEY);
      await ask("Listo", "Se borraron la cuenta y los datos guardados.", [], ["OK"]);
      Script.complete();
      return;
    }
  }
  if (!doSetup || (await setup(settings))) {
    settings = await refresh(settings);
    await buildWidget(settings).presentSmall();
  }
}
Script.complete();
