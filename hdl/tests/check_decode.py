#!/usr/bin/env python3
"""Compare every exposed DecodeUnit output for random and encoding-sweep inputs."""
from pathlib import Path
import re, subprocess, os
root=Path(__file__).resolve().parents[2];os.chdir(root)
out=root/'build/hdl_checks/decode';out.mkdir(parents=True,exist_ok=True)
ref=root/'generated/hdl-reference/DecodeUnit.sv'
subprocess.run(['verilator','--lint-only','-Wall','--top-module','decode_unit','-f','hdl/filelist.f'],check=True)
fields={'badAddr':'bad_addr','useImm':'use_imm','usesRs1':'uses_rs1','usesRs2':'uses_rs2',
'writesRd':'writes_rd','vecOp':'vec_op','vecUnary':'vec_unary','writesV':'writes_v',
'usesV1':'uses_v1','usesV2':'uses_v2','usesVOld':'uses_v_old','usesVMask':'uses_v_mask',
'matOp':'mat_op','matUnit':'mat_unit','matBuffer':'mat_buffer','matMode':'mat_mode','matId':'mat_id'}
header=ref.read_text().split(');',1)[0].split('(',1)[1]
decl=[];conns=[];checks=[]
for part in header.split(','):
 part=part.strip();m=re.match(r'(input|output)\s*(\[[^]]+\])?\s*(\w+)$',part)
 if m:direction,width,name=m.groups();width=width or ''
 else:name=part
 if direction=='input':expr='packet.'+fields.get(name[6:],name[6:])
 else:
  expr='ref_'+name;decl.append(f'logic {width} {expr};')
  field=fields.get(name[7:],name[7:])
  checks.append(f'assert(uop.{field} === {expr}) else $fatal(1,"{field} inst=%h",packet.inst);')
 conns.append(f'.{name}({expr})')
tb='''module tb;
xj_pkg::fetch_packet_t packet;
xj_pkg::micro_op_t uop;
'''+ '\n'.join(decl)+'''
decode_unit dut(.packet(packet),.uop(uop));
DecodeUnit reference('''+',\n'.join(conns)+''');
task automatic check;
 #1;
'''+ '\n'.join(checks)+'''
endtask
initial begin
 packet='0;
 for(int n=0;n<100000;n++) begin
  packet.inst=$urandom;packet.pc=$urandom;packet.exception=(n%13==0);
  packet.cause=4'($urandom);packet.bad_addr=$urandom;check();
 end
 packet.exception=0;
 // All opcode/funct3/funct7 combinations, with reserved register fields zero.
 for(int opc=0;opc<128;opc++)
  for(int f3=0;f3<8;f3++)
   for(int f7=0;f7<128;f7++) begin
    packet.inst=(32'(f7)<<25)|(32'(f3)<<12)|32'(opc);check();
   end
 $display("PASS decode: 100000 random + 131072 encoding sweep");$finish;
end
endmodule
'''
p=out/'tb.sv';p.write_text(tb)
subprocess.run(['verilator','--binary','--timing','--assert','--top-module','tb','--Mdir',str(out/'obj'),
 '-f','hdl/filelist.f',str(ref),str(p)],check=True)
subprocess.run([str(out/'obj/Vtb')],check=True)
