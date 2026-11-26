#!/usr/bin/env python3
"""PocketHuman asset builder.

Converts the CC0 MakeHuman / MPFB2 data into compact files the app loads:
  base mesh, morph targets (adult only), slider definitions, Mixamo rig + weights,
  proxies (eyes, eyebrows, eyelashes, teeth, tongue, hair), skins.

usage: build_assets.py MPFB2_REPO SYSTEM_ASSETS_DIR SKINS01_DIR OUT_DIR
All inputs are CC0 (see https://static.makehumancommunity.org/about/license.html).
"""
import sys, os, gzip, json, struct, io, re, glob
import numpy as np
from PIL import Image

MPFB, SYS, SKINS01, OUT = sys.argv[1:5]
DATA = os.path.join(MPFB, 'src', 'mpfb', 'data')
os.makedirs(OUT, exist_ok=True)
def out(*p): return os.path.join(OUT, *p)

# Nothing for babies or children goes in: the app only makes adults.
def adult_ok(name):
    return not re.search(r'(^|[-_/])(baby|child)([-_.]|$)', name)

# ---------------------------------------------------------------- obj reader
def read_obj(path):
    V, VT, F, groups = [], [], [], []
    g = None
    for line in open(path, encoding='utf-8', errors='ignore'):
        if line.startswith('v '):
            V.append([float(x) for x in line.split()[1:4]])
        elif line.startswith('vt '):
            VT.append([float(x) for x in line.split()[1:3]])
        elif line.startswith('g '):
            g = line.split(None, 1)[1].strip()
        elif line.startswith('f '):
            corners = []
            for c in line.split()[1:]:
                p = c.split('/')
                corners.append((int(p[0]) - 1, int(p[1]) - 1 if len(p) > 1 and p[1] else -1))
            F.append(corners); groups.append(g)
    return np.array(V, np.float32), np.array(VT, np.float32) if VT else np.zeros((0, 2), np.float32), F, groups

def unweld(F, keep=None):
    """faces with (v, vt) corners -> render vertices (unique pairs) + triangles"""
    key = {}; rv, rt, tris = [], [], []
    for fi, f in enumerate(F):
        if keep is not None and not keep[fi]: continue
        ids = []
        for (v, t) in f:
            k = (v, t)
            if k not in key: key[k] = len(rv); rv.append(v); rt.append(t)
            ids.append(key[k])
        for j in range(1, len(ids) - 1): tris.append((ids[0], ids[j], ids[j + 1]))
    return np.array(rv, np.int32), np.array(rt, np.int32), np.array(tris, np.uint32)

# ---------------------------------------------------------------- base mesh
V, VT, F, G = read_obj(os.path.join(DATA, '3dobjs', 'base.obj'))
print('base verts', len(V), 'faces', len(F))
keep = np.array([g == 'body' for g in G])
rv, rt, tris = unweld(F, keep)
uv = VT[rt]; uv[:, 1] = 1 - uv[:, 1]                 # top-left origin, like glTF / GL textures as uploaded
with open(out('base.bin'), 'wb') as f:
    f.write(struct.pack('<4sI', b'PHB1', len(V))); f.write(V.astype('<f4').tobytes())
    f.write(struct.pack('<I', len(rv))); f.write(rv.astype('<u2').tobytes()); f.write(uv.astype('<f4').tobytes())
    f.write(struct.pack('<I', len(tris))); f.write(tris.astype('<u4').tobytes())
print('body render verts', len(rv), 'tris', len(tris))
vg = json.load(open(os.path.join(DATA, 'mesh_metadata', 'basemesh_vertex_groups.json')))
mirror = [int(l.split()[1]) for l in open(os.path.join(DATA, 'mesh_metadata', 'hm08.mirror')) if l.strip()] if os.path.exists(os.path.join(DATA, 'mesh_metadata', 'hm08.mirror')) else []

# ---------------------------------------------------------------- targets
def read_target(path):
    idx, d = [], []
    with gzip.open(path, 'rt') as fh:
        for line in fh:
            p = line.split()
            if len(p) == 4 and not line.startswith('#'):
                idx.append(int(p[0])); d.append([float(p[1]), float(p[2]), float(p[3])])
    return np.array(idx, np.int32), np.array(d, np.float32)
tdir = os.path.join(DATA, 'targets')
index = {}; blob = io.BytesIO()
skipped = 0
for path in sorted(glob.glob(os.path.join(tdir, '**', '*.target.gz'), recursive=True)):
    name = os.path.relpath(path, tdir)[:-len('.target.gz')]
    if not adult_ok(name) or name.startswith('genitals/') or name.startswith('expression/'):
        skipped += 1; continue
    idx, d = read_target(path)
    nz = np.abs(d).max(1) > 1e-5 if len(d) else np.zeros(0, bool)
    idx, d = idx[nz], d[nz]
    index[name] = [blob.tell(), len(idx)]
    blob.write(idx.astype('<u2').tobytes()); blob.write(d.astype('<f2').tobytes())
open(out('targets.bin'), 'wb').write(blob.getvalue())
json.dump(index, open(out('targets.json'), 'w'), separators=(',', ':'))
print('targets', len(index), 'skipped (child/baby/genital/expression)', skipped, 'bytes', blob.tell())

# ---------------------------------------------------------------- sliders
tj = json.load(open(os.path.join(tdir, 'target.json')))
groups_out = []; missing = []
for gname, gdef in tj.items():
    if gname in ('genitals',): continue
    sliders = []
    for c in gdef['categories']:
        o = c.get('opposites') or {'negative-left': '', 'negative-right': '', 'negative-unsided': '', 'positive-left': '', 'positive-right': '', 'positive-unsided': ''}
        def have(n): return n and (gname + '/' + n) in index
        def path(n): return gname + '/' + n
        if c.get('has_left_and_right'):
            neg = [path(o[k]) for k in ('negative-left', 'negative-right') if have(o[k])]
            pos = [path(o[k]) for k in ('positive-left', 'positive-right') if have(o[k])]
        else:
            neg = [path(o['negative-unsided'])] if have(o['negative-unsided']) else []
            pos = [path(o['positive-unsided'])] if have(o['positive-unsided']) else []
            if not neg and not pos and len(c['targets']) == 1 and have(c['targets'][0]):
                pos = [path(c['targets'][0])]
        if not neg and not pos: missing.append(c['name']); continue
        label = c.get('label', c['name']).replace('measure-', '').replace('-decr-incr', '').replace('-', ' ')
        sliders.append({'id': gname + '/' + c['name'], 'label': label, 'neg': neg, 'pos': pos})
    if sliders: groups_out.append({'group': gname, 'sliders': sliders})
macro = json.load(open(os.path.join(tdir, 'macrodetails', 'macro.json')))
json.dump({'groups': groups_out, 'macro': macro}, open(out('sliders.json'), 'w'), separators=(',', ':'))
print('missing sliders', len(missing), missing[:8]); print('slider groups', len(groups_out), 'sliders', sum(len(g['sliders']) for g in groups_out))

# ---------------------------------------------------------------- rig (Mixamo names)
rig = json.load(open(os.path.join(DATA, 'rigs', 'standard', 'rig.mixamo.json')))['bones']
wts = json.load(open(os.path.join(DATA, 'rigs', 'standard', 'weights.mixamo.json')))['weights']
names = list(rig.keys())
# parents before children
order = []
def visit(n):
    if n in order: return
    p = rig[n].get('parent')
    if p: visit(p)
    order.append(n)
for n in names: visit(n)
def loc(spec):
    if spec['strategy'] == 'CUBE': return {'verts': [v for a, b_ in vg[spec['cube_name']] for v in range(a, b_ + 1)]}
    return {'verts': spec['vertex_indices']}
bones = [{'name': n, 'parent': order.index(rig[n]['parent']) if rig[n].get('parent') else -1, 'head': loc(rig[n]['head']), 'tail': loc(rig[n]['tail'])} for n in order]
W = np.zeros((len(V), len(order)), np.float32)
for bn, lst in wts.items():
    if bn not in order: continue
    j = order.index(bn)
    for vi, w in lst: W[vi, j] += w
top = np.argsort(-W, 1)[:, :4]; tw = np.take_along_axis(W, top, 1); s = tw.sum(1, keepdims=True); tw = np.where(s > 0, tw / np.maximum(s, 1e-9), 0)
json.dump({'bones': bones}, open(out('rig.json'), 'w'), separators=(',', ':'))
with open(out('rig_weights.bin'), 'wb') as f:
    f.write(top.astype('<u1').tobytes()); f.write(tw.astype('<f4').tobytes())
print('rig bones', len(order))

# ---------------------------------------------------------------- materials, textures
def read_mhmat(path):
    m = {}
    for line in open(path, encoding='utf-8', errors='ignore'):
        p = line.strip().split(None, 1)
        if len(p) == 2 and not p[0].startswith('#'): m[p[0]] = p[1]
    return m
def save_tex(src, dst_name, size, alpha):
    im = Image.open(src)
    has_a = alpha and (im.mode in ('RGBA', 'LA') or 'transparency' in im.info)
    im = im.convert('RGBA' if has_a else 'RGB')
    if max(im.size) > size: im = im.resize((size, size), Image.LANCZOS)
    os.makedirs(os.path.dirname(out(dst_name)), exist_ok=True)
    if has_a: im.save(out(dst_name + '.png'), optimize=True); return dst_name + '.png', True
    im.save(out(dst_name + '.jpg'), quality=88); return dst_name + '.jpg', False

# ---------------------------------------------------------------- proxies
def read_mhclo(path):
    info = {'verts': [], 'scale': {}, 'delete': [], 'obj': None, 'material': None, 'name': None}
    status = ''
    for line in open(path, encoding='utf-8', errors='ignore'):
        w = line.split()
        if not w or w[0].startswith('#'): continue
        if status == 'v':
            if w[0].isdigit():
                if len(w) == 1: info['verts'].append(([int(w[0])] * 3, [1, 0, 0], [0, 0, 0]))
                else: info['verts'].append(([int(x) for x in w[:3]], [float(x) for x in w[3:6]], [float(x) for x in w[6:9]]))
                continue
            if info['verts']: status = ''
        if status == 'd':
            if w[0].isdigit() or w[0] == '-':
                seq = False; prev = 0
                for t in w:
                    if t == '-': seq = True; continue
                    v = int(t)
                    if seq: info['delete'].extend(range(prev + 1, v + 1)); seq = False
                    else: info['delete'].append(v)
                    prev = v
                continue
            status = ''
        k = w[0]
        if k == 'obj_file': info['obj'] = w[1]
        elif k == 'material': info['material'] = w[1]
        elif k == 'name': info['name'] = w[1]
        elif k in ('x_scale', 'y_scale', 'z_scale'): info['scale'][k[0]] = [int(w[1]), int(w[2]), float(w[3])]
        elif k == 'verts': status = 'v'
        if status == 'v' and k != 'verts': pass
        elif k == 'delete_verts': status = 'd'
    return info

proxies = []
def add_proxy(kind, folder, mhclo_name=None, mats=None):
    mh = glob.glob(os.path.join(folder, '*.mhclo')) if mhclo_name is None else [os.path.join(folder, mhclo_name)]
    if not mh: return
    info = read_mhclo(mh[0])
    pv, pvt, pf, _ = read_obj(os.path.join(folder, info['obj']))
    prv, prt, ptris = unweld(pf)
    puv = pvt[prt]; puv[:, 1] = 1 - puv[:, 1]
    refs = np.array([v[0] for v in info['verts']], np.int32); rw = np.array([v[1] for v in info['verts']], np.float32); ro = np.array([v[2] for v in info['verts']], np.float32)
    assert len(refs) == len(pv), (folder, len(refs), len(pv))
    pid = kind + '_' + os.path.basename(folder.rstrip('/'))
    with open(out('proxies', pid + '.bin'), 'wb') as f:
        f.write(struct.pack('<4sI', b'PHP1', len(pv)))
        f.write(refs.astype('<u2').tobytes()); f.write(rw.astype('<f4').tobytes()); f.write(ro.astype('<f4').tobytes())
        sc = [info['scale'].get(a, [0, 0, 0]) for a in 'xyz']
        f.write(struct.pack('<3I3f', sc[0][0], sc[1][0], sc[2][0], sc[0][2], sc[1][2], sc[2][2])); f.write(struct.pack('<3I', sc[0][1], sc[1][1], sc[2][1]))
        f.write(struct.pack('<I', len(prv))); f.write(prv.astype('<u2').tobytes()); f.write(puv.astype('<f4').tobytes())
        f.write(struct.pack('<I', len(ptris))); f.write(ptris.astype('<u4').tobytes())
    variants = []
    mat_paths = mats if mats is not None else ([os.path.join(folder, info['material'])] if info['material'] else [])
    for mp in mat_paths:
        if not os.path.exists(mp): continue
        m = read_mhmat(mp); tex = m.get('diffuseTexture')
        if not tex: continue
        tp = os.path.normpath(os.path.join(os.path.dirname(mp), tex))
        if not os.path.exists(tp): continue
        vname = os.path.splitext(os.path.basename(mp))[0]
        fname, alpha = save_tex(tp, 'proxies/' + pid + '_' + vname, 512 if kind in ('eyes', 'eyebrows', 'eyelashes', 'teeth', 'tongue') else 1024, True)
        variants.append({'name': vname.replace('_', ' '), 'texture': fname, 'alpha': alpha})
    proxies.append({'id': pid, 'kind': kind, 'label': os.path.basename(folder).replace('_', ' '), 'file': 'proxies/' + pid + '.bin',
                    'delete': sorted(set(info['delete'])), 'variants': variants})
os.makedirs(out('proxies'), exist_ok=True)
add_proxy('eyes', os.path.join(SYS, 'eyes', 'high-poly'), 'high-poly.mhclo', sorted(glob.glob(os.path.join(SYS, 'eyes', 'materials', '*.mhmat'))))
for kind in ('eyebrows', 'eyelashes', 'teeth', 'tongue', 'hair'):
    for d in sorted(glob.glob(os.path.join(SYS, kind, '*/'))):
        if kind == 'teeth' and 'base' not in d: continue
        add_proxy(kind, d)
json.dump(proxies, open(out('proxies.json'), 'w'), separators=(',', ':'))
print('proxies', {k: sum(p['kind'] == k for p in proxies) for k in ('eyes', 'eyebrows', 'eyelashes', 'teeth', 'tongue', 'hair')})

# ---------------------------------------------------------------- skins
skins = []
os.makedirs(out('skins'), exist_ok=True)
cands = sorted(glob.glob(os.path.join(SYS, 'skins', '*', '*.mhmat'))) + sorted(glob.glob(os.path.join(SKINS01, 'skins', '*', '*.mhmat')))
for mp in cands:
    nm = os.path.splitext(os.path.basename(mp))[0]
    if 'genital' in nm or not adult_ok(nm) or 'young' in nm and ('child' in nm): continue
    m = read_mhmat(mp); tex = m.get('diffuseTexture')
    if not tex: continue
    tp = os.path.normpath(os.path.join(os.path.dirname(mp), tex))
    if not os.path.exists(tp): continue
    fname, _ = save_tex(tp, 'skins/' + nm, 2048, False)
    skins.append({'name': nm.replace('_', ' '), 'texture': fname})
json.dump(skins, open(out('skins.json'), 'w'), separators=(',', ':'))
print('skins', len(skins))
open(out('LICENSE-ASSETS.txt'), 'w').write('All assets in this folder come from MakeHuman / MPFB2 and the MakeHuman community asset packs\n'
    '(makehuman_system_assets, skins01) and are released under CC0 1.0. https://creativecommons.org/publicdomain/zero/1.0/\n')
