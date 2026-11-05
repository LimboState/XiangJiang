#!/usr/bin/env python3
"""Run handwritten RTL differential regressions from the repository root."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
os.chdir(root)
for script in ('run_checks.py', 'check_decode.py', 'check_control.py', 'check_rename.py',
               'check_rob.py', 'check_dma.py', 'check_lsu.py', 'check_memory_subsystem.py',
               'check_narrow.py', 'check_vector.py', 'check_matrix.py', 'check_npu.py'):
    print(f'=== {script} ===', flush=True)
    subprocess.run([sys.executable, str(root / 'hdl/tests' / script)], check=True)

for top, references in (
    ('memory_tb', []),
    ('bfloat_divider_tb', ['BFloatDivider.sv']),
    ('systolic_pe_int8_tb', ['SystolicPE_64.sv']),
    ('matrix_array_int8_tb', ['MatrixArray_1.sv', 'SystolicPE_64.sv']),
):
    output = root / 'build/hdl_checks' / ('regression_' + top)
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run(['verilator', '--binary', '--timing', '--assert', '--top-module', top,
                    '--Mdir', str(output), '-f', 'hdl/filelist.f',
                    *[str(root / 'generated/hdl-reference' / name) for name in references],
                    str(root / 'hdl/tests' / (top + '.sv'))], check=True)
    subprocess.run([str(output / ('V' + top))], check=True)
print('PASS complete handwritten RTL regression', flush=True)
