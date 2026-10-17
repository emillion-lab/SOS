// SOS — помощ наоколо. Паник бутон за всеки, без регистрация.
// Телефонът се разпознава по ANDROID_ID и получава номер „Помощ N". Няма име, телефон или номер на кола.
// Тревогата тръгва веднага до всички на линия в радиус 7 км. Подателят избира дали те виждат
// точната точка или само района (~300 м), и може да прикачи гласово съобщение до 20 сек.

const RADIUS_KM = 7;
const STALE_MS = 10 * 60 * 1000;          // позиция по-стара от това не се брои
const ALARM_TTL_MS = 30 * 60 * 1000;      // тревога изтича сама след 30 мин
const SOS_COOLDOWN_MS = 10 * 60 * 1000;   // нова тревога най-много веднъж на 10 мин от устройство
const FALSE_VOTES = 3;                    // толкова различни „Фалшива тревога" спират устройството
const MUTE_MS = 24 * 3600 * 1000;         // ... за 24 часа
const LOG_KEEP_MS = 30 * 24 * 3600 * 1000; // дневникът за админа пази 30 дни
const NEW_DEVICES_PER_IP_HOUR = 30;       // мобилните мрежи делят IP между много хора — таванът е широк
const AUDIO_MAX_B64 = 400000;             // ~300 KB звук; 20 сек AAC е ~60–80 KB
const AUDIO_CHUNK = 100000;
const CELL_DEG = 0.0027;                  // ~300 м клетка за „само района"
// Подписаното APK се строи в mvr-worker (там е ключът за Cloudflare, от който се чете ключът за подписване).
const APK_URL = 'https://github.com/emillion-lab/mvr-worker/releases/download/sos-apk-latest/SOS.apk';

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === '/health') return json({ ok: true, v: 3 });
    if (url.pathname === '/' && req.method === 'GET') return html(HOME_HTML);
    if (url.pathname === '/SOS.apk') return Response.redirect(APK_URL, 302);
    return env.HUB.get(env.HUB.idFromName('sofia')).fetch(req);
  },
};

export class Hub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.db = state.storage;
  }

  // Собствен админ ключ на SOS (Worker secret SOS_ADMIN_TOKEN), отделен от fish.taxi.
  // Създава се от mvr-worker → sos-admin-token.yml; копие има в Cloudflare KV sos-signing → admin-token.
  // Без него админ API-то е изключено.
  async adminToken() {
    const t = this.env.SOS_ADMIN_TOKEN || '';
    return t.length >= 32 ? t : '';
  }

  async fetch(req) {
    const url = new URL(req.url);
    const p = url.pathname;
    if (req.method === 'OPTIONS') return cors(new Response(null, { status: 204 }));
    try {
      if (p === '/register' && req.method === 'POST')
        return cors(await this.register(await req.json().catch(() => ({})), req));
      if (p === '/ws') return await this.connect(url, req);
      if (p.startsWith('/admin/')) {
        const tok = await this.adminToken();
        if (!tok) return cors(json({ error: 'админ ключът не е настроен' }, 503));
        if (!same(req.headers.get('x-admin') || '', tok)) return cors(json({ error: 'нямаш достъп' }, 401));
        const body = req.method === 'POST' ? await req.json().catch(() => ({})) : {};
        if (p === '/admin/list') return cors(await this.adminList());
        if (p === '/admin/block') return cors(await this.adminBlock(body));
      }
      return cors(json({ error: 'няма такъв път' }, 404));
    } catch (e) {
      return cors(json({ error: String((e && e.message) || e) }, 500));
    }
  }

  // ---------- устройство ----------
  async register(body, req) {
    const device = String((body && body.device) || '').toLowerCase();
    if (!/^[0-9a-f]{8,32}$/.test(device)) return json({ error: 'невалидно устройство' }, 400);
    const now = Date.now();
    let d = await this.db.get('dev:' + device);
    if (!d) {
      const ip = req.headers.get('cf-connecting-ip') || 'x';
      const rk = 'reg:' + ip + ':' + Math.floor(now / 3600e3);
      const cnt = ((await this.db.get(rk)) || 0) + 1;
      if (cnt > NEW_DEVICES_PER_IP_HOUR) return json({ error: 'Твърде много нови устройства от тази мрежа. Опитай след час.' }, 429);
      await this.db.put(rk, cnt);
      const n = ((await this.db.get('seq')) || 0) + 1;
      await this.db.put('seq', n);
      await this.db.put('num:' + n, device);
      d = { n, created: now, blocked: false, mutedUntil: 0, sess: null, alarms: 0, last: now };
    }
    if (d.blocked) return json({ error: 'Това устройство е спряно от администратора.' }, 403);
    if (d.sess) await this.kick(d.sess, 'Приложението е стартирано наново');
    const token = hex(crypto.getRandomValues(new Uint8Array(32)));
    d.sess = token;
    d.last = now;
    await this.db.put('dev:' + device, d);
    await this.db.put('sess:' + token, device);
    return json({ token, n: d.n });
  }

  async kick(token, why) {
    await this.db.delete('sess:' + token);
    for (const w of this.state.getWebSockets()) {
      if ((w.deserializeAttachment() || {}).sess === token) {
        try { w.send(JSON.stringify({ t: 'kicked', why })); w.close(4001, 'kicked'); } catch {}
      }
    }
  }

  // ---------- админ ----------
  async adminList() {
    const online = new Set(this.state.getWebSockets().map((w) => (w.deserializeAttachment() || {}).dev));
    const devices = [];
    for (const [k, d] of await this.db.list({ prefix: 'dev:' })) {
      devices.push({ n: d.n, online: online.has(k.slice(4)), last: d.last, created: d.created,
        alarms: d.alarms || 0, blocked: !!d.blocked, mutedUntil: d.mutedUntil || 0 });
    }
    devices.sort((a, b) => b.last - a.last);
    const log = [...(await this.db.list({ prefix: 'log:', reverse: true, limit: 200 })).values()];
    const active = (await this.activeAlarms()).map((a) => ({ n: a.n, ts: a.ts, exact: a.exact, helpers: a.acks.length, falses: a.falses.length, audio: !!a.audio }));
    return json({ online: online.size, devices: devices.slice(0, 500), active, log });
  }

  async adminBlock(body) {
    const device = await this.db.get('num:' + Number(body.n));
    const d = device && (await this.db.get('dev:' + device));
    if (!d) return json({ error: 'няма такова устройство' }, 404);
    d.blocked = !!body.blocked;
    if (!d.blocked) d.mutedUntil = 0;
    await this.db.put('dev:' + device, d);
    if (d.blocked) {
      if (d.sess) await this.kick(d.sess, 'Устройството е спряно от администратора');
      const al = await this.db.get('alarm:' + device);
      if (al) await this.endAlarm(al, 'blocked');
    }
    return json({ ok: true, n: d.n, blocked: d.blocked });
  }

  // ---------- WebSocket ----------
  async connect(url, req) {
    if (req.headers.get('Upgrade') !== 'websocket') return json({ error: 'трябва websocket' }, 426);
    const token = url.searchParams.get('token') || '';
    const device = token && (await this.db.get('sess:' + token));
    const d = device && (await this.db.get('dev:' + device));
    if (!d || d.blocked) return json({ error: 'невалидна сесия' }, 401);
    d.last = Date.now();
    await this.db.put('dev:' + device, d);
    const pair = new WebSocketPair();
    const ws = pair[1];
    this.state.acceptWebSocket(ws);
    ws.serializeAttachment({ sess: token, dev: device, n: d.n, lat: null, lon: null, ts: 0, known: [], receive: true });
    ws.send(JSON.stringify({ t: 'hello', n: d.n }));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws, raw) {
    let m;
    try { m = JSON.parse(raw); } catch { return; }
    const a = ws.deserializeAttachment();
    if (!a) return;
    const now = Date.now();

    if (m.t === 'prefs') {
      a.receive = m.receive !== false;
      ws.serializeAttachment(a);
      return;
    }

    if (m.t === 'loc' && valid(m.lat, m.lon)) {
      a.lat = +m.lat; a.lon = +m.lon; a.ts = now;
      ws.serializeAttachment(a);
      // моята тревога върви с мен; при „само района" се праща само когато сменя клетката
      const mine = await this.db.get('alarm:' + a.dev);
      if (mine) {
        const before = pubKey(mine);
        mine.lat = a.lat; mine.lon = a.lon; mine.upd = now;
        await this.db.put('alarm:' + a.dev, mine);
        if (pubKey(mine) !== before) this.broadcast(mine);
      }
      // активни тревоги, в чийто радиус току-що съм влязъл
      if (a.receive) {
        for (const al of await this.activeAlarms()) {
          if (al.dev !== a.dev && !a.known.includes(al.id) && km(a, al) <= RADIUS_KM) this.sendAlert(ws, al);
        }
      }
      return;
    }

    if (m.t === 'sos') {
      if (!valid(m.lat, m.lon)) return send(ws, { t: 'error', msg: 'Няма GPS позиция — изчакай секунда' });
      const d = await this.db.get('dev:' + a.dev);
      if (!d || d.blocked) return send(ws, { t: 'error', msg: 'Устройството е спряно' });
      if (d.mutedUntil > now) return send(ws, { t: 'muted', until: d.mutedUntil });
      const existing = await this.db.get('alarm:' + a.dev);
      const last = (await this.db.get('cool:' + a.dev)) || 0;
      if (!existing && now - last < SOS_COOLDOWN_MS)
        return send(ws, { t: 'error', msg: 'Нова тревога може след ' + Math.ceil((SOS_COOLDOWN_MS - (now - last)) / 60000) + ' мин' });
      a.lat = +m.lat; a.lon = +m.lon; a.ts = now;
      ws.serializeAttachment(a);
      const al = existing || {
        id: crypto.randomUUID().slice(0, 8), dev: a.dev, n: a.n, ts: now, acks: [], falses: [], audio: false, mime: '',
      };
      al.lat = a.lat; al.lon = a.lon; al.upd = now;
      al.exact = !!m.exact;
      await this.db.put('alarm:' + a.dev, al);
      if (!existing) {
        await this.db.put('cool:' + a.dev, now);
        d.alarms = (d.alarms || 0) + 1;
        await this.db.put('dev:' + a.dev, d);
      }
      const reached = this.broadcast(al);
      return send(ws, { t: 'sent', id: al.id, reached, exact: al.exact });
    }

    if (m.t === 'exact') {
      const al = await this.db.get('alarm:' + a.dev);
      if (!al) return;
      al.exact = !!m.on;
      await this.db.put('alarm:' + a.dev, al);
      this.broadcast(al);
      return send(ws, { t: 'exactSet', on: al.exact });
    }

    if (m.t === 'audio') {
      const al = await this.db.get('alarm:' + a.dev);
      if (!al) return send(ws, { t: 'error', msg: 'Няма активна тревога за гласа' });
      const b64 = String(m.b64 || '');
      if (!b64 || b64.length > AUDIO_MAX_B64 || !/^[A-Za-z0-9+/=]+$/.test(b64))
        return send(ws, { t: 'error', msg: 'Гласовото съобщение е твърде дълго' });
      await this.dropAudio(al.id);
      const parts = Math.ceil(b64.length / AUDIO_CHUNK);
      for (let i = 0; i < parts; i++) await this.db.put('audio:' + al.id + ':' + i, b64.slice(i * AUDIO_CHUNK, (i + 1) * AUDIO_CHUNK));
      await this.db.put('audio:' + al.id, { parts, mime: String(m.mime || 'audio/mp4').slice(0, 40) });
      al.audio = true; al.mime = String(m.mime || 'audio/mp4').slice(0, 40); al.upd = now;
      await this.db.put('alarm:' + a.dev, al);
      this.broadcast(al);
      return send(ws, { t: 'audioSaved', id: al.id });
    }

    if (m.t === 'getaudio' && m.id) {
      const meta = await this.db.get('audio:' + m.id);
      if (!meta || !a.known.includes(m.id)) return send(ws, { t: 'error', msg: 'Гласовото съобщение вече не е налично' });
      let b64 = '';
      for (let i = 0; i < meta.parts; i++) b64 += (await this.db.get('audio:' + m.id + ':' + i)) || '';
      return send(ws, { t: 'audio', id: m.id, b64, mime: meta.mime });
    }

    if (m.t === 'cancel') {
      const al = await this.db.get('alarm:' + a.dev);
      if (al) await this.endAlarm(al, 'cancelled');
      return send(ws, { t: 'cancelled' });
    }

    if (m.t === 'ack' && m.id) {
      const al = await this.byId(m.id);
      if (!al || al.dev === a.dev || !a.known.includes(al.id)) return;
      if (!al.acks.includes(a.n)) { al.acks.push(a.n); await this.db.put('alarm:' + al.dev, al); }
      const d = valid(a.lat, a.lon) ? Math.round(km(a, al) * 10) / 10 : null;
      this.toDevice(al.dev, { t: 'ack', id: al.id, n: a.n, km: d, count: al.acks.length });
      this.broadcast(al);
      return;
    }

    if (m.t === 'false' && m.id) {
      const al = await this.byId(m.id);
      if (!al || al.dev === a.dev || !a.known.includes(al.id)) return;
      if (!al.falses.includes(a.n)) al.falses.push(a.n);
      await this.db.put('alarm:' + al.dev, al);
      send(ws, { t: 'falseNoted', id: al.id });
      if (al.falses.length >= FALSE_VOTES) {
        const d = await this.db.get('dev:' + al.dev);
        if (d) { d.mutedUntil = now + MUTE_MS; await this.db.put('dev:' + al.dev, d); }
        this.toDevice(al.dev, { t: 'muted', until: now + MUTE_MS });
        await this.endAlarm(al, 'false');
      }
    }
  }

  async webSocketClose(ws, code) { try { ws.close(code === 1005 ? 1000 : code); } catch {} }
  async webSocketError() {}

  // ---------- тревоги ----------
  async byId(id) {
    for (const al of await this.activeAlarms()) if (al.id === id) return al;
    return null;
  }

  async activeAlarms() {
    const out = [];
    const now = Date.now();
    for (const al of (await this.db.list({ prefix: 'alarm:' })).values()) {
      if (now - al.ts > ALARM_TTL_MS) { await this.endAlarm(al, 'expired'); continue; }
      out.push(al);
    }
    return out;
  }

  async endAlarm(al, reason) {
    await this.db.delete('alarm:' + al.dev);
    await this.dropAudio(al.id);
    const msg = JSON.stringify({ t: 'cancel', id: al.id, reason });
    for (const w of this.state.getWebSockets()) try { w.send(msg); } catch {}
    // дневник за админа: без координати и без звук
    const now = Date.now();
    await this.db.put('log:' + String(al.ts).padStart(13, '0') + ':' + al.id, {
      ts: al.ts, end: now, n: al.n, exact: !!al.exact, audio: !!al.audio,
      helpers: al.acks.length, falses: al.falses.length, reason,
    });
    const old = await this.db.list({ prefix: 'log:', end: 'log:' + String(now - LOG_KEEP_MS).padStart(13, '0'), limit: 200 });
    if (old.size) await this.db.delete([...old.keys()]);
  }

  async dropAudio(id) {
    const meta = await this.db.get('audio:' + id);
    if (!meta) return;
    const keys = ['audio:' + id];
    for (let i = 0; i < meta.parts; i++) keys.push('audio:' + id + ':' + i);
    await this.db.delete(keys);
  }

  toDevice(dev, o) {
    for (const w of this.state.getWebSockets()) if ((w.deserializeAttachment() || {}).dev === dev) send(w, o);
  }

  // праща тревогата на всички на линия в радиуса; връща колко са
  broadcast(al) {
    let n = 0;
    const now = Date.now();
    for (const w of this.state.getWebSockets()) {
      const a = w.deserializeAttachment();
      if (!a || a.dev === al.dev || !a.receive || !valid(a.lat, a.lon) || now - a.ts > STALE_MS) continue;
      if (km(a, al) > RADIUS_KM) continue;
      this.sendAlert(w, al);
      n++;
    }
    return n;
  }

  sendAlert(w, al) {
    const a = w.deserializeAttachment();
    const p = pub(al);
    send(w, {
      t: 'alert', id: al.id, n: al.n, lat: p.lat, lon: p.lon, exact: !!al.exact, ts: al.ts,
      km: Math.round(km(a, p) * 10) / 10, helpers: al.acks.length, audio: !!al.audio,
    });
    if (!a.known.includes(al.id)) {
      a.known = [...a.known, al.id].slice(-20);
      w.serializeAttachment(a);
    }
  }
}

// ---------- позиция за получателите ----------
// „Само района": центърът на клетка ~300×300 м. Стабилна клетка, а не случаен шум —
// иначе с много обновявания средното издава точната точка.
function pub(al) {
  if (al.exact) return { lat: al.lat, lon: al.lon };
  const latC = (Math.floor(al.lat / CELL_DEG) + 0.5) * CELL_DEG;
  const lonStep = CELL_DEG / Math.cos((latC * Math.PI) / 180);
  const lonC = (Math.floor(al.lon / lonStep) + 0.5) * lonStep;
  return { lat: Math.round(latC * 1e5) / 1e5, lon: Math.round(lonC * 1e5) / 1e5 };
}
function pubKey(al) { const p = pub(al); return p.lat + ',' + p.lon + ',' + !!al.exact; }

// ---------- помощни ----------
function valid(lat, lon) { return lat !== null && lon !== null && Number.isFinite(+lat) && Number.isFinite(+lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180; }
function km(a, b) {
  const R = 6371, r = Math.PI / 180;
  const dLat = (b.lat - a.lat) * r, dLon = (b.lon - a.lon) * r;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
function send(ws, o) { try { ws.send(JSON.stringify(o)); } catch {} }
function json(o, status = 200) { return new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' } }); }
function html(s) { return new Response(s, { headers: { 'content-type': 'text/html; charset=utf-8', 'x-content-type-options': 'nosniff', 'referrer-policy': 'no-referrer' } }); }
function cors(r) {
  const h = new Headers(r.headers);
  h.set('access-control-allow-origin', '*');
  h.set('access-control-allow-headers', 'content-type, x-admin');
  h.set('access-control-allow-methods', 'GET, POST, OPTIONS');
  return new Response(r.body, { status: r.status, headers: h, webSocket: r.webSocket });
}
function hex(buf) { return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join(''); }
function same(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

export const _test = { pub, km };

const STYLE = `<style>
:root{--bg:#fff;--fg:#111;--mut:#666;--red:#d32f2f;--line:#ddd}
@media (prefers-color-scheme:dark){:root{--bg:#121212;--fg:#eee;--mut:#999;--line:#333}}
body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:16px system-ui,sans-serif;max-width:640px}
h1{font-size:20px;color:var(--red)}input,button,a.btn{display:block;text-align:center;text-decoration:none;font:inherit;padding:12px;border-radius:8px;border:1px solid var(--line);width:100%;box-sizing:border-box;margin:4px 0;background:var(--bg);color:var(--fg)}
button,a.btn{background:var(--red);color:#fff;border:0;font-weight:600}button.sec,a.sec{background:transparent;color:var(--fg);border:1px solid var(--line)}
button.mini{display:inline-block;width:auto;padding:6px 10px;margin:0 0 0 8px;font-size:14px}
.row{padding:8px 0;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;gap:8px}.mut{color:var(--mut);font-size:14px}
h2{font-size:17px;margin:20px 0 6px}
</style>`;

const HOME_HTML = `<!doctype html><html lang="bg"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>SOS</title>${STYLE}</head><body>
<h1>SOS — помощ наоколо</h1>
<p>Паник бутон за всеки, без регистрация. Тревогата стига веднага до хората наоколо с приложението, в радиус 7 км. Можеш да оставиш гласово съобщение и да избереш дали да покажеш точното място или само района.</p>
<a class="btn" href="/SOS.apk">Свали приложението (Android)</a>
<p class="mut">При непосредствена опасност звънни на 112. Приложението не е спешна служба и не гарантира, че някой ще дойде.</p>
</body></html>`;
