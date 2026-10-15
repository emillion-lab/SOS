#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Взима последната Sofia_YYYY-MM-DD.json(.gz) от emillion-lab/TAXI и пише plates.json:
{"date": ..., "source": ..., "count": N, "plates": {"CA1234XX": ["модел", "фирма"]}}
Worker-ът проверява срещу него при издаване на парола. Номерата се нормализират
по същия начин като в worker.js (кирилица → латиница, без интервали и тирета).
"""
import gzip, json, os, re, urllib.request

API = 'https://api.github.com/repos/emillion-lab/TAXI/contents/'
LOOK = str.maketrans('АВЕКМНОРСТУХ', 'ABEKMHOPCTYX')


def norm(s):
    return re.sub(r'[\s\-.]', '', (s or '').upper()).translate(LOOK)


def get(url, api=False):
    h = {'User-Agent': 'sos-plates'}
    if api:
        h['Accept'] = 'application/vnd.github+json'
        if os.environ.get('GH_TOKEN'):
            h['Authorization'] = 'Bearer ' + os.environ['GH_TOKEN']
    with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=120) as r:
        return r.read()


items = json.loads(get(API, api=True))
snaps = {}
for it in items:
    m = re.fullmatch(r'Sofia_(\d{4}-\d{2}-\d{2})\.json(\.gz)?', it['name'])
    if m and (m.group(1) not in snaps or m.group(2)):  # .gz бие суровия
        snaps[m.group(1)] = it
if not snaps:
    raise SystemExit('ГРЕШКА: няма Sofia_*.json в TAXI')
date = max(snaps)
src = snaps[date]
raw = get(src['download_url'])
if raw[:2] == b'\x1f\x8b':
    raw = gzip.decompress(raw)
data = json.loads(raw.decode('utf-8'))

plates = {}
for op in data:
    name = (op.get('operatorName') or '').strip()
    for v in op.get('vehicles') or []:
        reg = norm(v.get('registerNumber'))
        if reg:
            plates[reg] = [(v.get('markAndModel') or '').strip(), name]

if len(plates) < 1000:
    raise SystemExit('ГРЕШКА: само %d номера — снимката изглежда счупена' % len(plates))

with open('plates.json', 'w', encoding='utf-8') as fh:
    json.dump({'date': date, 'source': src['name'], 'count': len(plates), 'plates': plates},
              fh, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
print('plates.json: %d номера от %s' % (len(plates), src['name']))
