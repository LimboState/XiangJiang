#!/usr/bin/env python3
"""Compare the handwritten ROB with the generated Chisel ROB cycle by cycle."""
from pathlib import Path
import os
import re
import subprocess

root = Path(__file__).resolve().parents[2]
os.chdir(root)
output = root / 'build/hdl_checks/rob'
output.mkdir(parents=True, exist_ok=True)
reference = root / 'generated/hdl-reference/Rob.sv'
header = reference.read_text().split(');', 1)[0].split('(', 1)[1]
fields = {'usesRs1': 'uses_rs1', 'usesRs2': 'uses_rs2', 'writesRd': 'writes_rd',
          'vecOp': 'vec_op', 'vecUnary': 'vec_unary', 'writesV': 'writes_v',
          'usesV1': 'uses_v1', 'usesV2': 'uses_v2', 'usesVOld': 'uses_v_old',
          'usesVMask': 'uses_v_mask', 'useImm': 'use_imm', 'matOp': 'mat_op',
          'matUnit': 'mat_unit', 'matBuffer': 'mat_buffer', 'matMode': 'mat_mode',
          'matId': 'mat_id', 'badAddr': 'bad_addr', 'nextPc': 'next_pc'}

def field(name):
    if name == 'tag_slot':
        return 'tag.slot'
    if name == 'tag_generation':
        return 'tag.generation'
    return fields.get(name, name)

ports = []
checks = []
declarations = []
direction = None
width = ''
for part in header.split(','):
    part = part.strip()
    match = re.fullmatch(r'(?:(input|output)\s+)?(?:([\[][^]]+[]])\s+)?(\w+)', part)
    if not match:
        raise ValueError(f'Unparsed port: {part}')
    port_direction, port_width, name = match.groups()
    direction = port_direction or direction
    width = port_width or ('' if port_direction else width)
    if name == 'clock':
        expression = 'clk'
    elif name == 'reset':
        expression = 'reset'
    elif name in ('io_flush', 'io_quiescent', 'io_allocate_valid',
                  'io_retire_ready', 'io_wb_valid'):
        expression = name[3:]
    elif name.startswith('io_allocate_bits_'):
        expression = 'allocate.' + field(name[len('io_allocate_bits_'):])
    elif name.startswith('io_wb_bits_'):
        expression = 'wb.' + field(name[len('io_wb_bits_'):])
    elif name.startswith('io_query_') or name.startswith('io_vectorQuery_'):
        prefix = 'io_query_' if name.startswith('io_query_') else 'io_vectorQuery_'
        array = 'query' if prefix == 'io_query_' else 'vector_query'
        suffix = name[len(prefix):]
        port, attr = suffix.split('_', 1)
        expression = f'{array}[{port}].{attr}'
    elif direction == 'output':
        mapped = {'io_allocate_ready': 'allocate_ready', 'io_allocTag_slot': 'alloc_tag.slot',
                  'io_allocTag_generation': 'alloc_tag.generation', 'io_allocSeq': 'alloc_seq',
                  'io_head_valid': 'head_valid', 'io_retire_valid': 'retire_valid',
                  'io_wbAccepted': 'wb_accepted', 'io_empty': 'empty'}
        if name.startswith('io_head_bits_'):
            target = 'head.' + field(name[len('io_head_bits_'):])
            guard = 'head_valid'
        elif name.startswith('io_retire_bits_uop_'):
            target = 'retire.uop.' + field(name[len('io_retire_bits_uop_'):])
            guard = 'retire_valid'
        elif name.startswith('io_retire_bits_'):
            target = 'retire.' + field(name[len('io_retire_bits_'):])
            guard = 'retire_valid'
        elif name.startswith('io_values_') or name.startswith('io_vectorValues_'):
            prefix = 'io_values_' if name.startswith('io_values_') else 'io_vectorValues_'
            array = 'values' if prefix == 'io_values_' else 'vector_values'
            port, attr = name[len(prefix):].split('_', 1)
            target = f'{array}[{port}].{field(attr)}'
            guard = '1\'b1'
        else:
            target = mapped[name]
            guard = '1\'b1'
        expression = 'ref_' + name
        declarations.append(f'logic {width} {expression};')
        checks.append(f'if ({guard}) assert ({expression} === {target}) '
                      f'else $fatal(1, "cycle=%0d {name}: ref=%h dut=%h", cycle, {expression}, {target});')
    else:
        raise ValueError(name)
    ports.append(f'.{name}({expression})')

testbench = '''module tb;
logic clk=0, reset=1, flush=0, quiescent=1, allocate_valid=0, retire_ready=0, wb_valid=0;
xj_pkg::micro_op_t allocate, head;
xj_pkg::exe_result_t wb;
xj_pkg::rob_tag_t query[2], vector_query[4], alloc_tag;
xj_pkg::operand_t values[2];
xj_pkg::vector_operand_t vector_values[4];
xj_pkg::commit_record_t retire;
logic allocate_ready, head_valid, retire_valid, wb_accepted, empty;
logic [15:0] alloc_seq;
int cycle;
''' + '\n'.join(declarations) + '''
rob dut(.*);
Rob reference(''' + ',\n'.join(ports) + ''');
task automatic check;
 #1;
''' + '\n'.join(checks) + '''
endtask
initial begin
 allocate='0;wb='0;query='{default:'0};vector_query='{default:'0};
 for (int iteration=0;iteration<40000;iteration++) begin
  cycle=iteration;
  clk=0;reset=(iteration<2 || iteration%6000==0);
  quiescent=1'b1;flush=(iteration%4000==0 && iteration>2);
  allocate_valid=1'($urandom);retire_ready=1'($urandom);
  allocate.inst=$urandom;allocate.pc=$urandom;
  allocate.op=xj_pkg::opcode_t'(6'($urandom));allocate.fu=xj_pkg::fu_t'(3'($urandom));
  allocate.rs1=5'($urandom);allocate.rs2=5'($urandom);
  allocate.rd=5'($urandom);allocate.uses_rs1=1'($urandom);
  allocate.uses_rs2=1'($urandom);allocate.writes_rd=1'($urandom);
  allocate.vec_op=6'($urandom);allocate.vec_unary=2'($urandom);
  allocate.writes_v=1'($urandom);allocate.uses_v1=1'($urandom);
  allocate.uses_v2=1'($urandom);allocate.uses_v_old=1'($urandom);
  allocate.uses_v_mask=1'($urandom);allocate.unmasked=1'($urandom);
  allocate.imm=$urandom;allocate.use_imm=1'($urandom);
  allocate.mat_op=xj_pkg::mat_op_t'(4'($urandom));allocate.mat_unit=2'($urandom);
  allocate.mat_buffer=2'($urandom);allocate.mat_mode=1'($urandom);
  allocate.mat_id=5'($urandom);allocate.barrier=1'($urandom);
  allocate.exception=1'($urandom_range(0,15)==0);
  allocate.cause=5'($urandom);allocate.bad_addr=$urandom;
  allocate.tag=7'($urandom);allocate.seq=16'($urandom);
  wb_valid=1'($urandom);wb.tag=7'($urandom);wb.seq=16'($urandom);
  for(int lane=0;lane<8;lane++) wb.data[32*lane+:32]=$urandom;
  wb.exception=1'($urandom_range(0,9)==0);
  wb.cause=5'($urandom);wb.bad_addr=$urandom;
  wb.detail=$urandom;wb.next_pc=$urandom;
  for(int port=0;port<2;port++) query[port]=7'($urandom);
  for(int port=0;port<4;port++) vector_query[port]=7'($urandom);
  check();
  allocate.tag=alloc_tag;allocate.seq=alloc_seq;
  if (head_valid && $urandom_range(0,3)!=0) begin
   wb_valid=1'b1;wb.tag=head.tag;
   query[0]=head.tag;vector_query[0]=head.tag;
  end
  check();clk=1;check();
 end
 $display("PASS ROB: 40000 cycles");$finish;
end
endmodule
'''
(output / 'tb.sv').write_text(testbench)
subprocess.run(['verilator', '--lint-only', '-Wall', '--top-module', 'rob', '-f', 'hdl/filelist.f'], check=True)
subprocess.run(['verilator', '--binary', '--timing', '--assert', '--top-module', 'tb',
                '--Mdir', str(output / 'obj'), '-f', 'hdl/filelist.f',
                str(reference), str(output / 'tb.sv')], check=True)
subprocess.run([str(output / 'obj/Vtb')], check=True)
