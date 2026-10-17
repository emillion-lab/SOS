// Локален тест на Hub с фалшива памет и фалшиви WebSocket-и. Не е тест на Cloudflare runtime.
// Пускане: node worker/test/hub.test.mjs
import assert from 'node:assert/strict';
import { Hub, _test } from '../src/worker.js';

class DB {
  constructor() { this.m = new Map(); }
  async get(k) { return structuredClone(this.m.get(k)); }
  async put(k, v) { this.m.set(k, structuredClone(v)); }
  async delete(k) { for (const x of Array.isArray(k) ? k : [k]) this.m.delete(x); }
  async list(o = {}) {
    let e = [...this.m].filter(([k]) => (!o.prefix || k.startsWith(o.prefix)) && (!o.end || k < o.end)).sort(([a], [b]) => a.localeCompare(b));
    if (o.reverse) e.reverse();
    if (o.limit) e = e.slice(0, o.limit);
    return new Map(e.map(([k, v]) => [k, structuredClone(v)]));
  }
}
class WS {
  constructor(att) { this.att = att; this.inbox = []; }
  serializeAttachment(a) { this.att = structuredClone(a); }
  deserializeAttachment() { return structuredClone(this.att); }
  send(s) { this.inbox.push(JSON.parse(s)); }
  close() { this.closed = true; }
  last(t) { return [...this.inbox].reverse().find((x) => x.t === t); }
}
const sockets = [];
const hub = new Hub({ storage: new DB(), getWebSockets: () => sockets }, { SOS_ADMIN_TOKEN: 'adm'.repeat(11) });
const req = (path, body) => new Request('https://x' + path, { method: 'POST', body: JSON.stringify(body), headers: { 'cf-connecting-ip': '1.2.3.4', 'content-type': 'application/json' } });

async function phone(device, lat, lon) {
  const r = await (await hub.fetch(req('/register', { device }))).json();
  assert.ok(r.token, 'register ' + JSON.stringify(r));
  const ws = new WS({ sess: r.token, dev: device, n: r.n, lat: null, lon: null, ts: 0, known: [], receive: true });
  sockets.push(ws);
  await hub.webSocketMessage(ws, JSON.stringify({ t: 'loc', lat, lon }));
  return Object.assign(ws, { n: r.n });
}
const msg = (ws, o) => hub.webSocketMessage(ws, JSON.stringify(o));
let ok = 0; const check = (c, name) => { assert.ok(c, name); ok++; console.log('✓', name); };

check((await (await hub.fetch(req('/register', { device: 'nope!' }))).json()).error, 'невалиден ид се отказва');

const A = await phone('aaaa1111', 42.6977, 23.3219);   // център
const B = await phone('bbbb2222', 42.7100, 23.3300);   // ~1.5 км
const C = await phone('cccc3333', 42.7000, 23.3250);
const D = await phone('dddd4444', 42.6600, 23.3700);   // ~5.7 км
const FAR = await phone('ffff9999', 42.1500, 24.7500); // Пловдив
check(A.n === 1 && B.n === 2, 'поредни номера Помощ 1, 2');
const again = await (await hub.fetch(req('/register', { device: 'aaaa1111' }))).json();
check(again.n === 1, 'същото устройство пази номера си');
A.att.sess = again.token;

// тревога само с района
await msg(A, { t: 'sos', lat: 42.6977, lon: 23.3219, exact: false });
const sent = A.last('sent');
check(sent && sent.reached >= 2, 'тревогата стигна до ' + sent.reached + ' наоколо');
const alB = B.last('alert');
check(alB && alB.exact === false, 'B получи тревога, режим „само района"');
check(alB.lat !== 42.6977 || alB.lon !== 23.3219, 'B НЕ вижда точната точка');
check(_test.km({ lat: 42.6977, lon: 23.3219 }, alB) < 0.35, 'районът е в рамките на ~300 м (' + Math.round(_test.km({ lat: 42.6977, lon: 23.3219 }, alB) * 1000) + ' м)');
check(!FAR.last('alert'), 'Пловдив не получава');

// малко движение в същата клетка не издава нищо
const before = B.inbox.length;
await msg(A, { t: 'loc', lat: 42.69775, lon: 23.32195 });
check(B.inbox.length === before, 'движение вътре в клетката не се праща');

// превключване на точна
await msg(A, { t: 'exact', on: true });
check(B.last('alert').lat === 42.69775 && B.last('alert').exact === true, 'след „точна локация" B вижда точката');

// глас
const b64 = Buffer.from('fake-aac-audio'.repeat(12000)).toString('base64');
await msg(A, { t: 'audio', b64, mime: 'audio/mp4' });
check(A.last('audioSaved'), 'гласът е записан');
check(B.last('alert').audio === true, 'B вижда, че има глас');
await msg(B, { t: 'getaudio', id: alB.id });
check(B.last('audio') && B.last('audio').b64 === b64, 'B изтегли гласа цял (' + b64.length + ' знака, на ' + Math.ceil(b64.length / 100000) + ' части)');
await msg(FAR, { t: 'getaudio', id: alB.id });
check(FAR.last('error') && !FAR.last('audio'), 'непоканен не може да тегли гласа');

// идвам
await msg(B, { t: 'ack', id: alB.id });
check(A.last('ack') && A.last('ack').n === B.n, 'A вижда „Помощ ' + B.n + ' идва"');
await msg(FAR, { t: 'ack', id: alB.id });
check(A.last('ack').count === 1, 'непоканен не може да се запише като идващ');

// фалшива тревога: нужни са 3 различни
await msg(B, { t: 'false', id: alB.id });
await msg(B, { t: 'false', id: alB.id });
await msg(C, { t: 'false', id: alB.id });
check(!A.last('muted'), '2 гласа (единият повторен) не спират');
await msg(D, { t: 'false', id: alB.id });
check(A.last('muted') && B.last('cancel'), '3 различни гласа спират тревогата и заглушават A за 24ч');
await msg(A, { t: 'sos', lat: 42.6977, lon: 23.3219, exact: true });
check(A.last('muted') && A.inbox.filter((x) => x.t === 'sent').length === 1, 'заглушеното устройство не може да пусне нова');
check(![...hub.db.m.keys()].some((k) => k.startsWith('audio:')), 'гласът е изтрит заедно с тревогата');

// ограничение 10 мин и спиране
await msg(C, { t: 'sos', lat: 42.7000, lon: 23.3250, exact: true });
check(B.last('alert').n === C.n && B.last('alert').exact, 'C пуска тревога с точна точка');
await msg(C, { t: 'cancel' });
check(C.last('cancelled'), 'C я спира');
await msg(C, { t: 'sos', lat: 42.7000, lon: 23.3250, exact: true });
check(/след/.test(C.last('error').msg), 'нова тревога веднага след това → „след N мин"');

// получател, който е изключил „получавай"
await msg(D, { t: 'prefs', receive: false });
await msg(B, { t: 'sos', lat: 42.7100, lon: 23.3300, exact: true });
const dAlerts = D.inbox.filter((x) => x.t === 'alert' && x.n === B.n);
check(dAlerts.length === 0, 'D е изключил получаването и не получава');

// админ
const list = await (await hub.fetch(new Request('https://x/admin/list', { headers: { 'x-admin': 'adm'.repeat(11) } }))).json();
check(list.devices.length === 5 && list.log.length === 2 && list.active.length === 1, 'админ: 5 устройства, 2 в дневника, 1 активна');
check(!JSON.stringify(list.log).includes('42.6'), 'дневникът е без координати');
check((await hub.fetch(new Request('https://x/admin/list'))).status === 401, 'админ без токен → 401');
await hub.fetch(new Request('https://x/admin/block', { method: 'POST', headers: { 'x-admin': 'adm'.repeat(11) }, body: JSON.stringify({ n: B.n, blocked: true }) }));
check(B.last('kicked') && C.last('cancel'), 'блокиран B е изхвърлен, тревогата му спряна');
check((await hub.fetch(req('/register', { device: 'bbbb2222' }))).status === 403, 'блокираният не може да влезе отново');

// таван на нови устройства от едно IP
let last;
for (let i = 0; i < 30; i++) last = await hub.fetch(req('/register', { device: 'e' + String(i).padStart(7, '0') }));
check(last.status === 429, 'над 30 нови устройства/час от едно IP → 429');

console.log('\nPASS ' + ok + ' проверки');
