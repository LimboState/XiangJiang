#!/usr/bin/env python3
"""Lint handwritten RTL, then differential-test scalar FUs and frontend.
Run in the XJ conda environment from any directory. Generated RTL is an oracle,
not included in the handwritten synthesis filelist. No warning suppressions for
handwritten RTL; test/oracle warnings do not affect the separate strict lint.
"""
from pathlib import Path
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
os.chdir(ROOT)
OUT = ROOT / 'build' / 'hdl_checks'
OUT.mkdir(parents=True, exist_ok=True)
REF = ROOT / 'generated' / 'hdl-reference'

def run(args):
    subprocess.run(args, check=True)

for top in ['alu_unit', 'branch_unit', 'frontend', 'issue_unit', 'instruction_tcm', 'data_tcm']:
    run(['verilator', '--lint-only', '-Wall', '--top-module', top, '-f', 'hdl/filelist.f'])
for args in [['-GG_HEAD_ONLY=1', '-GG_ENTRIES=1'], ['-GG_VECTOR_ENABLED=1'],
             ['-GG_VECTOR_ENABLED=1', '-GG_ISSUE_ON_WAKEUP=1']]:
    run(['verilator', '--lint-only', '-Wall', '--top-module', 'issue_unit',
         '-f', 'hdl/filelist.f', *args])

for kind, ref in [('alu_unit', 'ALUUnit'), ('branch_unit', 'BranchUnit')]:
    mappings = {'clock':'clk', 'reset':'reset', 'io_req_valid':'req_valid',
        'io_resp_ready':'resp_ready','io_flush':'flush','io_req_bits_uop_pc':'pc',
        'io_req_bits_uop_op':'op','io_req_bits_uop_imm':'imm',
        'io_req_bits_uop_useImm':'use_imm','io_req_bits_uop_tag_slot':'tag.slot',
        'io_req_bits_uop_tag_generation':'tag.generation','io_req_bits_uop_seq':'seq',
        'io_req_bits_src1_value':'a','io_req_bits_src2_value':'b',
        'io_req_ready':'ref_ready','io_resp_valid':'ref_valid','io_busy':'ref_busy'}
    for field in ['data','seq','exception','cause','detail']:
        mappings['io_resp_bits_'+field]='ref_result.'+field
    mappings.update({'io_resp_bits_tag_slot':'ref_result.tag.slot',
        'io_resp_bits_tag_generation':'ref_result.tag.generation',
        'io_resp_bits_badAddr':'ref_result.bad_addr','io_resp_bits_nextPc':'ref_result.next_pc'})
    header=(REF/(ref+'.sv')).read_text().split(');',1)[0]
    ports=re.findall(r'\b(clock|reset|io_\w+)\b',header)
    connections=',\n'.join(f'.{p}({mappings[p]})' for p in ports)
    extra='.use_imm(use_imm),' if kind=='alu_unit' else ''
    tb=f'''module tb;
logic clk=0, reset=1, flush=0, req_valid=0, resp_ready=0;
logic use_imm; logic [5:0] op; logic [31:0] a,b,pc,imm;
logic [15:0] seq; xj_pkg::rob_tag_t tag;
logic dut_ready,dut_valid,dut_busy,ref_ready,ref_valid,ref_busy;
xj_pkg::exe_result_t dut_result,ref_result;
{kind} dut(.clk(clk),.reset(reset),.flush(flush),.req_valid(req_valid),
.resp_ready(resp_ready),{extra}.op(op),.a(a),.b(b),.pc(pc),.imm(imm),.seq(seq),.tag(tag),
.req_ready(dut_ready),.resp_valid(dut_valid),.busy(dut_busy),.resp(dut_result));
{ref} reference({connections});
task automatic check;
 assert ({{dut_ready,dut_valid,dut_busy}} === {{ref_ready,ref_valid,ref_busy}}) else $fatal(1,"handshake");
 if(dut_valid) assert(dut_result === ref_result) else $fatal(1,"payload op=%0d",op);
endtask
initial begin
 for(int n=0;n<12000;n++) begin
  clk=0; reset=(n<2 || n%1000==0); flush=($urandom_range(0,31)==0);
  req_valid=1'($urandom_range(0,1));resp_ready=1'($urandom_range(0,1));
  op=6'(n%64);a=$urandom;b=$urandom;pc=$urandom;imm=$urandom;
  if(n%3==0) pc=32'($urandom_range(0,65535))*4;
  if(n%4==0) imm=0;
  if(n%5==0) b=a;
  use_imm=1'($urandom_range(0,1));tag=7'($urandom);seq=16'($urandom);
  #1; if(n>1) check(); clk=1; #1; check();
 end
 $display("PASS {kind}: 12000 cycles");$finish;
end
endmodule
'''
    path=OUT/(kind+'_tb.sv');path.write_text(tb)
    obj=OUT/kind
    run(['verilator','--binary','--timing','--assert','--top-module','tb',
         '--Mdir',str(obj),'-f','hdl/filelist.f',str(REF/(ref+'.sv')),str(path)])
    run([str(obj/'Vtb')])

# Random control overlap, stale responses, barriers, restarts and backpressure.
tb='''module tb;
logic clk=0,reset=1,start=0,stop_run=0,barrier=0,redirect_valid=0;
logic response_valid=0,fetch_ready=0;
logic [31:0] redirect_pc;
xj_pkg::fetch_packet_t response,fetch,ref_fetch;
logic request_valid,fetch_valid,ref_request_valid,ref_fetch_valid;
logic [31:0] request_pc,ref_request_pc;
frontend dut(.clk(clk),.reset(reset),.start(start),.stop_run(stop_run),.barrier(barrier),
.redirect_valid(redirect_valid),.redirect_pc(redirect_pc),.response_valid(response_valid),
.response(response),.fetch_ready(fetch_ready),.request_valid(request_valid),
.request_pc(request_pc),.fetch_valid(fetch_valid),.fetch(fetch));
Frontend reference(.clock(clk),.reset(reset),.io_start(start),.io_stopRun(stop_run),
.io_barrier(barrier),.io_redirect_valid(redirect_valid),.io_redirect_bits(redirect_pc),
.io_request_valid(ref_request_valid),.io_request_bits(ref_request_pc),
.io_response_valid(response_valid),.io_response_bits_pc(response.pc),
.io_response_bits_inst(response.inst),.io_response_bits_exception(response.exception),
.io_response_bits_cause(response.cause),.io_response_bits_badAddr(response.bad_addr),
.io_fetch_ready(fetch_ready),.io_fetch_valid(ref_fetch_valid),.io_fetch_bits_pc(ref_fetch.pc),
.io_fetch_bits_inst(ref_fetch.inst),.io_fetch_bits_exception(ref_fetch.exception),
.io_fetch_bits_cause(ref_fetch.cause),.io_fetch_bits_badAddr(ref_fetch.bad_addr));
logic outstanding=0;logic [31:0] outstanding_pc;
task automatic check;
 assert({request_valid,request_pc,fetch_valid} ===
        {ref_request_valid,ref_request_pc,ref_fetch_valid}) else $fatal(1,"frontend control");
 if(fetch_valid) assert(fetch === ref_fetch) else $fatal(1,"frontend payload");
endtask
initial begin
 for(int n=0;n<20000;n++) begin
  clk=0; reset=(n<2);start=($urandom_range(0,19)==0);
  stop_run=($urandom_range(0,19)==0);barrier=1'($urandom_range(0,1));
  redirect_valid=($urandom_range(0,19)==0);redirect_pc=$urandom;
  fetch_ready=1'($urandom_range(0,1));
  response_valid=outstanding && ($urandom_range(0,3)==0);
  response.pc=outstanding_pc; response.inst=$urandom;response.cause=4'($urandom);
  response.exception=1'($urandom_range(0,1)); response.bad_addr=$urandom;
  #1;if(n>1) check();
  if(response_valid) outstanding=0;
  if(request_valid) begin outstanding=1;outstanding_pc=request_pc;end
  clk=1;#1;check();
 end
 $display("PASS frontend: 20000 cycles");$finish;
end
endmodule
'''
path=OUT/'frontend_tb.sv';path.write_text(tb)
run(['verilator','--binary','--timing','--assert','--top-module','tb','--Mdir',
     str(OUT/'frontend'),'-f','hdl/filelist.f',str(REF/'Frontend.sv'),str(path)])
run([str(OUT/'frontend'/'Vtb')])

# Derive only port adapters from the generated headers, never the DUT logic.
for ref, head_only, vector in [('IssueUnit', 0, 0), ('IssueUnit_1', 1, 0), ('IssueUnit_2', 0, 1)]:
    header = (REF/(ref+'.sv')).read_text().split(');', 1)[0]
    header = header[header.index('(')+1:]
    fields = {'useImm':'use_imm', 'matOp':'mat_op', 'matUnit':'mat_unit',
              'vecOp':'vec_op', 'vecUnary':'vec_unary', 'usesV1':'uses_v1',
              'usesV2':'uses_v2', 'usesVOld':'uses_v_old', 'usesVMask':'uses_v_mask'}
    def field_expr(prefix, suffix):
        return prefix+'.'+'.'.join(fields.get(s,s) for s in suffix.split('_'))
    conns=[]; declarations=[]; checks=[]; direction=None; width=''
    plain={'clock':'clk','reset':'reset','io_dispatch_valid':'dispatch_valid',
           'io_dispatch_ready':'ref_ready','io_wakeup_valid':'wakeup_valid',
           'io_issue_ready':'issue_ready','io_issue_valid':'ref_valid',
           'io_empty':'ref_empty','io_flush':'flush','io_head_valid':'head_valid',
           'io_head_bits_slot':'head_tag.slot','io_head_bits_generation':'head_tag.generation'}
    for part in header.split(','):
        part=part.strip()
        match=re.match(r'(input|output)\s*(\[[^]]+\])?\s*(\w+)$',part)
        if match: direction,width,name=match.groups();width=width or ''
        else: name=part
        if name in plain: expr=plain[name]
        elif name.startswith('io_dispatch_bits_'): expr=field_expr('dispatch',name[len('io_dispatch_bits_'):])
        elif name.startswith('io_wakeup_bits_'): expr=field_expr('wakeup',name[len('io_wakeup_bits_'):])
        elif name.startswith('io_issue_bits_'):
            expr='ref_'+name
            declarations.append(f'logic {width} {expr};')
            dut=field_expr('issue',name[len('io_issue_bits_'):])
            checks.append(f'assert ({dut} === {expr}) else $fatal(1,"{name}");')
        else: raise ValueError(name)
        conns.append(f'.{name}({expr})')
    tb='''module tb;
logic clk=0,reset=1,flush=0,dispatch_valid=0,wakeup_valid=0,issue_ready=0,head_valid=0;
logic dut_ready,dut_valid,dut_empty,ref_ready,ref_valid,ref_empty;
xj_pkg::issue_entry_t dispatch,issue;
xj_pkg::exe_result_t wakeup;
xj_pkg::rob_tag_t head_tag;
'''+ '\n'.join(declarations)+f'''
issue_unit #(.G_HEAD_ONLY({head_only}),.G_VECTOR_ENABLED({vector})) dut(
.clk(clk),.reset(reset),.flush(flush),.head_valid(head_valid),.head_tag(head_tag),
.dispatch_valid(dispatch_valid),.dispatch(dispatch),.wakeup_valid(wakeup_valid),
.wakeup(wakeup),.issue_ready(issue_ready),.dispatch_ready(dut_ready),
.issue_valid(dut_valid),.issue(issue),.empty(dut_empty));
{ref} reference('''+',\n'.join(conns)+''');
task automatic check;
 assert({dut_ready,dut_valid,dut_empty} === {ref_ready,ref_valid,ref_empty})
  else $fatal(1,"issue handshake");
 if(dut_valid) begin
'''+ '\n'.join(checks)+'''
 end
endtask
initial begin
 dispatch='0;wakeup='0;head_tag='0;
 for(int n=0;n<20000;n++) begin
  clk=0;reset=(n<2 || n%2000==0);flush=($urandom_range(0,99)==0);
  dispatch_valid=1'($urandom);issue_ready=1'($urandom);wakeup_valid=1'($urandom);
  dispatch.uop.pc=$urandom;dispatch.uop.seq=16'($urandom);
  dispatch.uop.op=xj_pkg::opcode_t'(6'($urandom));dispatch.uop.imm=$urandom;
  dispatch.uop.fu=xj_pkg::fu_t'(3'($urandom));dispatch.uop.use_imm=1'($urandom);
  dispatch.uop.mat_op=xj_pkg::mat_op_t'(4'($urandom));dispatch.uop.mat_unit=2'($urandom);
  dispatch.uop.rs1=5'($urandom);dispatch.uop.vec_op=6'($urandom);
  dispatch.uop.vec_unary=2'($urandom);dispatch.uop.unmasked=1'($urandom);
  dispatch.uop.tag=7'($urandom_range(0,7));
  dispatch.uop.uses_v1=1;dispatch.uop.uses_v2=1;
  dispatch.uop.uses_v_old=1;dispatch.uop.uses_v_mask=1;
  dispatch.src1.ready=1'($urandom);dispatch.src2.ready=1'($urandom);
  dispatch.src1.tag=7'($urandom_range(0,7));dispatch.src2.tag=7'($urandom_range(0,7));
  dispatch.src1.value=$urandom;dispatch.src2.value=$urandom;
  dispatch.vsrc1='0;dispatch.vsrc2='0;dispatch.vold='0;dispatch.vmask='0;
  dispatch.vsrc1.ready=1'($urandom);dispatch.vsrc2.ready=1'($urandom);
  dispatch.vold.ready=1'($urandom);dispatch.vmask.ready=1'($urandom);
  dispatch.vsrc1.tag=7'($urandom_range(0,7));dispatch.vsrc2.tag=7'($urandom_range(0,7));
  dispatch.vold.tag=7'($urandom_range(0,7));dispatch.vmask.tag=7'($urandom_range(0,7));
  wakeup.tag=7'($urandom_range(0,7));wakeup.exception=($urandom_range(0,9)==0);
  for(int j=0;j<8;j++) begin
   wakeup.data[32*j+:32]=$urandom;
   dispatch.vsrc1.value[32*j+:32]=$urandom;dispatch.vsrc2.value[32*j+:32]=$urandom;
   dispatch.vold.value[32*j+:32]=$urandom;dispatch.vmask.value[32*j+:32]=$urandom;
  end
  head_valid=1;head_tag=7'($urandom_range(0,7));
  #1;if(n>1) check();clk=1;#1;check();
 end
 $display("PASS issue variant: 20000 cycles");$finish;
end
endmodule
'''
    path=OUT/(ref+'_tb.sv');path.write_text(tb)
    run(['verilator','--binary','--timing','--assert','--top-module','tb','--Mdir',
         str(OUT/ref),'-f','hdl/filelist.f',str(REF/(ref+'.sv')),str(path)])
    run([str(OUT/ref/'Vtb')])
