#!/usr/bin/env python3
"""Full-chip cycle differential with host-loaded programs and an external AXI model."""
import os
from pathlib import Path
import subprocess
from reference_ports import payload, reference_block, snake

root = Path(__file__).resolve().parents[2]
os.chdir(root)
out = root / 'build/hdl_checks/npu'
out.mkdir(parents=True, exist_ok=True)
ref_dir = root / 'generated/hdl-reference'


def resolve(name):
    if name in ('clock', 'reset'):
        return ('clk' if name == 'clock' else 'reset'), "1'b1"
    name = name.removeprefix('io_')
    if name in ('control_aw_bits_prot', 'control_ar_bits_prot'):
        return name.replace('_bits_', '_'), "1'b1"
    if name == 'control_b_bits':
        return 'control_b_resp', 'control_b_valid'
    if name.startswith('control_') and '_bits_' in name:
        prefix, field = name.split('_bits_', 1)
        return prefix + '_' + field, f'{prefix}_valid'
    if name.startswith('status_'):
        return 'status.' + payload(name.removeprefix('status_')), "1'b1"
    if name.startswith('debugRegs_'):
        return 'debug_regs[' + name.removeprefix('debugRegs_') + ']', "1'b1"
    if name.startswith('debugVRegs_'):
        return 'debug_v_regs[' + name.removeprefix('debugVRegs_') + ']', "1'b1"
    if '_bits_' in name:
        prefix, field = name.split('_bits_', 1)
        return prefix + '.' + payload(field), f'{prefix}_valid'
    return '_'.join(snake(part) for part in name.split('_')), "1'b1"


block, checks = reference_block(ref_dir / 'XiangJiangNPU.sv', 'XiangJiangNPU', resolve)
template = (root / 'hdl/tests/npu_tb.sv').read_text()
tb = out / 'tb.sv'
tb.write_text(template.replace('/* REFERENCE */', block).replace('/* CHECKS */', checks))
subprocess.run(['verilator', '--lint-only', '-Wall', '--top-module', 'xiangjiang_npu',
                '-f', 'hdl/filelist.f'], check=True)
subprocess.run(['verilator', '--binary', '--build-jobs', '2', '--output-groups', '0',
                '--timing', '--assert', '--top-module', 'npu_tb',
                '--Mdir', str(out / 'obj'), '-f', 'hdl/filelist.f',
                *map(str, sorted(ref_dir.glob('*.sv'))), str(tb)], check=True)
subprocess.run([str(out / 'obj/Vnpu_tb')], check=True)
