#!/usr/bin/env python3
"""Differential test of all three matrix arrays through MatrixUnit."""
import os
from pathlib import Path
import subprocess
from reference_ports import payload, reference_block, snake

root = Path(__file__).resolve().parents[2]
os.chdir(root)
out = root / 'build/hdl_checks/matrix_unit'
out.mkdir(parents=True, exist_ok=True)
ref_dir = root / 'generated/hdl-reference'


def resolve(name):
    if name in ('clock', 'reset'):
        return ('clk' if name == 'clock' else 'reset'), "1'b1"
    name = name.removeprefix('io_')
    if '_bits_' in name:
        prefix, field = name.split('_bits_', 1)
        base = {'task': 'task_data', 'config': 'config_data'}.get(prefix, snake(prefix))
        return base + '.' + payload(field), 'mem_req_valid' if base == 'mem_req' else "1'b1"
    return '_'.join(snake(part) for part in name.split('_')), "1'b1"


block, checks = reference_block(ref_dir / 'MatrixUnit.sv', 'MatrixUnit', resolve)
template = (root / 'hdl/tests/matrix_unit_tb.sv').read_text()
tb = out / 'tb.sv'
tb.write_text(template.replace('/* REFERENCE */', block).replace('/* CHECKS */', checks))
subprocess.run(['verilator', '--lint-only', '-Wall', '--top-module', 'matrix_unit',
                '-f', 'hdl/filelist.f'], check=True)
subprocess.run(['verilator', '--binary', '--timing', '--assert', '--top-module', 'matrix_unit_tb',
                '--Mdir', str(out / 'obj'), '-f', 'hdl/filelist.f',
                *[str(ref_dir / name) for name in ('MatrixUnit.sv', 'MatrixArray.sv',
                  'MatrixArray_1.sv', 'SystolicPE.sv', 'SystolicPE_64.sv')], str(tb)], check=True)
subprocess.run([str(out / 'obj/Vmatrix_unit_tb')], check=True)
