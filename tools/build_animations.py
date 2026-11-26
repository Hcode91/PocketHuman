"""Converts Quaternius' Universal Animation Library clips (CC0) into PocketHuman's compact animation file.

usage: build_animations.py OUT_BIN UAL1.glb UAL2.glb

For every clip and every PocketHuman (Mixamo-named) bone we store the source bone's *world-space rotation change
from its bind pose* (in PocketHuman's facing: +Z forward, +Y up), the source bone's bind direction, and the hips'
movement divided by the source's hip height. The app then fits that onto any body at export time:
    world(t) = delta(t) * align(target bind direction -> source bind direction)
so differences in rest pose (T-pose vs A-pose), bone roll and proportions don't matter.

File (little endian): 'PHA1', nBones, nClips, then per clip:
  nameLen u8, name utf8, set u8, fps f32, nFrames u32, srcDirs f32[nBones*3],
  per frame: hips f32[3], rotations i16[nBones*4] (x,y,z,w * 32767)
"""
import sys, json, struct, math
import numpy as np

BONES = ["Hips", "Spine", "Spine1", "Spine2", "Neck", "Head", "LeftShoulder", "LeftArm", "LeftUpLeg", "LeftLeg", "LeftFoot",
         "LeftForeArm", "LeftHand", "LeftHandIndex1", "LeftHandIndex2", "LeftHandIndex3", "LeftHandMiddle1", "LeftHandMiddle2",
         "LeftHandMiddle3", "LeftHandPinky1", "LeftHandPinky2", "LeftHandPinky3", "LeftHandRing1", "LeftHandRing2", "LeftHandRing3",
         "LeftHandThumb1", "LeftHandThumb2", "LeftHandThumb3", "LeftToeBase", "RightShoulder", "RightArm", "RightUpLeg", "RightLeg",
         "RightFoot", "RightForeArm", "RightHand", "RightHandIndex1", "RightHandIndex2", "RightHandIndex3", "RightHandMiddle1",
         "RightHandMiddle2", "RightHandMiddle3", "RightHandPinky1", "RightHandPinky2", "RightHandPinky3", "RightHandRing1",
         "RightHandRing2", "RightHandRing3", "RightHandThumb1", "RightHandThumb2", "RightHandThumb3", "RightToeBase"]
# which bone gives each bone its direction (same choice on both skeletons); None = direction from the parent
CHILD = {"Hips": "Spine", "Spine": "Spine1", "Spine1": "Spine2", "Spine2": "Neck", "Neck": "Head"}
for s in ("Left", "Right"):
    CHILD.update({f"{s}Shoulder": f"{s}Arm", f"{s}Arm": f"{s}ForeArm", f"{s}ForeArm": f"{s}Hand", f"{s}Hand": f"{s}HandMiddle1",
                  f"{s}UpLeg": f"{s}Leg", f"{s}Leg": f"{s}Foot", f"{s}Foot": f"{s}ToeBase"})
    for f in ("Index", "Middle", "Pinky", "Ring", "Thumb"):
        CHILD[f"{s}Hand{f}1"] = f"{s}Hand{f}2"; CHILD[f"{s}Hand{f}2"] = f"{s}Hand{f}3"
PARENT = {}
for b, c in CHILD.items(): PARENT.setdefault(c, b)
PARENT.update({"LeftShoulder": "Spine2", "RightShoulder": "Spine2", "LeftUpLeg": "Hips", "RightUpLeg": "Hips"})
for s in ("Left", "Right"):
    for f in ("Index", "Middle", "Pinky", "Ring", "Thumb"): PARENT[f"{s}Hand{f}1"] = f"{s}Hand"

def map_ual1():
    m = {"Hips": "DEF-hips", "Spine": "DEF-spine.001", "Spine1": "DEF-spine.002", "Spine2": "DEF-spine.003", "Neck": "DEF-neck", "Head": "DEF-head"}
    for s, x in (("Left", "L"), ("Right", "R")):
        m.update({f"{s}Shoulder": f"DEF-shoulder.{x}", f"{s}Arm": f"DEF-upper_arm.{x}", f"{s}ForeArm": f"DEF-forearm.{x}", f"{s}Hand": f"DEF-hand.{x}",
                  f"{s}UpLeg": f"DEF-thigh.{x}", f"{s}Leg": f"DEF-shin.{x}", f"{s}Foot": f"DEF-foot.{x}", f"{s}ToeBase": f"DEF-toe.{x}"})
        for f, g in (("Index", "f_index"), ("Middle", "f_middle"), ("Pinky", "f_pinky"), ("Ring", "f_ring")):
            for k in (1, 2, 3): m[f"{s}Hand{f}{k}"] = f"DEF-{g}.0{k}.{x}"
        for k in (1, 2, 3): m[f"{s}HandThumb{k}"] = f"DEF-thumb.0{k}.{x}"
    return m

def map_ual2():
    m = {"Hips": "pelvis", "Spine": "spine_01", "Spine1": "spine_02", "Spine2": "spine_03", "Neck": "neck_01", "Head": "Head"}
    for s, x in (("Left", "l"), ("Right", "r")):
        m.update({f"{s}Shoulder": f"clavicle_{x}", f"{s}Arm": f"upperarm_{x}", f"{s}ForeArm": f"lowerarm_{x}", f"{s}Hand": f"hand_{x}",
                  f"{s}UpLeg": f"thigh_{x}", f"{s}Leg": f"calf_{x}", f"{s}Foot": f"foot_{x}", f"{s}ToeBase": f"ball_{x}"})
        for f, g in (("Index", "index"), ("Middle", "middle"), ("Pinky", "pinky"), ("Ring", "ring"), ("Thumb", "thumb")):
            for k in (1, 2, 3): m[f"{s}Hand{f}{k}"] = f"{g}_0{k}_{x}"
    return m

# ---------------------------------------------------------------- quaternions (x, y, z, w)
def qmul(a, b):
    ax, ay, az, aw = a; bx, by, bz, bw = b
    return np.array([aw*bx + ax*bw + ay*bz - az*by, aw*by - ax*bz + ay*bw + az*bx, aw*bz + ax*by - ay*bx + az*bw, aw*bw - ax*bx - ay*by - az*bz])
def qinv(q): return np.array([-q[0], -q[1], -q[2], q[3]])
def qnorm(q): return q / np.linalg.norm(q)
def slerp(a, b, t):
    d = np.dot(a, b)
    if d < 0: b = -b; d = -d
    if d > 0.9995: return qnorm(a + t * (b - a))
    th = math.acos(d); s = math.sin(th)
    return (math.sin((1 - t) * th) * a + math.sin(t * th) * b) / s
def q2m(q):
    x, y, z, w = q
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)], [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])
def m2q(m):
    t = m[0,0] + m[1,1] + m[2,2]
    if t > 0: s = math.sqrt(t + 1) * 2; return qnorm(np.array([(m[2,1]-m[1,2])/s, (m[0,2]-m[2,0])/s, (m[1,0]-m[0,1])/s, 0.25*s]))
    i = int(np.argmax([m[0,0], m[1,1], m[2,2]]))
    if i == 0: s = math.sqrt(1 + m[0,0] - m[1,1] - m[2,2]) * 2; return qnorm(np.array([0.25*s, (m[0,1]+m[1,0])/s, (m[0,2]+m[2,0])/s, (m[2,1]-m[1,2])/s]))
    if i == 1: s = math.sqrt(1 + m[1,1] - m[0,0] - m[2,2]) * 2; return qnorm(np.array([(m[0,1]+m[1,0])/s, 0.25*s, (m[1,2]+m[2,1])/s, (m[0,2]-m[2,0])/s]))
    s = math.sqrt(1 + m[2,2] - m[0,0] - m[1,1]) * 2; return qnorm(np.array([(m[0,2]+m[2,0])/s, (m[1,2]+m[2,1])/s, 0.25*s, (m[1,0]-m[0,1])/s]))
def trs(t, r, s):
    m = np.eye(4); m[:3, :3] = q2m(r) * np.array(s)[None, :]; m[:3, 3] = t; return m

# ---------------------------------------------------------------- glTF reading
def load(path):
    d = open(path, 'rb').read(); n = struct.unpack('<I', d[12:16])[0]; j = json.loads(d[20:20+n]); b = d[20+n+8:]
    def acc(i):
        a = j['accessors'][i]; bv = j['bufferViews'][a['bufferView']]; o = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
        c = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}[a['type']]
        dt = {5126: np.float32, 5123: np.uint16, 5121: np.uint8, 5122: np.int16, 5120: np.int8}[a['componentType']]
        stride = bv.get('byteStride')
        if stride and stride != c * np.dtype(dt).itemsize:
            raw = np.frombuffer(b, np.uint8, stride * a['count'], o).reshape(a['count'], stride)[:, :c * np.dtype(dt).itemsize].copy()
            arr = raw.view(dt).reshape(a['count'], c).astype(np.float64)
        else: arr = np.frombuffer(b, dt, a['count'] * c, o).reshape(a['count'], c).astype(np.float64)
        if a.get('normalized'):
            arr = arr / {np.uint8: 255, np.uint16: 65535, np.int8: 127, np.int16: 32767}[dt]
        return arr
    return j, acc

def rest_trs(node):
    if 'matrix' in node:
        m = np.array(node['matrix']).reshape(4, 4).T; s = np.linalg.norm(m[:3, :3], axis=0)
        return m[:3, 3].copy(), m2q(m[:3, :3] / s), s
    return np.array(node.get('translation', [0, 0, 0]), float), np.array(node.get('rotation', [0, 0, 0, 1]), float), np.array(node.get('scale', [1, 1, 1]), float)

def build(path, mapping, set_id, fps=30):
    j, acc = load(path)
    nodes = j['nodes']; parent = {}
    for i, nd in enumerate(nodes):
        for c in nd.get('children', []): parent[c] = i
    name2i = {nd.get('name'): i for i, nd in enumerate(nodes)}
    rest = [rest_trs(nd) for nd in nodes]
    order = []  # nodes parents-first
    seen = set()
    def visit(i):
        if i in seen: return
        if i in parent: visit(parent[i])
        seen.add(i); order.append(i)
    for i in range(len(nodes)): visit(i)

    def world_all(local):
        W = [None] * len(nodes)
        for i in order:
            t, r, s = local[i]; m = trs(t, r, s)
            W[i] = W[parent[i]] @ m if i in parent else m
        return W

    # bind pose from inverse bind matrices (falls back to the node rest pose)
    skin = j['skins'][0]; ibm = acc(skin['inverseBindMatrices']).reshape(-1, 4, 4).transpose(0, 2, 1)
    Wrest = world_all(rest)
    bind = {}
    for k, ji in enumerate(skin['joints']): bind[ji] = np.linalg.inv(ibm[k])
    # the skeleton's own world (armature node) applies on top of the IBM-space
    arm = parent.get(skin['joints'][0]); armW = Wrest[arm] if arm is not None else np.eye(4)
    def bindW(i): return armW @ bind[i] if i in bind else Wrest[i]

    src = [name2i[mapping[b]] for b in BONES]
    def rot(M): R = M[:3, :3]; return m2q(R / np.linalg.norm(R, axis=0))
    bpos = {b: bindW(src[k])[:3, 3] for k, b in enumerate(BONES)}
    # facing: feet->toes points forward; turn the source so forward is +Z
    fwd = (bpos["LeftToeBase"] - bpos["LeftFoot"]) + (bpos["RightToeBase"] - bpos["RightFoot"]); fwd[1] = 0; fwd /= np.linalg.norm(fwd)
    ang = math.atan2(fwd[0], fwd[2]); F = np.array([0, math.sin(-ang / 2), 0, math.cos(-ang / 2)])
    Fm = q2m(F)
    floor = min(bpos["LeftToeBase"][1], bpos["RightToeBase"][1], bpos["LeftFoot"][1], bpos["RightFoot"][1])
    hipH = bpos["Hips"][1] - floor
    dirs = []
    for b in BONES:
        if b in CHILD: d = bpos[CHILD[b]] - bpos[b]
        else: d = bpos[b] - bpos[PARENT[b]]
        d = Fm @ d; dirs.append(d / (np.linalg.norm(d) + 1e-12))
    bindQ = [rot(bindW(i)) for i in src]

    clips = []
    for an in j.get('animations', []):
        name = an['name']
        if name in ('A_TPose',): continue
        ch = {}
        T = 0.0
        for c in an['channels']:
            smp = an['samplers'][c['sampler']]; tin = acc(smp['input'])[:, 0]; out = acc(smp['output'])
            interp = smp.get('interpolation', 'LINEAR')
            if interp == 'CUBICSPLINE': out = out.reshape(len(tin), 3, -1)[:, 1, :]
            ch[(c['target']['node'], c['target']['path'])] = (tin, out, interp); T = max(T, tin[-1])
        nF = max(2, int(round(T * fps)) + 1)
        def sample(key, t, default):
            if key not in ch: return default
            tin, out, interp = ch[key]
            if t <= tin[0]: return out[0]
            if t >= tin[-1]: return out[-1]
            k = int(np.searchsorted(tin, t)) - 1; a = (t - tin[k]) / (tin[k+1] - tin[k])
            if interp == 'STEP': return out[k]
            if key[1] == 'rotation': return slerp(qnorm(out[k]), qnorm(out[k+1]), a)
            return out[k] * (1 - a) + out[k+1] * a
        frames = []
        for f in range(nF):
            t = min(T, f / fps)
            local = [(sample((i, 'translation'), t, rest[i][0]), qnorm(sample((i, 'rotation'), t, rest[i][1])), sample((i, 'scale'), t, rest[i][2])) for i in range(len(nodes))]
            W = world_all(local)
            rots = []
            for k, i in enumerate(src):
                d = qmul(rot(W[i]), qinv(bindQ[k]))         # world change from bind
                d = qnorm(qmul(qmul(F, d), qinv(F)))        # in PocketHuman's facing
                if d[3] < 0: d = -d
                rots.append(d)
            hp = Fm @ (W[src[0]][:3, 3] - bpos["Hips"]) / hipH
            frames.append((hp, rots))
        clips.append((name, set_id, fps, frames, dirs))
    print(path.split('/')[-1], len(clips), 'clips, hip height', round(hipH, 3), 'facing angle', round(math.degrees(ang)))
    return clips

out = sys.argv[1]
clips = build(sys.argv[2], map_ual1(), 1) + build(sys.argv[3], map_ual2(), 2)
with open(out, 'wb') as f:
    f.write(struct.pack('<4sII', b'PHA1', len(BONES), len(clips)))
    for name, sid, fps, frames, dirs in clips:
        nb = name.encode(); f.write(struct.pack('<B', len(nb))); f.write(nb); f.write(struct.pack('<BfI', sid, fps, len(frames)))
        f.write(np.array(dirs, '<f4').tobytes())
        for hp, rots in frames:
            f.write(np.array(hp, '<f4').tobytes()); f.write(np.clip(np.round(np.array(rots) * 32767), -32767, 32767).astype('<i2').tobytes())
print('wrote', out, len(clips), 'clips')
