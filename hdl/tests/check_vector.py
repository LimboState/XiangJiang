#!/usr/bin/env python3
"""Compare all five vector execution groups, including floating-point pipelines."""
import os
from pathlib import Path
import subprocess
from reference_ports import payload, reference_block

root = Path(__file__).resolve().parents[2]
os.chdir(root)
ref_dir = root / 'generated/hdl-reference'


def resolve(name):
    if name in ('clock', 'reset'):
        return ('clk' if name == 'clock' else 'reset'), "1'b1"
    name = name.removeprefix('io_')
    if '_bits_' in name:
        base, field = name.split('_bits_', 1)
        return base + '.' + payload(field), 'resp_valid' if base == 'resp' else "1'b1"
    return name, "1'b1"


for group in range(5):
    module = 'VectorUnit' + (f'_{group}' if group else '')
    output = root / f'build/hdl_checks/vector_{group}'
    output.mkdir(parents=True, exist_ok=True)
    block, checks = reference_block(ref_dir / f'{module}.sv', module, resolve)
    template = (root / 'hdl/tests/vector_tb.sv').read_text()
    tb = output / 'tb.sv'
    tb.write_text(template.replace('/* REFERENCE */', block).replace('/* CHECKS */', checks)
                  .replace('GROUP_VALUE', str(group)))
    subprocess.run(['verilator', '--lint-only', '-Wall', '--top-module', 'vector_unit',
                    f'-GG_GROUP={group}', '-f', 'hdl/filelist.f'], check=True)
    subprocess.run(['verilator', '--binary', '--timing', '--assert', '--top-module', 'vector_tb',
                    '--Mdir', str(output / 'obj'), '-f', 'hdl/filelist.f',
                    str(ref_dir / f'{module}.sv'), str(ref_dir / 'BFloatDivider.sv'), str(tb)], check=True)
    subprocess.run([str(output / 'obj/Vvector_tb')], check=True)
