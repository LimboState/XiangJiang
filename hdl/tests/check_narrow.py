#!/usr/bin/env python3
"""Check fast BF16 narrowing against the original Parts + round implementation.

This is a bit-pattern sweep, not a formal proof. Generated Chisel VectorUnit and
MatrixUnit comparisons are run separately by check_vector.py/check_matrix.py.
"""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
os.chdir(root)
output = root / 'build/hdl_checks/bf16_narrow_fast'
output.mkdir(parents=True, exist_ok=True)
for top in ('bf16_narrow_fast', 'fp_convert'):
    subprocess.run(['verilator', '--lint-only', '-Wall', '--top-module', top,
                    '-f', 'hdl/filelist.f'], check=True)
subprocess.run(['verilator', '--binary', '--timing', '--assert',
                '--top-module', 'bf16_narrow_fast_tb', '--Mdir', str(output),
                '-f', 'hdl/filelist.f', 'hdl/tests/bf16_narrow_fast_tb.sv'], check=True)
subprocess.run([str(output / 'Vbf16_narrow_fast_tb')], check=True)
