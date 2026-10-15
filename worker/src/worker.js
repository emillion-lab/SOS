// SOS — паник бутон за таксиметрови шофьори.
// Вход: регистрационен номер (трябва да е в софийския регистър) + парола, издадена от админа.
// В ефира шофьорът е „Такси N". При тревога колегите до 7 км виждат номер, модел, фирма и точка.

const RADIUS_KM = 7;
const STALE_MS = 10 * 60 * 1000;        // позиция по-стара от това не се брои
const ALARM_TTL_MS = 30 * 60 * 1000;    // тревога изтича сама след 30 мин
const SOS_COOLDOWN_MS = 10 * 60 * 1000; // 1 тревога на 10 мин от номер
const PLATES_URL = 'https://raw.githubusercontent.com/emillion-lab/SOS/main/plates.json';
const APK_URL = 'https://github.com/emillion-lab/SOS/releases/download/apk-latest/SOS.apk';
const ITER = 100000;                    // таванът на PBKDF2 в Workers

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === '/health') return json({ ok: true, v: 2 });
    if (url.pathname === '/' && req.method === 'GET')
      return new Response(HOME_HTML, { headers: { 'content-type': 'text/html; charset=utf-8' } });
    if (url.pathname === '/admin' && req.method === 'GET')
      return new Response(ADMIN_HTML, { headers: { 'content-type': 'text/html; charset=utf-8' } });
    return env.HUB.get(env.HUB.idFromName('sofia')).fetch(req);
  },
};

export class Hub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.db = state.storage;
    this.plates = null;
    this.platesAt = 0;
  }

  // Същият админ токен като на fish.taxi: secret ADMIN_TOKEN, иначе KV admin:token в GPS_STORE
  async adminToken() {
    if (this.env.ADMIN_TOKEN) return this.env.ADMIN_TOKEN;
    if (this.env.GPS_STORE) return (await this.env.GPS_STORE.get('admin:token')) || '';
    return '';
  }

  async fetch(req) {
    const url = new URL(req.url);
    const p = url.pathname;
    if (req.method === 'OPTIONS') return cors(new Response(null, { status: 204 }));
    try {
      if (p === '/login' && req.method === 'POST') return cors(await this.login(await req.json()));
      if (p === '/ws') return await this.connect(url, req);
      if (p.startsWith('/admin/')) {
        const tok = await this.adminToken();
        if (!tok || !same(req.headers.get('x-admin') || '', tok))
          return cors(json({ error: 'нямаш достъп' }, 401));
        const body = req.method === 'POST' ? await req.json().catch(() => ({})) : {};
        if (p === '/admin/issue') return cors(await this.issue(body.plate));
        if (p === '/admin/revoke') return cors(await this.revoke(body.plate));
        if (p === '/admin/list') return cors(await this.list());
      }
      return cors(json({ error: 'няма такъв път' }, 404));
    } catch (e) {
      return cors(json({ error: String((e && e.message) || e) }, 500));
    }
  }

  // ---------- регистър ----------
  async registry() {
    if (this.plates && Date.now() - this.platesAt < 3600e3) return this.plates;
    const r = await fetch(PLATES_URL, { cf: { cacheTtl: 600 } });
    if (!r.ok) throw new Error('регистърът не се зарежда (' + r.status + ')');
    this.plates = (await r.json()).plates || {};
    this.platesAt = Date.now();
    return this.plates;
  }

  // ---------- админ ----------
  async issue(raw) {
    const plate = normPlate(raw);
    if (!plate) return json({ error: 'липсва номер' }, 400);
    const reg = (await this.registry())[plate];
    if (!reg) return json({ error: plate + ' не е в софийския регистър на таксита' }, 404);
    const old = await this.db.get('cred:' + plate);
    let n = old && old.n;
    if (!n) {
      n = ((await this.db.get('seq')) || 0) + 1;
      await this.db.put('seq', n);
    }
    if (old && old.sess) await this.kick(old.sess, 'Паролата за ' + plate + ' е сменена');
    const password = genPassword();
    const salt = hex(crypto.getRandomValues(new Uint8Array(16)));
    await this.db.put('cred:' + plate, {
      n, model: reg[0] || '', op: reg[1] || '', salt, hash: await pbkdf2(password, salt), sess: null,
    });
    return json({ plate, password, n, model: reg[0] || '', op: reg[1] || '' });
  }

  async revoke(raw) {
    const plate = normPlate(raw);
    const c = await this.db.get('cred:' + plate);
    if (!c) return json({ error: 'няма такъв' }, 404);
    if (c.sess) await this.kick(c.sess, 'Достъпът е спрян');
    await this.db.delete('cred:' + plate);
    return json({ ok: true, plate });
  }

  async list() {
    const online = new Set(this.state.getWebSockets().map((w) => (w.deserializeAttachment() || {}).plate));
    const out = [];
    for (const [k, c] of await this.db.list({ prefix: 'cred:' })) {
      const plate = k.slice(5);
      out.push({ plate, n: c.n, model: c.model, op: c.op, online: online.has(plate) });
    }
    out.sort((a, b) => a.n - b.n);
    return json({ drivers: out });
  }

  // ---------- вход ----------
  async login(body) {
    const plate = normPlate(body && body.plate);
    const c = plate && (await this.db.get('cred:' + plate));
    if (!c || !same(await pbkdf2(String(body.password || ''), c.salt), c.hash))
      return json({ error: 'грешен номер или парола' }, 401);
    if (c.sess) await this.kick(c.sess, 'Влезли са с ' + plate + ' от друго устройство');
    const token = hex(crypto.getRandomValues(new Uint8Array(32)));
    c.sess = token;
    await this.db.put('cred:' + plate, c);
    await this.db.put('sess:' + token, plate);
    return json({ token, plate, n: c.n, model: c.model, op: c.op });
  }

  async kick(token, why) {
    await this.db.delete('sess:' + token);
    for (const w of this.state.getWebSockets()) {
      if ((w.deserializeAttachment() || {}).sess === token) {
        try { w.send(JSON.stringify({ t: 'kicked', why })); w.close(4001, 'kicked'); } catch {}
      }
    }
  }

  // ---------- WebSocket ----------
  async connect(url, req) {
    if (req.headers.get('Upgrade') !== 'websocket') return json({ error: 'трябва websocket' }, 426);
    const token = url.searchParams.get('token') || '';
    const plate = token && (await this.db.get('sess:' + token));
    if (!plate) return json({ error: 'невалидна сесия' }, 401);
    const c = await this.db.get('cred:' + plate);
    if (!c) return json({ error: 'невалидна сесия' }, 401);
    const pair = new WebSocketPair();
    const ws = pair[1];
    this.state.acceptWebSocket(ws);
    ws.serializeAttachment({ sess: token, plate, n: c.n, model: c.model, op: c.op, lat: null, lon: null, ts: 0, known: [] });
    ws.send(JSON.stringify({ t: 'hello', n: c.n, plate }));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws, raw) {
    let m;
    try { m = JSON.parse(raw); } catch { return; }
    const a = ws.deserializeAttachment();
    if (!a) return;
    const now = Date.now();

    if (m.t === 'loc' && valid(m.lat, m.lon)) {
      a.lat = +m.lat; a.lon = +m.lon; a.ts = now;
      // моята тревога върви с мен
      const mine = await this.db.get('alarm:' + a.plate);
      if (mine) { mine.lat = a.lat; mine.lon = a.lon; mine.upd = now; await this.db.put('alarm:' + a.plate, mine); }
      ws.serializeAttachment(a);
      if (mine) this.broadcast(mine);
      // активни тревоги, в чийто радиус току-що съм влязъл
      for (const al of await this.activeAlarms()) {
        if (al.plate !== a.plate && !(a.known || []).includes(al.id) && km(a, al) <= RADIUS_KM) this.sendAlert(ws, al);
      }
      return;
    }

    if (m.t === 'sos') {
      if (!valid(m.lat, m.lon)) return send(ws, { t: 'error', msg: 'Няма GPS позиция — изчакай секунда' });
      const last = (await this.db.get('cool:' + a.plate)) || 0;
      const existing = await this.db.get('alarm:' + a.plate);
      if (!existing && now - last < SOS_COOLDOWN_MS)
        return send(ws, { t: 'error', msg: 'Тревога може веднъж на 10 мин' });
      a.lat = +m.lat; a.lon = +m.lon; a.ts = now;
      ws.serializeAttachment(a);
      const al = existing || {
        id: crypto.randomUUID().slice(0, 8), plate: a.plate, n: a.n, model: a.model, op: a.op,
        ts: now, acks: [],
      };
      al.lat = a.lat; al.lon = a.lon; al.upd = now;
      await this.db.put('alarm:' + a.plate, al);
      await this.db.put('cool:' + a.plate, now);
      const reached = this.broadcast(al);
      return send(ws, { t: 'sent', id: al.id, reached });
    }

    if (m.t === 'cancel') {
      const al = await this.db.get('alarm:' + a.plate);
      if (!al) return send(ws, { t: 'cancelled' });
      await this.db.delete('alarm:' + a.plate);
      const msg = JSON.stringify({ t: 'cancel', id: al.id });
      for (const w of this.state.getWebSockets()) try { w.send(msg); } catch {}
      return send(ws, { t: 'cancelled' });
    }

    if (m.t === 'ack' && m.id) {
      for (const al of await this.activeAlarms()) {
        if (al.id !== m.id) continue;
        if (!al.acks.includes(a.n)) { al.acks.push(a.n); await this.db.put('alarm:' + al.plate, al); }
        const d = valid(a.lat, a.lon) ? Math.round(km(a, al) * 10) / 10 : null;
        for (const w of this.state.getWebSockets()) {
          if ((w.deserializeAttachment() || {}).plate === al.plate)
            send(w, { t: 'ack', id: al.id, n: a.n, km: d, count: al.acks.length });
        }
      }
    }
  }

  async webSocketClose(ws, code) { try { ws.close(code === 1005 ? 1000 : code); } catch {} }
  async webSocketError() {}

  async activeAlarms() {
    const out = [];
    const now = Date.now();
    for (const [k, al] of await this.db.list({ prefix: 'alarm:' })) {
      if (now - al.ts > ALARM_TTL_MS) { await this.db.delete(k); continue; }
      out.push(al);
    }
    return out;
  }

  // праща тревогата на всички живи в радиуса; връща колко са
  broadcast(al) {
    let n = 0;
    const now = Date.now();
    for (const w of this.state.getWebSockets()) {
      const a = w.deserializeAttachment();
      if (!a || a.plate === al.plate || !valid(a.lat, a.lon) || now - a.ts > STALE_MS) continue;
      if (km(a, al) > RADIUS_KM) continue;
      this.sendAlert(w, al);
      n++;
    }
    return n;
  }

  sendAlert(w, al) {
    const a = w.deserializeAttachment();
    send(w, {
      t: 'alert', id: al.id, n: al.n, plate: al.plate, model: al.model, op: al.op,
      lat: al.lat, lon: al.lon, ts: al.ts, km: Math.round(km(a, al) * 10) / 10, helpers: al.acks.length,
    });
    if (!(a.known || []).includes(al.id)) {
      a.known = [...(a.known || []), al.id].slice(-20);
      w.serializeAttachment(a);
    }
  }
}

// ---------- помощни ----------
const LOOK = { 'А': 'A', 'В': 'B', 'Е': 'E', 'К': 'K', 'М': 'M', 'Н': 'H', 'О': 'O', 'Р': 'P', 'С': 'C', 'Т': 'T', 'У': 'Y', 'Х': 'X' };
function normPlate(s) {
  return String(s || '').toUpperCase().replace(/[\s\-.]/g, '').replace(/[АВЕКМНОРСТУХ]/g, (c) => LOOK[c]);
}
function valid(lat, lon) { return Number.isFinite(+lat) && Number.isFinite(+lon) && lat !== null && Math.abs(lat) <= 90 && Math.abs(lon) <= 180; }
function km(a, b) {
  const R = 6371, r = Math.PI / 180;
  const dLat = (b.lat - a.lat) * r, dLon = (b.lon - a.lon) * r;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
function send(ws, o) { try { ws.send(JSON.stringify(o)); } catch {} }
function json(o, status = 200) { return new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json; charset=utf-8' } }); }
function cors(r) {
  const h = new Headers(r.headers);
  h.set('access-control-allow-origin', '*');
  h.set('access-control-allow-headers', 'content-type, x-admin');
  h.set('access-control-allow-methods', 'GET, POST, OPTIONS');
  return new Response(r.body, { status: r.status, headers: h, webSocket: r.webSocket });
}
function hex(buf) { return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join(''); }
function unhex(s) { return new Uint8Array(s.match(/../g).map((x) => parseInt(x, 16))); }
function same(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}
async function pbkdf2(pass, salt) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(pass), 'PBKDF2', false, ['deriveBits']);
  return hex(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: unhex(salt), iterations: ITER }, key, 256));
}
function genPassword() {
  const abc = 'abcdefghjkmnpqrstuvwxyz23456789';
  const r = crypto.getRandomValues(new Uint8Array(8));
  return [...r].map((x) => abc[x % abc.length]).join('');
}

const STYLE = `<style>
:root{--bg:#fff;--fg:#111;--mut:#666;--red:#d32f2f;--line:#ddd}
@media (prefers-color-scheme:dark){:root{--bg:#121212;--fg:#eee;--mut:#999;--line:#333}}
body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:16px system-ui,sans-serif;max-width:520px}
h1{font-size:20px;color:var(--red)}input,button,a.btn{display:block;text-align:center;text-decoration:none;font:inherit;padding:12px;border-radius:8px;border:1px solid var(--line);width:100%;box-sizing:border-box;margin:4px 0;background:var(--bg);color:var(--fg)}
button,a.btn{background:var(--red);color:#fff;border:0;font-weight:600}button.sec,a.sec{background:transparent;color:var(--fg);border:1px solid var(--line)}
#out{margin:12px 0;padding:12px;border:1px solid var(--line);border-radius:8px;white-space:pre-wrap;min-height:20px}
.pw{font:700 28px ui-monospace,monospace;letter-spacing:2px}.row{padding:8px 0;border-bottom:1px solid var(--line)}.mut{color:var(--mut);font-size:14px}
</style>`;

const HOME_HTML = `<!doctype html><html lang="bg"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>SOS</title>${STYLE}</head><body>
<h1>SOS — колега в беда</h1>
<p>Паник бутон за таксиметрови шофьори в София. Тревогата стига до колегите в радиус 7 км.</p>
<a class="btn" href="${APK_URL}">Свали приложението (Android)</a>
<a class="btn sec" href="/admin">Админ — пароли за шофьори</a>
<p class="mut">Не замества 112.</p>
</body></html>`;

const ADMIN_HTML = `<!doctype html><html lang="bg"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>SOS админ</title>${STYLE}</head><body>
<h1>SOS — пароли за шофьори</h1>
<input id="tok" type="password" placeholder="Админ токен (същият като на fish.taxi)">
<input id="plate" placeholder="Рег. номер, напр. CA1234XX" autocapitalize="characters">
<button onclick="issue()">Издай нова парола</button>
<button class="sec" onclick="revoke()">Спри достъпа за този номер</button>
<div id="out"></div>
<button class="sec" onclick="list()">Списък шофьори</button>
<div id="list"></div>
<script>
const $=id=>document.getElementById(id);
try{$('tok').value=localStorage.getItem('sosAdmin')||''}catch(e){}
async function call(path,body){
  try{localStorage.setItem('sosAdmin',$('tok').value)}catch(e){}
  const r=await fetch(path,{method:body?'POST':'GET',headers:{'content-type':'application/json','x-admin':$('tok').value},body:body?JSON.stringify(body):undefined});
  return r.json();
}
async function issue(){const j=await call('/admin/issue',{plate:$('plate').value});
  $('out').innerHTML=j.error?j.error:'Такси '+j.n+' · '+j.plate+'<br><span class="mut">'+j.model+' · '+j.op+'</span><div class="pw">'+j.password+'</div><span class="mut">Показва се само сега. Дай я на шофьора.</span>';}
async function revoke(){if(!confirm('Спираш достъпа?'))return;const j=await call('/admin/revoke',{plate:$('plate').value});$('out').textContent=j.error||('Спрян: '+j.plate);}
async function list(){const j=await call('/admin/list');if(j.error){$('list').textContent=j.error;return}
  $('list').innerHTML=j.drivers.map(d=>'<div class="row">'+(d.online?'🟢':'⚪')+' Такси '+d.n+' · '+d.plate+'<div class="mut">'+d.model+' · '+d.op+'</div></div>').join('')||'Няма издадени';}
</script></body></html>`;
