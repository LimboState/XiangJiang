#!/usr/bin/env python3
"""Record or check the Chisel/document/generated-RTL baseline used for comparison."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def hashes(root, paths):
    return {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(paths)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    path = root / 'hdl/reference_manifest.json'
    generated = list((root / 'generated/hdl-reference').glob('*.sv'))
    if not generated:
        raise RuntimeError('Generate the Chisel reference before recording or checking hashes')
    current = {
        'git_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        'command': './mill xj.runMain top.Generate generated/hdl-reference',
        'configuration': 'XJParams() defaults; see generated/hdl-reference/xiangjiang-config.json',
        'sha256': hashes(root, list((root / 'src/main/scala').rglob('*.scala'))
                         + list((root / 'docs').glob('*.md'))),
        'generated_sha256': hashes(root, generated),
    }
    if args.check:
        recorded = json.loads(path.read_text())
        for key in ('sha256', 'generated_sha256'):
            if recorded[key] != current[key]:
                changed = sorted(name for name in recorded[key].keys() | current[key].keys()
                                 if recorded[key].get(name) != current[key].get(name))
                raise RuntimeError(f'Reference baseline changed: {changed}')
        print('PASS reference manifest: source, documentation and generated RTL hashes match')
    else:
        path.write_text(json.dumps(current, indent=2) + '\n')
        print(f'Recorded {path}')


if __name__ == '__main__':
    main()
