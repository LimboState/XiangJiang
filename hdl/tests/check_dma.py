#!/usr/bin/env python3
"""DMA differential regression, including valid bursts and protocol poison."""
import os
from pathlib import Path
import subprocess
from reference_ports import payload, reference_block, snake

root = Path(__file__).resolve().parents[2]
os.chdir(root)
out = root / 'build/hdl_checks/dma'
out.mkdir(parents=True, exist_ok=True)
reference = root / 'generated/hdl-reference/DmaEngine.sv'


def resolve(name):
    if name in ('clock', 'reset'):
        return ('clk' if name == 'clock' else 'reset'), "1'b1"
    name = name.removeprefix('io_')
    if '_bits_' in name:
        prefix, field = name.split('_bits_', 1)
        base = '_'.join(snake(part) for part in prefix.split('_'))
        guard = f'{base}_valid' if base not in ('commit', 'command', 'local_resp') else "1'b1"
        return base + '.' + payload(field), guard
    if name.startswith('status_'):
        return 'status.' + snake(name.removeprefix('status_')), "1'b1"
    if name.startswith('fault_'):
        return 'fault.' + snake(name.removeprefix('fault_')), "1'b1"
    return '_'.join(snake(part) for part in name.split('_')), "1'b1"


block, checks = reference_block(reference, 'DmaEngine', resolve)
template = (root / 'hdl/tests/dma_tb.sv').read_text()
tb = out / 'tb.sv'
tb.write_text(template.replace('/* REFERENCE */', block).replace('/* CHECKS */', checks))
subprocess.run(['verilator', '--lint-only', '-Wall', '--top-module', 'dma_engine',
                '-f', 'hdl/filelist.f'], check=True)
subprocess.run(['verilator', '--binary', '--timing', '--assert', '--top-module', 'dma_tb',
                '--Mdir', str(out / 'obj'), '-f', 'hdl/filelist.f', str(reference), str(tb)], check=True)
subprocess.run([str(out / 'obj/Vdma_tb')], check=True)
