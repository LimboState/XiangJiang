#!/usr/bin/env python3
"""Cycle compare AXI-Lite controller to Chisel, including independent channels."""
from pathlib import Path
import os,re,subprocess
root=Path(__file__).resolve().parents[2];os.chdir(root)
out=root/'build/hdl_checks/control';out.mkdir(parents=True,exist_ok=True)
ref=root/'generated/hdl-reference/ControlRegisters.sv'
subprocess.run(['verilator','--lint-only','-Wall','--top-module','control_registers','-f','hdl/filelist.f'],check=True)
fields={'faultPc':'fault_pc','faultInst':'fault_inst','faultCause':'fault_cause',
'faultAddr':'fault_addr','faultDetail':'fault_detail','rowBytes':'row_bytes',
'externalStride':'external_stride','localStride':'local_stride','bytesDone':'bytes_done','rowsDone':'rows_done'}
mapping={'clock':'clk','reset':'reset','io_hostIdle':'host_idle','io_start':'ref_start','io_ack':'ref_ack'}
for p in ['aw_ready','aw_valid','w_ready','w_valid','b_ready','b_valid','ar_ready','ar_valid','r_ready','r_valid']:
 mapping['io_axi_'+p]=('ref_' if p.endswith('ready') and p not in ['b_ready','r_ready'] or p in ['b_valid','r_valid'] else '')+p
mapping.update({'io_axi_aw_bits_addr':'aw_addr','io_axi_w_bits_data':'w_data',
'io_axi_w_bits_strb':'w_strb','io_axi_b_bits':'ref_b_resp','io_axi_ar_bits_addr':'ar_addr',
'io_axi_r_bits_data':'ref_r_data','io_axi_r_bits_resp':'ref_r_resp'})
ports=re.findall(r'\b(clock|reset|io_\w+)\b',ref.read_text().split(');',1)[0])
conns=[]
for p in ports:
 if p.startswith('io_status_'): expr='status.'+'.'.join(fields.get(x,x) for x in p[10:].split('_'))
 elif p.startswith('io_debugRegs_'):expr='debug_regs['+p.split('_')[-1]+']'
 else:expr=mapping[p]
 conns.append(f'.{p}({expr})')
tb='''module tb;
logic clk=0,reset=1,host_idle=0;
xj_pkg::core_status_t status;
logic aw_valid,w_valid,b_ready,ar_valid,r_ready;
logic [31:0] aw_addr,w_data,ar_addr;
logic [3:0] w_strb;
logic [31:0] debug_regs[32];
logic aw_ready,w_ready,b_valid,ar_ready,r_valid,start,ack;
logic ref_aw_ready,ref_w_ready,ref_b_valid,ref_ar_ready,ref_r_valid,ref_start,ref_ack;
logic [1:0] b_resp,r_resp,ref_b_resp,ref_r_resp;
logic [31:0] r_data,ref_r_data;
control_registers dut(.clk(clk),.reset(reset),.host_idle(host_idle),.status(status),
.aw_valid(aw_valid),.aw_addr(aw_addr),.w_valid(w_valid),.w_data(w_data),.w_strb(w_strb),
.b_ready(b_ready),.ar_valid(ar_valid),.ar_addr(ar_addr),.r_ready(r_ready),
.debug_regs(debug_regs),.aw_ready(aw_ready),.w_ready(w_ready),.b_valid(b_valid),
.b_resp(b_resp),.ar_ready(ar_ready),.r_valid(r_valid),.r_data(r_data),.r_resp(r_resp),
.start(start),.ack(ack));
ControlRegisters reference('''+',\n'.join(conns)+''');
task automatic check;
 assert({aw_ready,w_ready,b_valid,ar_ready,r_valid,start,ack} ===
 {ref_aw_ready,ref_w_ready,ref_b_valid,ref_ar_ready,ref_r_valid,ref_start,ref_ack})
 else $fatal(1,"control handshake");
 if(b_valid) assert(b_resp === ref_b_resp) else $fatal(1,"control B");
 if(r_valid) assert({r_data,r_resp} === {ref_r_data,ref_r_resp}) else $fatal(1,"control R");
endtask
initial begin
 status='0;
 for(int i=0;i<32;i++) debug_regs[i]=32'(i)*32'h01010101;
 for(int n=0;n<20000;n++) begin
  clk=0;reset=(n<2 || n%1000==0);
  aw_valid=1'($urandom);w_valid=1'($urandom);b_ready=1'($urandom);
  ar_valid=1'($urandom);r_ready=1'($urandom);host_idle=1'($urandom);
  aw_addr=(n%3==0)?32'd4:32'd0;w_data=$urandom;w_strb=4'($urandom);
  ar_addr=32'($urandom_range(0,127))*4;
  status.busy=1'($urandom);status.done=1'($urandom);status.fault=1'($urandom);
  status.fault_pc=$urandom;status.fault_inst=$urandom;status.fault_cause=5'($urandom);
  status.fault_addr=$urandom;status.fault_detail=$urandom;
  status.dma.row_bytes=$urandom;status.dma.rows=$urandom;
  status.dma.external_stride=$urandom;status.dma.local_stride=$urandom;
  status.dma.bytes_done=$urandom;status.dma.rows_done=$urandom;
  status.dma.active=1'($urandom);status.dma.outbound=1'($urandom);
  status.dma.error=1'($urandom);status.dma.poisoned=1'($urandom);
  #1;if(n>1) check();clk=1;#1;check();
 end
 $display("PASS control: 20000 cycles");$finish;
end
endmodule
'''
p=out/'tb.sv';p.write_text(tb)
subprocess.run(['verilator','--binary','--timing','--assert','--top-module','tb','--Mdir',str(out/'obj'),
 '-f','hdl/filelist.f',str(ref),str(p)],check=True)
subprocess.run([str(out/'obj/Vtb')],check=True)
