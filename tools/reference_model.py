#!/usr/bin/env python3
"""Reference implementation of PocketHuman's model maths (used to check the converted assets and the app's output).
usage: reference_model.py ASSET_DIR out.glb [json settings]"""
import sys, json, struct, numpy as np, os
A, OUTF = sys.argv[1], sys.argv[2]
S = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
rd = lambda n: open(os.path.join(A, n), 'rb').read()

b = rd('base.bin'); nV = struct.unpack_from('<I', b, 4)[0]; o = 8
V = np.frombuffer(b, '<f4', nV * 3, o).reshape(-1, 3).copy(); o += nV * 12
nR = struct.unpack_from('<I', b, o)[0]; o += 4
rmap = np.frombuffer(b, '<u2', nR, o).astype(int); o += nR * 2
ruv = np.frombuffer(b, '<f4', nR * 2, o).reshape(-1, 2); o += nR * 8
nT = struct.unpack_from('<I', b, o)[0]; o += 4
tris = np.frombuffer(b, '<u4', nT * 3, o).reshape(-1, 3)
tidx = json.loads(rd('targets.json')); tb = rd('targets.bin')
def target(name):
    off, n = tidx[name]
    return np.frombuffer(tb, '<u2', n, off).astype(int), np.frombuffer(tb, '<f2', n * 3, off + n * 2).reshape(-1, 3).astype(np.float32)
sl = json.loads(rd('sliders.json')); macro = sl['macro']['macrotargets']

def components(name, value):
    out = []
    for p in macro[name]['parts']:
        if p['lowest'] < value < p['highest']:
            t = (value - p['lowest']) / (p['highest'] - p['lowest'])
            if p['low']: out.append((p['low'], 1 - t))
            if p['high']: out.append((p['high'], t))
    return out
m = dict(gender=0.0, age=0.5, muscle=0.5, weight=0.5, proportions=0.5, height=0.5, cupsize=0.5, firmness=0.5)
m.update(S.get('macro', {}))
race = S.get('race', {'african': 1 / 3, 'asian': 1 / 3, 'caucasian': 1 / 3})
C = {k: components(k, m[k]) for k in m}
stack = []
for r, rw in race.items():
    for a, aw in C['age']:
        for g, gw in C['gender']: stack.append((f'macrodetails/{r}-{g}-{a}', rw * gw * aw))
for g, gw in C['gender']:
    for a, aw in C['age']:
        for mu, mw in C['muscle']:
            for we, ww in C['weight']:
                stack.append((f'macrodetails/universal-{g}-{a}-{mu}-{we}', gw * aw * mw * ww))
                for h, hw in C['height']: stack.append((f'macrodetails/height/{g}-{a}-{mu}-{we}-{h}', gw * aw * mw * ww * hw))
                for pr, pw in C['proportions']: stack.append((f'macrodetails/proportions/{g}-{a}-{mu}-{we}-{pr}', gw * aw * mw * ww * pw))
                if g == 'female':
                    for cu, cw in C['cupsize']:
                        for fi, fw in C['firmness']:
                            n = f'breast/{g}-{a}-{mu}-{we}-{cu}-{fi}'
                            if 'averagecup-averagefirmness' not in n: stack.append((n, aw * mw * ww * cw * fw))
for sid, val in S.get('sliders', {}).items():
    for grp in sl['groups']:
        for s_ in grp['sliders']:
            if s_['id'] == sid:
                for t in (s_['pos'] if val > 0 else s_['neg']): stack.append((t, abs(val)))
for unit, val in S.get('units', {}).items():
    for r, rw in race.items(): stack.append((f'expression/units/{r}/{unit}', rw * val))
P = V.copy(); used = 0
for n, w in stack:
    if w > 0.001 and n in tidx:
        i, d = target(n); P[i] += w * d; used += 1
print('targets applied', used)
P[:, 1] -= P[:13380, 1].min()   # feet on the ground

# proxies
prox = json.loads(rd('proxies.json'))
def fit(pid):
    pb = rd(f'proxies/{pid}.bin'); n = struct.unpack_from('<I', pb, 4)[0]; o = 8
    refs = np.frombuffer(pb, '<u2', n * 3, o).reshape(-1, 3).astype(int); o += n * 6
    rw = np.frombuffer(pb, '<f4', n * 3, o).reshape(-1, 3); o += n * 12
    ro = np.frombuffer(pb, '<f4', n * 3, o).reshape(-1, 3); o += n * 12
    a0 = struct.unpack_from('<3I3f', pb, o); o += 24; a1 = struct.unpack_from('<3I', pb, o); o += 12
    sc = np.array([abs(P[a0[k], k] - P[a1[k], k]) / a0[3 + k] if a0[3 + k] else 1.0 for k in range(3)])
    pv = (P[refs] * rw[:, :, None]).sum(1) + ro * sc
    nr = struct.unpack_from('<I', pb, o)[0]; o += 4
    pm = np.frombuffer(pb, '<u2', nr, o).astype(int); o += nr * 2
    puv = np.frombuffer(pb, '<f4', nr * 2, o).reshape(-1, 2); o += nr * 8
    nt = struct.unpack_from('<I', pb, o)[0]; o += 4
    pt = np.frombuffer(pb, '<u4', nt * 3, o).reshape(-1, 3)
    return pv, refs, rw, pm, puv, pt
# rig
rig = json.loads(rd('rig.json'))['bones']; rwb = rd('rig_weights.bin')
WJ = np.frombuffer(rwb, '<u1', nV * 4, 0).reshape(-1, 4).astype(int); WW = np.frombuffer(rwb, '<f4', nV * 4, nV * 4).reshape(-1, 4)
SCALE = 0.1
heads = np.array([P[b_['head']['verts']].mean(0) for b_ in rig]) * SCALE

# glb writer (meters)
import io
from PIL import Image
blob = bytearray(); views = []; accs = []
def view(data, target=None):
    while len(blob) % 4: blob.append(0)
    views.append({'buffer': 0, 'byteOffset': len(blob), 'byteLength': len(data), **({'target': target} if target else {})}); blob.extend(data); return len(views) - 1
def acc(arr, ct, typ, target=34962, mm=False):
    a = np.ascontiguousarray(arr); d = {'bufferView': view(a.tobytes(), target), 'componentType': ct, 'count': len(a), 'type': typ}
    if mm: d['min'] = a.min(0).tolist(); d['max'] = a.max(0).tolist()
    accs.append(d); return len(accs) - 1
images, textures, materials, meshes, nodes = [], [], [], [], []
def tex(fn):
    data = rd(fn); images.append({'bufferView': view(data), 'mimeType': 'image/png' if fn.endswith('png') else 'image/jpeg'}); textures.append({'source': len(images) - 1}); return len(textures) - 1
def normals(Pp, T):
    N = np.zeros_like(Pp); fn = np.cross(Pp[T[:, 1]] - Pp[T[:, 0]], Pp[T[:, 2]] - Pp[T[:, 0]])
    for k in range(3): np.add.at(N, T[:, k], fn)
    return N / (np.linalg.norm(N, axis=1, keepdims=True) + 1e-12)
def add_mesh(name, Pp, uvp, T, J4, W4, texfile, alpha):
    # weld normals by position so UV seams don't show
    key = np.round(Pp / 1e-5).astype(np.int64); _, inv = np.unique(key, axis=0, return_inverse=True)
    Nw = np.zeros((inv.max() + 1, 3)); fn = np.cross(Pp[T[:, 1]] - Pp[T[:, 0]], Pp[T[:, 2]] - Pp[T[:, 0]])
    for k in range(3): np.add.at(Nw, inv[T[:, k]], fn)
    N = Nw[inv]; N /= np.linalg.norm(N, axis=1, keepdims=True) + 1e-12
    materials.append({'name': name, 'pbrMetallicRoughness': {'baseColorTexture': {'index': tex(texfile)}, 'metallicFactor': 0, 'roughnessFactor': 0.7},
                      **({'alphaMode': 'MASK', 'alphaCutoff': 0.4, 'doubleSided': True} if alpha else {})})
    meshes.append({'name': name, 'primitives': [{'attributes': {'POSITION': acc(Pp.astype('<f4'), 5126, 'VEC3', mm=True), 'NORMAL': acc(N.astype('<f4'), 5126, 'VEC3'),
        'TEXCOORD_0': acc(uvp.astype('<f4'), 5126, 'VEC2'), 'JOINTS_0': acc(J4.astype('<u2'), 5123, 'VEC4'), 'WEIGHTS_0': acc(W4.astype('<f4'), 5126, 'VEC4')},
        'indices': acc(T.astype('<u4').ravel(), 5125, 'SCALAR', 34963), 'material': len(materials) - 1}]})
    nodes.append({'name': name, 'mesh': len(meshes) - 1, 'skin': 0})
skins = json.loads(rd('skins.json'))
skin = skins[S.get('skin', 0)]['texture']
add_mesh('body', P[rmap] * SCALE, ruv, tris, WJ[rmap], WW[rmap], skin, False)
picks = S.get('proxies', ['eyes_high-poly', 'eyebrows_eyebrow001', 'eyelashes_eyelashes01', 'teeth_teeth_base', 'tongue_tongue01', 'hair_ponytail01'])
for pid in picks:
    pr = [p for p in prox if p['id'] == pid][0]
    pv, refs, rwp, pm, puv, pt = fit(pid)
    # weights from the reference vertices
    Wd = np.zeros((len(pv), len(rig)))
    for k in range(3):
        for j in range(4): np.add.at(Wd, (np.arange(len(pv)), WJ[refs[:, k], j]), rwp[:, k] * WW[refs[:, k], j])
    top = np.argsort(-Wd, 1)[:, :4]; tw = np.take_along_axis(Wd, top, 1); tw /= np.maximum(tw.sum(1, keepdims=True), 1e-9)
    v = pr['variants'][0] if pr['variants'] else None
    if v: add_mesh(pid, pv[pm] * SCALE, puv, pt, top[pm], tw[pm], v['texture'], v['alpha'])
# joints
jn0 = len(nodes)
for i, b_ in enumerate(rig):
    t = heads[i] - (heads[b_['parent']] if b_['parent'] >= 0 else 0)
    nodes.append({'name': b_['name'], 'translation': t.tolist(), 'children': []})
for i, b_ in enumerate(rig):
    if b_['parent'] >= 0: nodes[jn0 + b_['parent']]['children'].append(jn0 + i)
for n in nodes:
    if 'children' in n and not n['children']: del n['children']
ibm = np.zeros((len(rig), 16), '<f4')
for i in range(len(rig)):
    Mi = np.eye(4); Mi[:3, 3] = -heads[i]; ibm[i] = Mi.T.ravel()
skin_ = {'joints': list(range(jn0, jn0 + len(rig))), 'inverseBindMatrices': acc(ibm, 5126, 'MAT4', None), 'skeleton': jn0}
root = {'name': 'PocketHuman', 'children': list(range(jn0)) + [jn0]}
nodes.append(root)
gl = {'asset': {'version': '2.0', 'generator': 'PocketHuman reference'}, 'scene': 0, 'scenes': [{'nodes': [len(nodes) - 1]}], 'nodes': nodes, 'meshes': meshes,
      'materials': materials, 'textures': textures, 'images': images, 'samplers': [], 'skins': [skin_], 'accessors': accs, 'bufferViews': views,
      'buffers': [{'byteLength': len(blob)}]}
js = json.dumps(gl).encode(); js += b' ' * ((4 - len(js) % 4) % 4)
while len(blob) % 4: blob.append(0)
with open(OUTF, 'wb') as f:
    f.write(struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(blob)))
    f.write(struct.pack('<II', len(js), 0x4E4F534A)); f.write(js); f.write(struct.pack('<II', len(blob), 0x004E4942)); f.write(blob)
print('height m', round(float((P[:, 1].max() - P[:, 1].min()) * SCALE), 3), 'wrote', OUTF)
