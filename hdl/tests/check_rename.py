#!/usr/bin/env python3
"""Cycle compare rename_stage to the Chisel reference."""
from pathlib import Path
import os, re, subprocess
root = Path(__file__).resolve().parents[2]; os.chdir(root)
out = root/'build/hdl_checks/rename'; out.mkdir(parents=True, exist_ok=True)
ref = root/'generated/hdl-reference/RenameStage.sv'
subprocess.run(['verilator','--lint-only','-Wall','--top-module','rename_stage',
                '-f','hdl/filelist.f'], check=True)
# uop field name -> struct field name (matches package)
uop_fields = {'inst':'inst','pc':'pc','op':'op','fu':'fu','rs1':'rs1','rs2':'rs2','rd':'rd',
    'usesRs1':'uses_rs1','usesRs2':'uses_rs2','writesRd':'writes_rd','vecOp':'vec_op',
    'vecUnary':'vec_unary','writesV':'writes_v','usesV1':'uses_v1','usesV2':'uses_v2',
    'usesVOld':'uses_v_old','usesVMask':'uses_v_mask','unmasked':'unmasked','imm':'imm',
    'useImm':'use_imm','matOp':'mat_op','matUnit':'mat_unit','matBuffer':'mat_buffer',
    'matMode':'mat_mode','matId':'mat_id','barrier':'barrier','exception':'exception',
    'cause':'cause','badAddr':'bad_addr','seq':'seq'}
header = ref.read_text().split(');',1)[0].split('(',1)[1]
decl=[]; conns=[]; checks=[]
direction = None
width = ''
for part in header.split(','):
    part=part.strip()
    match=re.fullmatch(r'(?:(input|output)\s+)?(?:([\[][^]]+[]])\s+)?(\w+)',part)
    if not match: raise ValueError(f'Unparsed reference port: {part}')
    port_direction, port_width, name = match.groups()
    direction = port_direction or direction
    width = port_width or ('' if port_direction else width)
    if direction not in ('input', 'output'): raise ValueError(part)
    if name in ('clock','reset'): expr={'clock':'clk','reset':'reset'}[name]
    elif name=='io_flush': expr='flush'
    elif name=='io_dispatch': expr='dispatch'
    elif name.startswith('io_uop_'):
        f=name[7:]
        expr=('uop.tag.slot' if f=='tag_slot' else 'uop.tag.generation' if f=='tag_generation'
              else 'uop.'+uop_fields[f])
    elif name=='io_wakeup_valid': expr='wakeup_valid'
    elif name.startswith('io_wakeup_bits_'):
        f=name[15:]; expr=('wakeup.tag.slot' if f=='tag_slot' else
            'wakeup.tag.generation' if f=='tag_generation' else
            'wakeup.'+f)
    elif name=='io_commit_valid': expr='commit_valid'
    elif name.startswith('io_commit_bits_'):
        f=name[15:]
        if f.startswith('uop_'):
            sf=f[4:]
            expr=('commit.uop.tag.slot' if sf=='tag_slot' else
                  'commit.uop.tag.generation' if sf=='tag_generation' else
                  'commit.uop.'+uop_fields[sf])
        elif f=='tag_slot': expr='commit.uop.tag.slot'
        elif f=='tag_generation': expr='commit.uop.tag.generation'
        else: expr='commit.'+f
    elif name.startswith('io_lookupValues_'):
        f=name[16:]; port=int(f[0]); sf=f[2:]
        expr=('lookup_values['+str(port)+'].'+('tag.slot' if sf=='tag_slot' else
              'tag.generation' if sf=='tag_generation' else sf))
    elif name.startswith('io_vectorLookupValues_'):
        f=name[22:]; port=int(f[0]); sf=f[2:]
        expr=('vector_lookup_values['+str(port)+'].'+('tag.slot' if sf=='tag_slot' else
              'tag.generation' if sf=='tag_generation' else sf))
    elif name.startswith('io_renamed_'):
        f=name[11:]
        if f.startswith('uop_'):
            sf=f[4:]
            expr='ref_'+name; decl.append(f'logic {width} {expr};')
            mapped=('renamed.uop.tag.slot' if sf=='tag_slot' else
                    'renamed.uop.tag.generation' if sf=='tag_generation' else
                    'renamed.uop.'+uop_fields[sf])
            checks.append(f'assert({expr} === {mapped}) else $fatal(1,"{name}");')
        else:
            if f.endswith('_tag_slot'): pref=f[:-9]; sf='tag_slot'
            elif f.endswith('_tag_generation'): pref=f[:-15]; sf='tag_generation'
            elif f.endswith('_ready'): pref=f[:-6]; sf='ready'
            elif f.endswith('_value'): pref=f[:-6]; sf='value'
            else: raise ValueError(f)
            mapped={'tag_slot':'tag.slot','tag_generation':'tag.generation','value':'value','ready':'ready'}
            expr='ref_'+name; decl.append(f'logic {width} {expr};')
            checks.append(f'assert({expr} === renamed.{pref}.{mapped[sf]}) else '
                          f'$fatal(1,"{name}");')
    elif name.startswith('io_lookupTags_'):
        port=int(name[14]); sf=name[name.rfind('_')+1:]
        expr='ref_'+name; decl.append(f'logic {width} {expr};')
        checks.append(f'assert({expr} === lookup_tags[{port}].'
                      f'{"slot" if sf=="slot" else "generation"}) else $fatal(1,"{name}");')
    elif name.startswith('io_vectorLookupTags_'):
        port=int(name[20]); sf=name[name.rfind('_')+1:]
        expr='ref_'+name; decl.append(f'logic {width} {expr};')
        checks.append(f'assert({expr} === vector_lookup_tags[{port}].'
                      f'{"slot" if sf=="slot" else "generation"}) else $fatal(1,"{name}");')
    elif name.startswith('io_debugRegs_'):
        port=int(name[len('io_debugRegs_'):]); expr='ref_'+name; decl.append(f'logic {width} {expr};')
        checks.append(f'assert({expr} === debug_regs[{port}]) else $fatal(1,"{name}");')
    elif name.startswith('io_debugVRegs_'):
        port=int(name[len('io_debugVRegs_'):]); expr='ref_'+name; decl.append(f'logic {width} {expr};')
        checks.append(f'assert({expr} === debug_v_regs[{port}]) else $fatal(1,"{name}");')
    else: raise ValueError(name)
    conns.append(f'.{name}({expr})')
assert len(conns) == len(re.findall(r'\bio_\w+|\bclock\b|\breset\b', header)), 'Unconnected reference port'
tb='''module tb;
logic clk=0,reset=1,flush=0,dispatch=0,wakeup_valid=0,commit_valid=0;
xj_pkg::micro_op_t uop;xj_pkg::exe_result_t wakeup;xj_pkg::commit_record_t commit;
xj_pkg::operand_t lookup_values[2];xj_pkg::vector_operand_t vector_lookup_values[4];
xj_pkg::issue_entry_t renamed;xj_pkg::rob_tag_t lookup_tags[2];xj_pkg::rob_tag_t vector_lookup_tags[4];
logic [31:0] debug_regs[32];logic [255:0] debug_v_regs[32];
'''+'\n'.join(decl)+'''
rename_stage dut(.clk(clk),.reset(reset),.dispatch(dispatch),.flush(flush),.uop(uop),
.wakeup_valid(wakeup_valid),.wakeup(wakeup),.commit_valid(commit_valid),.commit(commit),
.lookup_values(lookup_values),.vector_lookup_values(vector_lookup_values),.renamed(renamed),
.lookup_tags(lookup_tags),.vector_lookup_tags(vector_lookup_tags),.debug_regs(debug_regs),
.debug_v_regs(debug_v_regs));
RenameStage reference('''+',\n'.join(conns)+''');
task automatic check;
 #1;
'''+'\n'.join(checks)+'''
endtask
initial begin
 uop='0;wakeup='0;commit='0;lookup_values='{default:'0};
 vector_lookup_values='{default:'0};
 for(int n=0;n<30000;n++) begin
  clk=0;reset=(n<2 || n%4000==0);flush=($urandom_range(0,19)==0);
  dispatch=1'($urandom);wakeup_valid=1'($urandom);commit_valid=1'($urandom);
  uop.inst=$urandom;uop.pc=$urandom;uop.op=xj_pkg::opcode_t'(6'($urandom));
  uop.fu=xj_pkg::fu_t'(3'($urandom));uop.rs1=5'($urandom);uop.rs2=5'($urandom);
  uop.rd=5'($urandom);uop.uses_rs1=1'($urandom);uop.uses_rs2=1'($urandom);
  uop.writes_rd=1'($urandom);uop.vec_op=6'($urandom);uop.writes_v=1'($urandom);
  uop.uses_v1=1'($urandom);uop.uses_v2=1'($urandom);uop.uses_v_old=1'($urandom);
  uop.uses_v_mask=1'($urandom);uop.mask_reg=5'd0;uop.unmasked=1'($urandom);
  uop.imm=$urandom;uop.use_imm=1'($urandom);uop.mat_op=xj_pkg::mat_op_t'(4'($urandom));
  uop.mat_unit=2'($urandom);uop.mat_buffer=2'($urandom);uop.mat_mode=1'($urandom);
  uop.mat_id=5'($urandom);uop.barrier=1'($urandom);uop.exception=1'($urandom);
  uop.cause=5'($urandom);uop.bad_addr=$urandom;uop.tag=7'($urandom_range(0,7));
  uop.seq=16'($urandom);
  wakeup.tag=7'($urandom_range(0,7));wakeup.exception=1'($urandom);
  for(int j=0;j<8;j++) wakeup.data[32*j+:32]=$urandom;
  commit.uop=uop;commit.uop.tag=7'($urandom_range(0,7));
  commit.exception=1'($urandom);commit.cause=5'($urandom);commit.bad_addr=$urandom;
  commit.detail=$urandom;commit.next_pc=$urandom;
  for(int j=0;j<8;j++) commit.data[32*j+:32]=$urandom;
  for(int p=0;p<2;p++) begin
   lookup_values[p].ready=1'($urandom);lookup_values[p].value=$urandom;
   lookup_values[p].tag=7'($urandom_range(0,7));
  end
  for(int p=0;p<4;p++) begin
   vector_lookup_values[p].ready=1'($urandom);vector_lookup_values[p].tag=7'($urandom_range(0,7));
   for(int j=0;j<8;j++) vector_lookup_values[p].value[32*j+:32]=$urandom;
  end
  check();clk=1;check();
 end
 $display("PASS rename: 30000 cycles");$finish;
end
endmodule
'''
p=out/'tb.sv';p.write_text(tb)
subprocess.run(['verilator','--binary','--timing','--assert','--top-module','tb','--Mdir',str(out/'obj'),
 '-f','hdl/filelist.f',str(ref),str(p)],check=True)
subprocess.run([str(out/'obj/Vtb')],check=True)
