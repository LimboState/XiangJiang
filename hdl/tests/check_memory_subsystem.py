#!/usr/bin/env python3
"""Cycle compare the AXI/TCM subsystem with the generated implementation."""
import os
from pathlib import Path
import subprocess
from reference_ports import payload, reference_block, snake

root = Path(__file__).resolve().parents[2]
os.chdir(root)
out = root / 'build/hdl_checks/memory_subsystem'
out.mkdir(parents=True, exist_ok=True)
ref_dir = root / 'generated/hdl-reference'


def resolve(name):
    if name in ('clock', 'reset'):
        return ('clk' if name == 'clock' else 'reset'), "1'b1"
    name = name.removeprefix('io_')
    if name == 'fetchReq_bits':
        return 'fetch_req', "1'b1"
    if '_bits_' in name:
        prefix, field = name.split('_bits_', 1)
        base = '_'.join(snake(part) for part in prefix.split('_'))
        guard = f'{base}_valid' if base in ('axi_b', 'axi_r', 'fetch_resp', 'data_resp', 'matrix_resp') else "1'b1"
        return base + '.' + payload(field), guard
    return '_'.join(snake(part) for part in name.split('_')), "1'b1"


block, checks = reference_block(ref_dir / 'MemorySubsystem.sv', 'MemorySubsystem', resolve)
template = (root / 'hdl/tests/memory_subsystem_tb.sv').read_text()
tb = out / 'tb.sv'
tb.write_text(template.replace('/* REFERENCE */', block).replace('/* CHECKS */', checks))
subprocess.run(['verilator', '--lint-only', '-Wall', '--top-module', 'memory_subsystem',
                '-f', 'hdl/filelist.f'], check=True)
subprocess.run(['verilator', '--binary', '--timing', '--assert', '--top-module', 'memory_subsystem_tb',
                '--Mdir', str(out / 'obj'), '-f', 'hdl/filelist.f',
                *[str(ref_dir / name) for name in ('MemorySubsystem.sv', 'InstructionTcm.sv',
                  'DataTcm.sv', 'memory_65536x32.sv', 'memory_4096x512.sv')], str(tb)], check=True)
subprocess.run([str(out / 'obj/Vmemory_subsystem_tb')], check=True)
