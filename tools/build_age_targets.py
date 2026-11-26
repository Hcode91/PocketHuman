#!/usr/bin/env python3
"""Convert MPFB2's CC0 baby and child body macro targets for PocketHuman."""
import argparse
import gzip
import io
import json
from pathlib import Path
import re

import numpy as np
from PIL import Image


def build_age_targets(mpfb: Path, out: Path):
    root = mpfb / 'src/mpfb/data/targets'
    index = {}
    blob = io.BytesIO()
    # Body macros only. The app does not use the youth breast targets.
    for path in sorted((root / 'macrodetails').rglob('*.target.gz')):
        if not re.search(r'-(baby|child)(-|\.)', path.name):
            continue
        with gzip.open(path, 'rt') as source:
            rows = [line.split() for line in source if line.strip() and not line.startswith('#')]
        if not rows:
            index[path.relative_to(root).as_posix().removesuffix('.target.gz')] = [blob.tell(), 0]
            continue
        indices = np.asarray([row[0] for row in rows], dtype='<u2')
        offsets = np.asarray([row[1:] for row in rows], dtype=np.float32)
        nonzero = np.abs(offsets).max(axis=1) > 1e-5
        name = path.relative_to(root).as_posix().removesuffix('.target.gz')
        index[name] = [blob.tell(), int(nonzero.sum())]
        blob.write(indices[nonzero].tobytes())
        blob.write(offsets[nonzero].astype('<f2').tobytes())

    if not index:
        raise ValueError(f'No youth macro targets found in {root}')
    out.mkdir(parents=True, exist_ok=True)
    (out / 'age_targets.bin').write_bytes(blob.getvalue())
    (out / 'age_targets.json').write_text(json.dumps(index, separators=(',', ':')) + '\n')
    Image.new('RGB', (4, 4), (51, 79, 103)).save(out / 'youth_base_outfit.png')
    print(f'Built {len(index)} youth targets ({blob.tell():,} bytes)')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mpfb', type=Path)
    parser.add_argument('out', type=Path)
    args = parser.parse_args()
    build_age_targets(args.mpfb, args.out)
