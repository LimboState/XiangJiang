`default_nettype none
module xiangjiang_npu (
    input logic clk,
    input logic reset,
    input logic control_aw_valid,
    input logic [31:0] control_aw_addr,
    // AXI-Lite protection attributes are accepted but ignored, matching Chisel.
    /* verilator lint_off UNUSEDSIGNAL */
    input logic [2:0] control_aw_prot,
    input logic [2:0] control_ar_prot,
    /* verilator lint_on UNUSEDSIGNAL */
    input logic control_w_valid,
    input logic [31:0] control_w_data,
    input logic [3:0] control_w_strb,
    input logic control_b_ready,
    input logic control_ar_valid,
    input logic [31:0] control_ar_addr,
    input logic control_r_ready,
    input logic memory_aw_valid,
    input xj_pkg::axi_address_t memory_aw,
    input logic memory_w_valid,
    input xj_pkg::axi_write_data_t memory_w,
    input logic memory_b_ready,
    input logic memory_ar_valid,
    input xj_pkg::axi_address_t memory_ar,
    input logic memory_r_ready,
    input logic external_aw_ready,
    input logic external_w_ready,
    input logic external_b_valid,
    input xj_pkg::axi_write_response_t external_b,
    input logic external_ar_ready,
    input logic external_r_valid,
    input xj_pkg::axi_read_data_t external_r,
    output logic control_aw_ready,
    output logic control_w_ready,
    output logic control_b_valid,
    output logic [1:0] control_b_resp,
    output logic control_ar_ready,
    output logic control_r_valid,
    output logic [31:0] control_r_data,
    output logic [1:0] control_r_resp,
    output logic memory_aw_ready,
    output logic memory_w_ready,
    output logic memory_b_valid,
    output xj_pkg::axi_write_response_t memory_b,
    output logic memory_ar_ready,
    output logic memory_r_valid,
    output xj_pkg::axi_read_data_t memory_r,
    output logic external_aw_valid,
    output xj_pkg::axi_address_t external_aw,
    output logic external_w_valid,
    output xj_pkg::axi_write_data_t external_w,
    output logic external_b_ready,
    output logic external_ar_valid,
    output xj_pkg::axi_address_t external_ar,
    output logic external_r_ready,
    output xj_pkg::core_status_t status,
    output logic retire_valid,
    output xj_pkg::commit_record_t retire,
    output logic [31:0] debug_regs [32],
    output logic [255:0] debug_v_regs [32]
);
    logic f_start_pending;
    logic w_start;
    logic w_ack;
    logic w_host_idle;
    xj_pkg::core_status_t w_core_status;
    logic w_fetch_req_valid;
    logic [31:0] w_fetch_req;
    // The integrated TCM is always ready; this output is checked in simulation.
    /* verilator lint_off UNUSEDSIGNAL */
    logic w_fetch_req_ready;
    /* verilator lint_on UNUSEDSIGNAL */
    logic w_fetch_resp_valid;
    xj_pkg::fetch_packet_t w_fetch_resp;
    logic w_data_req_valid;
    xj_pkg::word_req_t w_data_req;
    logic w_data_req_ready;
    logic w_data_resp_valid;
    xj_pkg::word_resp_t w_data_resp;
    logic w_data_resp_ready;
    logic w_matrix_req_valid;
    xj_pkg::matrix_beat_req_t w_matrix_req;
    logic w_matrix_req_ready;
    logic w_matrix_resp_valid;
    xj_pkg::matrix_beat_resp_t w_matrix_resp;
    logic w_matrix_resp_ready;
    logic w_unused_writeback_valid;
    xj_pkg::exe_result_t w_unused_writeback;
    logic w_unused_alu_valid;
    xj_pkg::rob_tag_t w_unused_alu;
    logic w_unused_agu_valid;
    xj_pkg::rob_tag_t w_unused_agu;
    always_ff @(posedge clk) begin
        if (reset) f_start_pending <= 1'b0;
        else f_start_pending <= w_start;
`ifndef SYNTHESIS
        if (!reset) assert (w_fetch_req_ready) else $error("TCM fetch must be always ready");
`endif
    end
    always_comb begin
        status = w_core_status;
        status.busy = w_core_status.busy || f_start_pending;
    end
    control_registers i_control_registers (
        .clk(clk), .reset(reset), .host_idle(w_host_idle), .status(status),
        .aw_valid(control_aw_valid), .aw_addr(control_aw_addr),
        .w_valid(control_w_valid), .w_data(control_w_data), .w_strb(control_w_strb),
        .b_ready(control_b_ready), .ar_valid(control_ar_valid), .ar_addr(control_ar_addr),
        .r_ready(control_r_ready), .debug_regs(debug_regs),
        .aw_ready(control_aw_ready), .w_ready(control_w_ready),
        .b_valid(control_b_valid), .b_resp(control_b_resp), .ar_ready(control_ar_ready),
        .r_valid(control_r_valid), .r_data(control_r_data), .r_resp(control_r_resp),
        .start(w_start), .ack(w_ack)
    );
    memory_subsystem i_memory_subsystem (
        .clk(clk), .reset(reset), .core_busy(status.busy), .bus_poisoned(w_core_status.dma.poisoned),
        .axi_aw_valid(memory_aw_valid), .axi_aw(memory_aw), .axi_ar(memory_ar),
        .axi_w_valid(memory_w_valid), .axi_w(memory_w), .axi_b_ready(memory_b_ready),
        .axi_ar_valid(memory_ar_valid), .axi_r_ready(memory_r_ready),
        .fetch_req_valid(w_fetch_req_valid), .fetch_req(w_fetch_req),
        .data_req_valid(w_data_req_valid), .data_req(w_data_req), .data_resp_ready(w_data_resp_ready),
        .matrix_req_valid(w_matrix_req_valid), .matrix_req(w_matrix_req),
        .matrix_resp_ready(w_matrix_resp_ready), .axi_aw_ready(memory_aw_ready),
        .axi_w_ready(memory_w_ready), .axi_b_valid(memory_b_valid), .axi_b(memory_b),
        .axi_ar_ready(memory_ar_ready), .axi_r_valid(memory_r_valid), .axi_r(memory_r),
        .fetch_req_ready(w_fetch_req_ready), .fetch_resp_valid(w_fetch_resp_valid),
        .fetch_resp(w_fetch_resp), .data_req_ready(w_data_req_ready),
        .data_resp_valid(w_data_resp_valid), .data_resp(w_data_resp),
        .matrix_req_ready(w_matrix_req_ready), .matrix_resp_valid(w_matrix_resp_valid),
        .matrix_resp(w_matrix_resp), .host_idle(w_host_idle)
    );
    scalar_core i_scalar_core (
        .clk(clk), .reset(reset), .start(f_start_pending), .ack(w_ack),
        .fetch_resp_valid(w_fetch_resp_valid), .fetch_resp(w_fetch_resp),
        .data_req_ready(w_data_req_ready), .data_resp_valid(w_data_resp_valid), .data_resp(w_data_resp),
        .matrix_req_ready(w_matrix_req_ready), .matrix_resp_valid(w_matrix_resp_valid),
        .matrix_resp(w_matrix_resp), .external_ar_ready(external_ar_ready),
        .external_aw_ready(external_aw_ready), .external_w_ready(external_w_ready),
        .external_r_valid(external_r_valid), .external_r(external_r),
        .external_b_valid(external_b_valid), .external_b(external_b),
        .fetch_req_valid(w_fetch_req_valid), .fetch_req(w_fetch_req),
        .data_req_valid(w_data_req_valid), .data_req(w_data_req), .data_resp_ready(w_data_resp_ready),
        .matrix_req_valid(w_matrix_req_valid), .matrix_req(w_matrix_req),
        .matrix_resp_ready(w_matrix_resp_ready), .external_ar_valid(external_ar_valid),
        .external_ar(external_ar), .external_aw_valid(external_aw_valid), .external_aw(external_aw),
        .external_w_valid(external_w_valid), .external_w(external_w),
        .external_r_ready(external_r_ready), .external_b_ready(external_b_ready),
        .status(w_core_status), .debug_regs(debug_regs), .debug_v_regs(debug_v_regs),
        .retire_valid(retire_valid), .retire(retire),
        .writeback_valid(w_unused_writeback_valid), .writeback(w_unused_writeback),
        .alu_issued_valid(w_unused_alu_valid), .alu_issued(w_unused_alu),
        .agu_issued_valid(w_unused_agu_valid), .agu_issued(w_unused_agu)
    );
endmodule
`default_nettype wire
