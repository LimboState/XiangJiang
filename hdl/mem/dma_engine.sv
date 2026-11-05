`default_nettype none
module dma_engine #(
    parameter int unsigned G_DTCM_BYTES = 1048576
) (
    input logic clk,
    input logic reset,
    input logic ack,
    input logic command_valid,
    // DMA consumes only scalar operands and owner identity from the shared issue bundle.
    /* verilator lint_off UNUSEDSIGNAL */
    input xj_pkg::issue_entry_t command,
    /* verilator lint_on UNUSEDSIGNAL */
    input logic result_ready,
    input logic commit_valid,
    input xj_pkg::rob_tag_t commit,
    input logic axi_ar_ready,
    input logic axi_aw_ready,
    input logic axi_w_ready,
    input logic axi_r_valid,
    input xj_pkg::axi_read_data_t axi_r,
    input logic axi_b_valid,
    input xj_pkg::axi_write_response_t axi_b,
    input logic local_req_ready,
    input logic local_resp_valid,
    input xj_pkg::word_resp_t local_resp,
    output logic command_ready,
    output logic result_valid,
    output xj_pkg::exe_result_t result,
    output logic axi_ar_valid,
    output xj_pkg::axi_address_t axi_ar,
    output logic axi_aw_valid,
    output xj_pkg::axi_address_t axi_aw,
    output logic axi_w_valid,
    output xj_pkg::axi_write_data_t axi_w,
    output logic axi_r_ready,
    output logic axi_b_ready,
    output logic local_req_valid,
    output xj_pkg::word_req_t local_req,
    output logic local_resp_ready,
    output xj_pkg::dma_status_t status,
    output logic busy,
    output xj_pkg::exe_result_t fault
);
    typedef enum logic [3:0] {
        ST_IDLE = 4'd0, ST_CHECK = 4'd1, ST_PREPARE = 4'd2,
        ST_AR = 4'd3, ST_RECEIVE = 4'd4, ST_LOCAL_WRITE = 4'd5,
        ST_LOCAL_WRITE_WAIT = 4'd6, ST_LOCAL_READ = 4'd7,
        ST_LOCAL_READ_WAIT = 4'd8, ST_AW = 4'd9, ST_SEND = 4'd10,
        ST_RESPONSE = 4'd11, ST_ADVANCE = 4'd12,
        ST_COMPLETE = 4'd13, ST_PUBLISH = 4'd14
    } state_t;
    state_t f_state;
    state_t d_state;
    xj_pkg::opcode_t f_op;
    xj_pkg::rob_tag_t f_owner_tag;
    logic [31:0] f_source_a;
    logic [31:0] f_source_b;
    xj_pkg::exe_result_t f_result;
    logic [31:0] f_row_bytes;
    logic [31:0] f_rows;
    logic [31:0] f_ext_stride;
    logic [31:0] f_local_stride;
    logic f_size_valid;
    logic f_stride_valid;
    logic [31:0] f_bytes_done;
    logic [31:0] f_rows_done;
    logic f_error;
    logic f_poisoned;
    logic f_direction;
    logic [31:0] f_ext_row;
    logic [31:0] f_local_row;
    logic [31:0] f_offset;
    logic [31:0] f_row;
    logic [31:0] f_ext;
    logic [31:0] f_local;
    logic [6:0] f_beats;
    logic [5:0] f_beat;
    logic [31:0] f_buffer [64];
    logic f_burst_error;
    logic d_is_config;
    logic d_config_bad_a;
    logic d_config_bad_b;
    logic d_misaligned;
    logic d_missing;
    logic d_out_ext;
    logic d_out_local;
    logic d_invalid;
    logic [31:0] d_last_row;
    logic [63:0] d_ext_span;
    logic [63:0] d_local_span;
    logic [64:0] d_ext_last;
    logic [64:0] d_local_last;
    logic [31:0] d_next_ext;
    logic [31:0] d_remaining;
    logic [12:0] d_page_remaining;
    logic [31:0] d_capped;
    logic [6:0] d_beats;
    logic [31:0] d_burst_bytes;
    logic d_last_beat;
    logic d_bad_r;
    logic d_bad_b;
    logic d_fail;
    logic [4:0] d_fail_cause;
    logic [31:0] d_fail_addr;
    logic [7:0] d_fail_reason;
    logic d_command_fire;
    logic d_result_fire;
    logic d_ar_fire;
    logic d_aw_fire;
    logic d_r_fire;
    logic d_w_fire;
    logic d_b_fire;
    logic d_local_req_fire;
    logic d_local_resp_fire;
    logic d_publish;

    always_comb begin
        d_is_config = f_op == xj_pkg::OP_DSETSIZE || f_op == xj_pkg::OP_DSETSTRIDE;
        d_config_bad_a = (|f_source_a[1:0]) ||
            (f_op == xj_pkg::OP_DSETSIZE && f_source_a == 32'd0);
        d_config_bad_b = f_op == xj_pkg::OP_DSETSIZE
            ? f_source_b == 32'd0 : (|f_source_b[1:0]);
        d_misaligned = (|f_source_a[1:0]) || (|f_source_b[1:0]);
        d_missing = !f_size_valid || !f_stride_valid;
        d_last_row = f_rows - 32'd1;
        d_ext_span = 64'(d_last_row) * 64'(f_ext_stride);
        d_local_span = 64'(d_last_row) * 64'(f_local_stride);
        d_ext_last = 65'(f_source_a) + 65'(d_ext_span) + 65'(f_row_bytes) - 65'd1;
        d_local_last = 65'(f_source_b) + 65'(d_local_span) + 65'(f_row_bytes) - 65'd1;
        d_out_ext = d_ext_last >= 65'h100000000;
        d_out_local = d_local_last >= 65'(G_DTCM_BYTES);
        d_invalid = d_is_config ? d_config_bad_a || d_config_bad_b
            : d_misaligned || d_missing || d_out_ext || d_out_local;
        d_next_ext = f_ext_row + f_offset;
        d_remaining = f_row_bytes - f_offset;
        d_page_remaining = 13'd4096 - {1'b0, d_next_ext[11:0]};
        d_capped = d_remaining > 32'd256 ? 32'd256 : d_remaining;
        d_beats = 7'((d_capped > 32'(d_page_remaining)
            ? 32'(d_page_remaining) : d_capped) >> 2);
        d_burst_bytes = {23'd0, f_beats, 2'd0};
        d_last_beat = {1'b0, f_beat} == f_beats - 7'd1;
        command_ready = f_state == ST_IDLE && !f_poisoned;
        result_valid = f_state == ST_COMPLETE && !f_poisoned;
        result = f_result;
        fault = f_result;
        busy = (f_state != ST_IDLE && f_state != ST_PUBLISH) || f_poisoned;
        status = '{row_bytes:f_row_bytes, rows:f_rows, external_stride:f_ext_stride,
            local_stride:f_local_stride, bytes_done:f_bytes_done, rows_done:f_rows_done,
            active:((f_state != ST_IDLE && !d_is_config) || f_poisoned),
            outbound:f_direction, error:f_error, poisoned:f_poisoned};
        axi_ar_valid = f_state == ST_AR && !f_poisoned;
        axi_aw_valid = f_state == ST_AW && !f_poisoned;
        axi_ar = '0;
        axi_ar.addr = f_ext;
        axi_ar.len = {1'b0, (f_beats - 7'd1)};
        axi_ar.size = 3'd2;
        axi_ar.burst = 2'd1;
        axi_aw = axi_ar;
        axi_r_ready = f_state == ST_RECEIVE && !f_poisoned;
        axi_w_valid = f_state == ST_SEND;
        axi_w = '{data:f_buffer[f_beat], strb:4'hf, last:d_last_beat};
        axi_b_ready = f_state == ST_RESPONSE && !f_poisoned;
        local_req_valid = (f_state == ST_LOCAL_READ || f_state == ST_LOCAL_WRITE)
            && !f_poisoned;
        local_req.addr = f_local + {24'd0, f_beat, 2'd0};
        local_req.write = f_state == ST_LOCAL_WRITE;
        local_req.data = f_buffer[f_beat];
        local_req.mask = 4'hf;
        local_resp_ready = f_state == ST_LOCAL_READ_WAIT || f_state == ST_LOCAL_WRITE_WAIT;
        d_command_fire = command_valid && command_ready;
        d_result_fire = result_valid && result_ready;
        d_ar_fire = axi_ar_valid && axi_ar_ready;
        d_aw_fire = axi_aw_valid && axi_aw_ready;
        d_r_fire = axi_r_valid && axi_r_ready;
        d_w_fire = axi_w_valid && axi_w_ready;
        d_b_fire = axi_b_valid && axi_b_ready;
        d_local_req_fire = local_req_valid && local_req_ready;
        d_local_resp_fire = local_resp_valid && local_resp_ready;
        d_publish = f_state == ST_PUBLISH && commit_valid && commit == f_owner_tag;
        d_bad_r = axi_r_valid && ((axi_r.id != '0) ||
            !(f_state == ST_RECEIVE || (f_state == ST_AR && d_ar_fire)) ||
            (f_state == ST_RECEIVE && axi_r.last != d_last_beat));
        d_bad_b = axi_b_valid && ((axi_b.id != '0) ||
            !(f_state == ST_RESPONSE || (f_state == ST_SEND && d_w_fire && axi_w.last)));
        d_fail = 1'b0;
        d_fail_cause = xj_pkg::C_FAULT_NONE;
        d_fail_addr = 32'd0;
        d_fail_reason = 8'd0;
        if (f_state == ST_CHECK && d_invalid) begin
            d_fail = 1'b1;
            d_fail_cause = xj_pkg::C_FAULT_DMA_CONFIG;
            d_fail_addr = d_is_config ? (d_config_bad_a ? f_source_a : f_source_b)
                : d_misaligned ? ((|f_source_a[1:0]) ? f_source_a : f_source_b)
                : d_missing ? 32'd0 : d_out_ext ? f_source_a : f_source_b;
            d_fail_reason = d_is_config ? 8'd1 : d_misaligned ? 8'd2
                : d_missing ? 8'd3 : 8'd4;
        end
        if (d_r_fire && axi_r.resp != 2'd0) begin
            d_fail = 1'b1;
            d_fail_cause = xj_pkg::C_FAULT_DMA_READ;
            d_fail_addr = f_ext;
            d_fail_reason = {6'd0, axi_r.resp};
        end
        if (d_local_resp_fire && local_resp.error) begin
            d_fail = 1'b1;
            d_fail_cause = xj_pkg::C_FAULT_DMA_CONFIG;
            d_fail_addr = local_req.addr;
            d_fail_reason = 8'd5;
        end
        if (d_b_fire && axi_b.resp != 2'd0) begin
            d_fail = 1'b1;
            d_fail_cause = xj_pkg::C_FAULT_DMA_WRITE;
            d_fail_addr = f_ext;
            d_fail_reason = {6'd0, axi_b.resp};
        end
    end

    always_comb begin
        d_state = f_state;
        unique case (f_state)
            ST_IDLE: if (d_command_fire) d_state = ST_CHECK;
            ST_CHECK: d_state = d_is_config || d_invalid ? ST_COMPLETE : ST_PREPARE;
            ST_PREPARE: d_state = f_direction ? ST_LOCAL_READ : ST_AR;
            ST_AR: if (d_ar_fire) d_state = ST_RECEIVE;
            ST_RECEIVE: if (d_r_fire && d_last_beat)
                d_state = f_burst_error || axi_r.resp != 2'd0 ? ST_COMPLETE : ST_LOCAL_WRITE;
            ST_LOCAL_WRITE: if (d_local_req_fire)
                d_state = f_direction ? ST_LOCAL_READ_WAIT : ST_LOCAL_WRITE_WAIT;
            ST_LOCAL_READ: if (d_local_req_fire)
                d_state = f_direction ? ST_LOCAL_READ_WAIT : ST_LOCAL_WRITE_WAIT;
            ST_LOCAL_WRITE_WAIT, ST_LOCAL_READ_WAIT: if (d_local_resp_fire) begin
                if (local_resp.error) d_state = ST_COMPLETE;
                else if (d_last_beat) d_state = f_direction ? ST_AW : ST_ADVANCE;
                else d_state = f_direction ? ST_LOCAL_READ : ST_LOCAL_WRITE;
            end
            ST_AW: if (d_aw_fire) d_state = ST_SEND;
            ST_SEND: if (d_w_fire && d_last_beat) d_state = ST_RESPONSE;
            ST_RESPONSE: if (d_b_fire)
                d_state = axi_b.resp != 2'd0 ? ST_COMPLETE : ST_ADVANCE;
            ST_ADVANCE: d_state = f_offset + d_burst_bytes == f_row_bytes
                && f_row == f_rows - 32'd1 ? ST_COMPLETE : ST_PREPARE;
            ST_COMPLETE: if (d_result_fire)
                d_state = d_is_config && !f_result.exception ? ST_PUBLISH : ST_IDLE;
            ST_PUBLISH: if (d_publish) d_state = ST_IDLE;
        endcase
    end

    always_ff @(posedge clk) begin
        if (reset) f_state <= ST_IDLE;
        else f_state <= d_state;
    end

    always_ff @(posedge clk) begin
        if (reset) begin
            f_result <= '0;
            f_row_bytes <= '0;
            f_rows <= '0;
            f_ext_stride <= '0;
            f_local_stride <= '0;
            f_size_valid <= 1'b0;
            f_stride_valid <= 1'b0;
            f_bytes_done <= '0;
            f_rows_done <= '0;
            f_error <= 1'b0;
            f_poisoned <= 1'b0;
            f_direction <= 1'b0;
            f_burst_error <= 1'b0;
        end else begin
            if (ack && f_state == ST_IDLE && !f_poisoned) begin
                f_bytes_done <= '0;
                f_rows_done <= '0;
                f_error <= 1'b0;
                f_direction <= 1'b0;
                f_result <= '0;
            end
            if (d_command_fire) begin
                f_result <= '0;
                f_result.tag <= command.uop.tag;
                f_result.seq <= command.uop.seq;
                f_result.next_pc <= command.uop.pc + 32'd4;
                f_error <= 1'b0;
                if (command.uop.op == xj_pkg::OP_MVIN || command.uop.op == xj_pkg::OP_MVOUT) begin
                    f_bytes_done <= '0;
                    f_rows_done <= '0;
                    f_direction <= command.uop.op == xj_pkg::OP_MVOUT;
                end
            end
            if (f_state == ST_PREPARE) f_burst_error <= 1'b0;
            if (d_r_fire && axi_r.resp != 2'd0) f_burst_error <= 1'b1;
            if (d_local_resp_fire && !local_resp.error && !f_direction)
                f_bytes_done <= f_bytes_done + 32'd4;
            if (d_b_fire && axi_b.resp == 2'd0) f_bytes_done <= f_bytes_done + d_burst_bytes;
            if (f_state == ST_ADVANCE && f_offset + d_burst_bytes == f_row_bytes)
                f_rows_done <= f_rows_done + 32'd1;
            if (d_publish) begin
                if (f_op == xj_pkg::OP_DSETSIZE) begin
                    f_row_bytes <= f_source_a;
                    f_rows <= f_source_b;
                    f_size_valid <= 1'b1;
                end else begin
                    f_ext_stride <= f_source_a;
                    f_local_stride <= f_source_b;
                    f_stride_valid <= 1'b1;
                end
            end
            if (d_fail) begin
                if (!f_error) begin
                    f_result.exception <= 1'b1;
                    f_result.cause <= d_fail_cause;
                    f_result.bad_addr <= d_fail_addr;
                    f_result.detail <= {8'd3, d_fail_reason, f_row[15:0]};
                end
                f_error <= 1'b1;
            end
            if ((d_bad_r || d_bad_b) && !f_poisoned) begin
                f_poisoned <= 1'b1;
                f_error <= 1'b1;
                f_result.exception <= 1'b1;
                f_result.cause <= xj_pkg::C_FAULT_DMA_PROTOCOL;
                f_result.bad_addr <= f_state == ST_IDLE ? 32'd0 : f_ext;
                f_result.detail <= {8'd3, 8'd6, f_row[15:0]};
            end
        end
    end

    always_ff @(posedge clk) begin
        if (d_command_fire) begin
            f_op <= command.uop.op;
            f_owner_tag <= command.uop.tag;
            f_source_a <= command.src1.value;
            f_source_b <= command.src2.value;
            f_row <= '0;
            f_offset <= '0;
            f_ext_row <= command.src1.value;
            f_local_row <= command.src2.value;
        end
        if (f_state == ST_PREPARE) begin
            f_ext <= d_next_ext;
            f_local <= f_local_row + f_offset;
            f_beats <= d_beats;
            f_beat <= '0;
        end
        if (d_r_fire) begin
            f_buffer[f_beat] <= axi_r.data;
            f_beat <= d_last_beat ? 6'd0 : f_beat + 6'd1;
        end
        if (d_local_resp_fire && !local_resp.error) begin
            if (f_direction) f_buffer[f_beat] <= local_resp.data;
            f_beat <= d_last_beat ? 6'd0 : f_beat + 6'd1;
        end
        if (d_w_fire && !d_last_beat) f_beat <= f_beat + 6'd1;
        if (f_state == ST_ADVANCE) begin
            if (f_offset + d_burst_bytes == f_row_bytes) begin
                if (f_row != f_rows - 32'd1) begin
                    f_row <= f_row + 32'd1;
                    f_offset <= '0;
                    f_ext_row <= f_ext_row + f_ext_stride;
                    f_local_row <= f_local_row + f_local_stride;
                end
            end else f_offset <= f_offset + d_burst_bytes;
        end
    end
endmodule
`default_nettype wire
