"""Builds assets/ph/community.json from the MakeHuman community asset index (the old site's JSON dump).
usage: build_community.py INDEX(.json or .html containing it) OUT_JSON
Keeps the types PocketHuman can import (clothes-format meshes, skins, targets), drops anything aimed at children,
and flags adult content (shown only with adult anatomy on)."""
import sys, re, json, html
src = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
t = re.sub(r'<script.*?</script>|<style.*?</style>', '', src, flags=re.S); t = html.unescape(re.sub(r'<[^>]+>', '', t)).strip()
j = json.JSONDecoder().raw_decode(t[t.index('{'):])[0]
CHILD = re.compile(r'\b(child|children|childs|kid|kids|baby|babies|toddler|infant|teen|teens|teenager|preteen|loli|lolita|shota|minor|underage|schoolchild|juvenile|newborn)\b', re.I)
ADULT = re.compile(r'genital|penis|vagin|vulva|labia|pubic|nude|naked|nsfw|erect|testic|scrot|anus|nipple', re.I)
def https(u): return u.replace('http://files.makehumancommunity.org', 'https://files.makehumancommunity.org')
def clean(s): return re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', html.unescape(s or ''))).strip()[:400]
out = []; dropped = 0
for a in j.values():
    typ = a.get('type'); f = a.get('files') or {}
    title = clean(a.get('title')); desc = clean(a.get('description')); cat = a.get('category') or ''
    if CHILD.search(title + ' ' + desc + ' ' + cat): dropped += 1; continue
    keep = {}
    if typ == 'clothes' and all(k in f for k in ('mhclo', 'obj', 'mhmat')):
        keep = {k: https(f[k]) for k in ('mhclo', 'obj', 'mhmat', 'diffuse', 'normals', 'render') if k in f}
    elif typ == 'skin' and 'mhmat' in f and 'diffuse' in f:
        keep = {k: https(f[k]) for k in ('mhmat', 'diffuse', 'normals', 'render', 'thumb') if k in f}
    elif typ == 'target' and 'file' in f:
        keep = {k: https(f[k]) for k in ('file', 'render') if k in f}
    else: continue
    lic = a.get('license') or ''
    if lic not in ('CC0', 'CC-BY', 'AGPL'): continue
    kind = {'Hair': 'hair', 'Eyebrows': 'eyebrows', 'Eyelashes': 'eyelashes', 'Genitalia': 'anatomy'}.get(cat, 'clothes') if typ == 'clothes' else ('skin' if typ == 'skin' else 'target')
    adult = kind == 'anatomy' or bool(ADULT.search(title + ' ' + cat)) or cat.startswith('Gender-specific')
    out.append({'n': a['nid'], 't': title, 'k': kind, 'c': cat, 'l': lic, 'a': a.get('username', ''), 'd': a.get('changed', ''), 'x': 1 if adult else 0, 's': desc, 'f': keep})
out.sort(key=lambda e: e['d'], reverse=True)
json.dump(out, open(sys.argv[2], 'w'), separators=(',', ':'))
from collections import Counter
print(len(out), 'assets kept,', dropped, 'dropped for child terms;', Counter(e['k'] for e in out), Counter(e['l'] for e in out), 'adult', sum(e['x'] for e in out))
