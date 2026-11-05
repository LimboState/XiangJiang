#!/usr/bin/env python3
"""Build a copied HDL-only tree, without Chisel or generated reference sources."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lint-only', action='store_true')
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    files = [line.strip() for line in (source / 'standalone.f').read_text().splitlines()
             if line.strip()]
    repository_list = (source / 'filelist.f').read_text().splitlines()
    if repository_list != ['hdl/' + name for name in files]:
        raise RuntimeError('filelist.f and standalone.f disagree')
    with tempfile.TemporaryDirectory(prefix='xj-npu-standalone-') as directory:
        target = Path(directory)
        for name in files:
            relative = Path(name)
            if relative.is_absolute() or '..' in relative.parts or relative.suffix != '.sv':
                raise RuntimeError(f'Non-local RTL dependency: {name}')
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source / relative, destination)
        shutil.copy2(source / 'standalone.f', target / 'standalone.f')
        for flags in ([], ['-DSYNTHESIS']):
            subprocess.run(['verilator', '--lint-only', '-Wall', *flags,
                            '--top-module', 'xiangjiang_npu', '-f', 'standalone.f'],
                           cwd=target, check=True)
        if not args.lint_only:
            template = (source / 'tests/npu_tb.sv').read_text()
            (target / 'npu_tb.sv').write_text(template.replace('/* REFERENCE */', '')
                                            .replace('/* CHECKS */', ''))
            subprocess.run(['verilator', '--binary', '--build-jobs', '2', '--output-groups', '0',
                            '--timing', '--assert', '--top-module', 'npu_tb', '--Mdir', 'obj',
                            '-f', 'standalone.f', 'npu_tb.sv'], cwd=target, check=True)
            subprocess.run([str(target / 'obj/Vnpu_tb')], cwd=target, check=True)
        print('PASS standalone: copied HDL-only tree; no src/ or generated/ dependencies')


if __name__ == '__main__':
    main()
