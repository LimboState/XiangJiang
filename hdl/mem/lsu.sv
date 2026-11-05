`default_nettype none
module lsu #(
    parameter int unsigned G_MEMQ_ENTRIES = 4,
    parameter int unsigned G_DTCM_BYTES = 1048576
) (
    input logic clk,
    input logic reset,
    input logic stop,
    input logic flush,
    input logic allocate_valid,
    input xj_pkg::issue_entry_t allocate,
    input logic wakeup_valid,
    // Wakeup uses owner tag, data and exception, not completion diagnostic fields.
    /* verilator lint_off UNUSEDSIGNAL */
    input xj_pkg::exe_result_t wakeup,
    /* verilator lint_on UNUSEDSIGNAL */
    input logic head_valid,
    input xj_pkg::rob_tag_t head,
    input logic request_ready,
    input logic response_valid,
    input xj_pkg::word_resp_t response,
    input logic dma_task_ready,
    input logic dma_result_valid,
    input xj_pkg::exe_result_t dma_result,
    input logic matrix_task_ready,
    input logic matrix_done,
    input logic matrix_state_fault,
    input logic matrix_access_fault,
    input logic result_ready,
    output logic allocate_ready,
    output logic request_valid,
    output xj_pkg::word_req_t request,
    output logic response_ready,
    output logic dma_task_valid,
    output xj_pkg::issue_entry_t dma_task,
    output logic dma_result_ready,
    output logic matrix_task_valid,
    output xj_pkg::matrix_task_t matrix_task,
    output logic result_valid,
    output xj_pkg::exe_result_t result,
    output logic empty,
    output logic quiescent,
    output logic address_issued_valid,
    output xj_pkg::rob_tag_t address_issued
);
    localparam int C_INDEX_WIDTH = G_MEMQ_ENTRIES > 1 ? $clog2(G_MEMQ_ENTRIES) : 1;
    localparam int C_COUNT_WIDTH = $clog2(G_MEMQ_ENTRIES+1);
    typedef enum logic [2:0] {
        ST_IDLE = 3'd0, ST_SCALAR_WAIT = 3'd1, ST_VECTOR_ISSUE = 3'd2,
        ST_VECTOR_WAIT = 3'd3, ST_MATRIX_WAIT = 3'd4,
        ST_DMA_WAIT = 3'd5, ST_RESULT = 3'd6
    } state_t;
    state_t f_state;
    state_t d_state;
    xj_pkg::issue_entry_t f_entries [G_MEMQ_ENTRIES];
    logic [G_MEMQ_ENTRIES-1:0] f_valid;
    logic [G_MEMQ_ENTRIES-1:0] f_address_ready;
    logic [31:0] f_address [G_MEMQ_ENTRIES];
    logic [4:0] f_address_fault [G_MEMQ_ENTRIES];
    logic [C_INDEX_WIDTH-1:0] f_head;
    logic [C_INDEX_WIDTH-1:0] f_tail;
    logic [C_COUNT_WIDTH-1:0] f_count;
    logic [7:0] f_pending_lanes;
    logic [255:0] f_load_data;
    logic f_blocked;
    xj_pkg::exe_result_t f_result;
    xj_pkg::issue_entry_t d_oldest;
    xj_pkg::issue_entry_t d_awakened [G_MEMQ_ENTRIES];
    xj_pkg::issue_entry_t d_allocation;
    logic d_candidate [G_MEMQ_ENTRIES+1];
    logic [C_INDEX_WIDTH-1:0] d_selected [G_MEMQ_ENTRIES+1];
    logic [C_INDEX_WIDTH-1:0] d_agu_index;
    logic d_agu_valid;
    logic d_agu_store;
    logic d_agu_vector;
    logic d_agu_matrix;
    logic d_agu_matrix_mem;
    logic d_agu_dma;
    logic [31:0] d_effective_address;
    logic [32:0] d_matrix_tile_bytes;
    logic [4:0] d_scalar_fault;
    logic [4:0] d_vector_fault;
    logic [4:0] d_matrix_fault;
    logic [4:0] d_agu_fault;
    logic d_vector;
    logic d_matrix;
    logic d_store;
    logic d_dma;
    logic d_authorized;
    logic d_data_ready;
    logic d_can_start;
    logic [2:0] d_lane;
    logic [7:0] d_remaining_lanes;
    logic [255:0] d_updated_data;
    logic [31:0] d_request_address;
    logic [4:0] d_response_fault;
    logic d_request_fire;
    logic d_response_fire;
    logic d_dma_complete;
    logic d_matrix_complete;
    logic d_make_result;
    logic d_block;
    xj_pkg::exe_result_t d_result;
    logic d_dequeue;
    logic d_allocate_fire;

    function automatic logic is_store(
        input xj_pkg::opcode_t v_op,
        input logic [5:0] v_vec_op,
        input xj_pkg::mat_op_t v_mat_op
    );
        return v_op == xj_pkg::OP_SW ||
            (v_op == xj_pkg::OP_VECTOR && v_vec_op == 6'd60) ||
            (v_op == xj_pkg::OP_MATRIX && v_mat_op == xj_pkg::MAT_STORE);
    endfunction

    function automatic logic [C_INDEX_WIDTH-1:0] increment(
        input logic [C_INDEX_WIDTH-1:0] v_index
    );
        return v_index == C_INDEX_WIDTH'(G_MEMQ_ENTRIES-1) ? '0
            : v_index + C_INDEX_WIDTH'(1);
    endfunction

    function automatic xj_pkg::issue_entry_t capture_wakeup(input xj_pkg::issue_entry_t v_entry);
        xj_pkg::issue_entry_t v_captured;
        v_captured = v_entry;
        if (wakeup_valid && !wakeup.exception) begin
            if (!v_entry.src1.ready && v_entry.src1.tag == wakeup.tag) begin
                v_captured.src1.ready = 1'b1;
                v_captured.src1.value = wakeup.data[31:0];
            end
            if (!v_entry.src2.ready && v_entry.src2.tag == wakeup.tag) begin
                v_captured.src2.ready = 1'b1;
                v_captured.src2.value = wakeup.data[31:0];
            end
            if (!v_entry.vold.ready && v_entry.vold.tag == wakeup.tag) begin
                v_captured.vold.ready = 1'b1;
                v_captured.vold.value = wakeup.data;
            end
            if (!v_entry.vmask.ready && v_entry.vmask.tag == wakeup.tag) begin
                v_captured.vmask.ready = 1'b1;
                v_captured.vmask.value = wakeup.data;
            end
        end
        return v_captured;
    endfunction

    always_comb begin
        d_candidate[0] = 1'b0;
        d_selected[0] = '0;
        for (int v_index = 0; v_index < G_MEMQ_ENTRIES; v_index++) begin
            logic v_ready;
            logic v_take;
            d_awakened[v_index] = capture_wakeup(f_entries[v_index]);
            v_ready = f_valid[v_index] && !f_address_ready[v_index]
                && f_entries[v_index].src1.ready
                && (f_entries[v_index].uop.op != xj_pkg::OP_VECTOR ||
                    !f_entries[v_index].uop.uses_v_mask || f_entries[v_index].vmask.ready);
            v_take = v_ready && (!d_candidate[v_index] ||
                f_entries[v_index].uop.seq < f_entries[d_selected[v_index]].uop.seq);
            d_candidate[v_index+1] = d_candidate[v_index] || v_ready;
            d_selected[v_index+1] = v_take ? C_INDEX_WIDTH'(v_index) : d_selected[v_index];
        end
        d_allocation = capture_wakeup(allocate);
        d_agu_index = d_selected[G_MEMQ_ENTRIES];
        d_agu_valid = d_candidate[G_MEMQ_ENTRIES] && !stop && !flush && !f_blocked;
        d_effective_address = f_entries[d_agu_index].src1.value + f_entries[d_agu_index].uop.imm;
        d_agu_vector = f_entries[d_agu_index].uop.op == xj_pkg::OP_VECTOR;
        d_agu_matrix = f_entries[d_agu_index].uop.op == xj_pkg::OP_MATRIX;
        d_agu_dma = f_entries[d_agu_index].uop.op == xj_pkg::OP_MVIN
            || f_entries[d_agu_index].uop.op == xj_pkg::OP_MVOUT;
        d_agu_matrix_mem = d_agu_matrix &&
            (f_entries[d_agu_index].uop.mat_op == xj_pkg::MAT_LOAD ||
                f_entries[d_agu_index].uop.mat_op == xj_pkg::MAT_STORE);
        d_agu_store = is_store(f_entries[d_agu_index].uop.op,
            f_entries[d_agu_index].uop.vec_op, f_entries[d_agu_index].uop.mat_op);
        d_scalar_fault = (|d_effective_address[1:0])
            ? (d_agu_store ? xj_pkg::C_FAULT_STORE_ALIGN : xj_pkg::C_FAULT_LOAD_ALIGN)
            : d_effective_address >= G_DTCM_BYTES
            ? (d_agu_store ? xj_pkg::C_FAULT_STORE_ACCESS : xj_pkg::C_FAULT_LOAD_ACCESS)
            : xj_pkg::C_FAULT_NONE;
        d_matrix_tile_bytes = f_entries[d_agu_index].uop.mat_unit == 2'd0 ? 33'd128
            : (f_entries[d_agu_index].uop.mat_op == xj_pkg::MAT_LOAD &&
                f_entries[d_agu_index].uop.mat_buffer != 2'd2) ? 33'd64 : 33'd256;
        d_matrix_fault = (|d_effective_address[5:0]) ? xj_pkg::C_FAULT_MATRIX_ALIGN
            : {1'b0, d_effective_address} + d_matrix_tile_bytes > 33'(G_DTCM_BYTES)
            ? xj_pkg::C_FAULT_MATRIX_ACCESS : xj_pkg::C_FAULT_NONE;
        d_vector_fault = (|d_effective_address[4:0])
            ? (d_agu_store ? xj_pkg::C_FAULT_VECTOR_STORE_ALIGN
                : xj_pkg::C_FAULT_VECTOR_LOAD_ALIGN)
            : {1'b0, d_effective_address} + 33'd32 > 33'(G_DTCM_BYTES)
            ? (d_agu_store ? xj_pkg::C_FAULT_VECTOR_STORE_ACCESS
                : xj_pkg::C_FAULT_VECTOR_LOAD_ACCESS) : xj_pkg::C_FAULT_NONE;
        d_agu_fault = d_agu_vector ? d_vector_fault : d_agu_matrix_mem ? d_matrix_fault
            : (d_agu_matrix || d_agu_dma) ? xj_pkg::C_FAULT_NONE : d_scalar_fault;
        address_issued_valid = d_agu_valid;
        address_issued = f_entries[d_agu_index].uop.tag;

        d_oldest = f_entries[f_head];
        d_vector = d_oldest.uop.op == xj_pkg::OP_VECTOR;
        d_matrix = d_oldest.uop.op == xj_pkg::OP_MATRIX;
        d_dma = d_oldest.uop.op == xj_pkg::OP_MVIN || d_oldest.uop.op == xj_pkg::OP_MVOUT;
        d_store = is_store(d_oldest.uop.op, d_oldest.uop.vec_op, d_oldest.uop.mat_op);
        d_authorized = (!d_store && !d_matrix && !d_dma)
            || (head_valid && head == d_oldest.uop.tag);
        d_data_ready = d_oldest.src2.ready &&
            (!d_vector || !d_oldest.uop.uses_v_old || d_oldest.vold.ready);
        d_can_start = f_count != '0 && f_address_ready[f_head] && d_data_ready
            && d_authorized && f_state == ST_IDLE && !f_blocked && !stop && !flush;
        d_lane = 3'd0;
        for (int v_index = 7; v_index >= 0; v_index--)
            if (f_pending_lanes[v_index]) d_lane = 3'(v_index);
        d_remaining_lanes = f_pending_lanes & ~(8'd1 << d_lane);
        d_updated_data = f_load_data;
        if (!d_store) d_updated_data[32*int'(d_lane)+:32] = response.data;
        d_request_address = f_address[f_head] + (d_vector ? {27'd0, d_lane, 2'd0} : 32'd0);
        allocate_ready = f_count < C_COUNT_WIDTH'(G_MEMQ_ENTRIES) && !stop && !flush && !f_blocked;
        result_valid = f_state == ST_RESULT;
        result = f_result;
        quiescent = f_state == ST_IDLE;
        empty = f_count == '0 && quiescent;
        dma_task_valid = d_can_start && d_dma;
        dma_task = d_oldest;
        dma_result_ready = f_state == ST_DMA_WAIT;
        matrix_task_valid = d_can_start && d_matrix && f_address_fault[f_head] == 5'd0;
        matrix_task = '{tag:d_oldest.uop.tag, op:d_oldest.uop.mat_op,
            unit:d_oldest.uop.mat_unit, buffer:d_oldest.uop.mat_buffer, addr:f_address[f_head]};
        request_valid = (d_can_start && !d_vector && !d_matrix && !d_dma
            && f_address_fault[f_head] == 5'd0) ||
            (f_state == ST_VECTOR_ISSUE && (|f_pending_lanes) && !f_blocked && !stop && !flush);
        request.addr = {d_request_address[31:2], 2'd0};
        request.data = d_vector ? d_oldest.vold.value[32*int'(d_lane)+:32] : d_oldest.src2.value;
        request.write = d_store;
        request.mask = 4'hf;
        response_ready = f_state == ST_SCALAR_WAIT || f_state == ST_VECTOR_WAIT;
        d_request_fire = request_valid && request_ready;
        d_response_fire = response_valid && response_ready;
        d_dma_complete = dma_result_valid && dma_result_ready && dma_result.tag == d_oldest.uop.tag;
        d_matrix_complete = matrix_done && f_state == ST_MATRIX_WAIT;
        d_response_fault = !response.error ? xj_pkg::C_FAULT_NONE : d_vector
            ? (d_store ? xj_pkg::C_FAULT_VECTOR_STORE_ACCESS : xj_pkg::C_FAULT_VECTOR_LOAD_ACCESS)
            : (d_store ? xj_pkg::C_FAULT_STORE_ACCESS : xj_pkg::C_FAULT_LOAD_ACCESS);
        d_result = '0;
        d_result.tag = d_oldest.uop.tag;
        d_result.seq = d_oldest.uop.seq;
        d_result.next_pc = d_oldest.uop.pc + 32'd4;
        d_make_result = 1'b0;
        d_block = 1'b0;
        if (d_can_start && f_address_fault[f_head] != 5'd0) begin
            d_make_result = 1'b1;
            d_result.cause = f_address_fault[f_head];
            d_result.bad_addr = f_address[f_head];
            d_block = 1'b1;
        end
        if (d_matrix_complete) begin
            d_make_result = 1'b1;
            d_result.cause = matrix_state_fault ? xj_pkg::C_FAULT_MATRIX_STATE
                : matrix_access_fault ? xj_pkg::C_FAULT_MATRIX_ACCESS : xj_pkg::C_FAULT_NONE;
            d_result.bad_addr = matrix_access_fault ? f_address[f_head] : 32'd0;
            d_block = matrix_state_fault || matrix_access_fault;
        end
        if (d_response_fire) begin
            d_block = response.error;
            d_result.cause = d_response_fault;
            d_result.bad_addr = d_vector ? f_address[f_head] : d_request_address;
            if (d_vector) begin
                d_make_result = response.error || d_remaining_lanes == 8'd0;
                d_result.data = d_store ? '0 : d_updated_data;
            end else begin
                d_make_result = 1'b1;
                d_result.data = {224'd0, response.data};
            end
        end
        d_result.exception = d_result.cause != 5'd0;
        d_result.detail = !d_result.exception ? 32'd0 : d_vector ? 32'h01000000
            : d_matrix ? 32'h02000000 | {22'd0, d_oldest.uop.mat_unit, 8'd0}
                | {30'd0, d_oldest.uop.mat_buffer} : 32'd0;
        if (d_dma_complete) begin
            d_make_result = 1'b1;
            d_result = dma_result;
            d_block = dma_result.exception;
        end
        d_dequeue = result_valid && result_ready && !result.exception;
        d_allocate_fire = allocate_valid && allocate_ready;
    end

    always_comb begin
        d_state = f_state;
        unique case (f_state)
            ST_IDLE: begin
                if (dma_task_valid && dma_task_ready) d_state = ST_DMA_WAIT;
                if (d_can_start && d_vector && f_address_fault[f_head] == 5'd0)
                    d_state = ST_VECTOR_ISSUE;
                if (matrix_task_valid && matrix_task_ready) d_state = ST_MATRIX_WAIT;
                if (d_request_fire) d_state = ST_SCALAR_WAIT;
            end
            ST_SCALAR_WAIT: if (d_response_fire) d_state = ST_RESULT;
            ST_VECTOR_ISSUE: begin
                if (d_request_fire) d_state = ST_VECTOR_WAIT;
                if (stop) d_state = ST_IDLE;
            end
            ST_VECTOR_WAIT: if (d_response_fire) d_state = ST_VECTOR_ISSUE;
            ST_MATRIX_WAIT: if (d_matrix_complete) d_state = ST_RESULT;
            ST_DMA_WAIT: if (d_dma_complete) d_state = ST_RESULT;
            ST_RESULT: if (result_ready) d_state = ST_IDLE;
        endcase
        if (d_make_result) d_state = ST_RESULT;
        if (flush) d_state = ST_IDLE;
    end

    always_ff @(posedge clk) begin
        if (reset) f_state <= ST_IDLE;
        else f_state <= d_state;
    end

    always_ff @(posedge clk) begin
`ifndef SYNTHESIS
        if (!reset && flush)
            assert (f_state != ST_SCALAR_WAIT && f_state != ST_VECTOR_WAIT)
                else $error("LSU flush must not cancel an accepted SRAM transaction");
`endif
        if (reset || flush) begin
            f_valid <= '0;
            f_address_ready <= '0;
            f_head <= '0;
            f_tail <= '0;
            f_count <= '0;
            f_blocked <= 1'b0;
        end else begin
            if (d_block) f_blocked <= 1'b1;
            if (d_agu_valid) f_address_ready[d_agu_index] <= 1'b1;
            if (d_dequeue) begin
                f_valid[f_head] <= 1'b0;
                f_address_ready[f_head] <= 1'b0;
                f_head <= increment(f_head);
            end
            if (d_allocate_fire) begin
                f_valid[f_tail] <= 1'b1;
                f_address_ready[f_tail] <= 1'b0;
                f_tail <= increment(f_tail);
            end
            if (d_allocate_fire != d_dequeue)
                f_count <= d_allocate_fire ? f_count + C_COUNT_WIDTH'(1)
                    : f_count - C_COUNT_WIDTH'(1);
        end
    end

    always_ff @(posedge clk) begin
        for (int v_index = 0; v_index < G_MEMQ_ENTRIES; v_index++) begin
            if (f_valid[v_index]) f_entries[v_index] <= d_awakened[v_index];
        end
        if (d_allocate_fire) f_entries[f_tail] <= d_allocation;
        if (d_agu_valid) begin
            f_address[d_agu_index] <= d_effective_address;
            f_address_fault[d_agu_index] <= d_agu_fault;
        end
        if (d_can_start && f_address_fault[f_head] == 5'd0 && d_vector) begin
            f_pending_lanes <= 8'hff;
            f_load_data <= '0;
        end
        if (d_response_fire && d_vector) begin
            f_load_data <= d_updated_data;
            f_pending_lanes <= d_remaining_lanes;
        end
        if (d_make_result) f_result <= d_result;
    end
endmodule
`default_nettype wire
