#!/usr/bin/env python3
"""Cycle compare unified scalar/vector/matrix/DMA MemQ against Chisel."""
import os
from pathlib import Path
import subprocess
from reference_ports import payload, reference_block, snake

root = Path(__file__).resolve().parents[2]
os.chdir(root)
out = root / 'build/hdl_checks/lsu'
out.mkdir(parents=True, exist_ok=True)
reference = root / 'generated/hdl-reference/LSU.sv'


def resolve(name):
    if name in ('clock', 'reset'):
        return ('clk' if name == 'clock' else 'reset'), "1'b1"
    name = name.removeprefix('io_')
    if '_bits_' in name:
        prefix, field = name.split('_bits_', 1)
        base = snake(prefix)
        guard = f'{base}_valid' if base in ('request', 'dma_task', 'matrix_task', 'result') else "1'b1"
        return base + '.' + payload(field), guard
    return '_'.join(snake(part) for part in name.split('_')), "1'b1"


block, checks = reference_block(reference, 'LSU', resolve)
template = (root / 'hdl/tests/lsu_tb.sv').read_text()
tb = out / 'tb.sv'
tb.write_text(template.replace('/* REFERENCE */', block).replace('/* CHECKS */', checks))
subprocess.run(['verilator', '--lint-only', '-Wall', '--top-module', 'lsu',
                '-f', 'hdl/filelist.f'], check=True)
subprocess.run(['verilator', '--binary', '--timing', '--assert', '--top-module', 'lsu_tb',
                '--Mdir', str(out / 'obj'), '-f', 'hdl/filelist.f', str(reference), str(tb)], check=True)
subprocess.run([str(out / 'obj/Vlsu_tb')], check=True)
