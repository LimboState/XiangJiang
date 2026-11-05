// In-order ROB with independent scalar and vector result pools.
// Independent per-entry/status registers; no single encoded FSM is forced.
`default_nettype none
module rob #(
    parameter int G_ROB_ENTRIES = 8,
    parameter int G_X_RES = 6,
    parameter int G_V_RES = 4
) (
    input logic clk,
    input logic reset,
    input logic flush,
    input logic quiescent,
    input logic allocate_valid,
    input xj_pkg::micro_op_t allocate,
    input logic retire_ready,
    input logic wb_valid,
    // Sequence field is not needed once the owner tag matches.
    /* verilator lint_off UNUSEDSIGNAL */
    input xj_pkg::exe_result_t wb,
    /* verilator lint_on UNUSEDSIGNAL */
    input xj_pkg::rob_tag_t query [2],
    input xj_pkg::rob_tag_t vector_query [4],
    output logic allocate_ready,
    output xj_pkg::rob_tag_t alloc_tag,
    output logic [xj_pkg::C_SEQUENCE_WIDTH-1:0] alloc_seq,
    output logic head_valid,
    output xj_pkg::micro_op_t head,
    output logic retire_valid,
    output xj_pkg::commit_record_t retire,
    output logic wb_accepted,
    output xj_pkg::operand_t values [2],
    output xj_pkg::vector_operand_t vector_values [4],
    output logic empty
);
    localparam int C_HEAD_WIDTH = $clog2(G_ROB_ENTRIES);
    localparam int C_X_IDX_WIDTH = $clog2(G_X_RES);
    localparam int C_V_IDX_WIDTH = $clog2(G_V_RES);
    logic [G_ROB_ENTRIES-1:0] f_valid;
    logic [G_ROB_ENTRIES-1:0] f_done;
    xj_pkg::micro_op_t f_uops [G_ROB_ENTRIES];
    logic [G_ROB_ENTRIES-1:0] f_has_result;
    logic [C_X_IDX_WIDTH-1:0] f_result_index [G_ROB_ENTRIES];
    logic [G_ROB_ENTRIES-1:0] f_has_vector_result;
    logic [C_V_IDX_WIDTH-1:0] f_vector_result_index [G_ROB_ENTRIES];
    logic [G_ROB_ENTRIES-1:0] f_exception;
    logic [4:0] f_cause [G_ROB_ENTRIES];
    logic [31:0] f_detail [G_ROB_ENTRIES];
    logic [31:0] f_bad_addr [G_ROB_ENTRIES];
    logic [31:0] f_next_pc [G_ROB_ENTRIES];
    logic [G_X_RES-1:0] f_result_used;
    logic [31:0] f_result_data [G_X_RES];
    logic [G_V_RES-1:0] f_vector_result_used;
    logic [xj_pkg::C_VECTOR_WIDTH-1:0] f_vector_result_data [G_V_RES];
    logic [C_HEAD_WIDTH-1:0] f_head_index;
    logic [C_HEAD_WIDTH-1:0] f_tail_index;
    logic [C_HEAD_WIDTH:0] f_count;
    logic [xj_pkg::C_GENERATION_WIDTH-1:0] f_next_generation [G_ROB_ENTRIES];
    logic [G_ROB_ENTRIES-1:0] f_generation_exhausted;
    logic [xj_pkg::C_SEQUENCE_WIDTH-1:0] f_next_sequence;
    logic f_sequence_exhausted;
    logic d_needs_result;
    logic d_needs_vector_result;
    logic d_free_result;
    logic d_free_vector_result;
    logic [C_X_IDX_WIDTH-1:0] d_free_result_index;
    logic [C_V_IDX_WIDTH-1:0] d_free_vector_result_index;
    logic d_must_drain;
    logic d_retire_fire;
    logic d_allocate_fire;
    logic d_wb_slot_valid;
    logic [C_HEAD_WIDTH-1:0] d_wb_slot;
    logic d_wb_slot_match;
    logic d_wb_accepted;
    logic d_readable [2];
    logic d_readable_vector [4];

    function automatic logic [C_HEAD_WIDTH-1:0] increment(input logic [C_HEAD_WIDTH-1:0] v_index);
        return (v_index == C_HEAD_WIDTH'(G_ROB_ENTRIES - 1)) ? '0 : v_index + 1'b1;
    endfunction

    function automatic logic [C_X_IDX_WIDTH-1:0] first_free(input logic [G_X_RES-1:0] v_used);
        logic [C_X_IDX_WIDTH-1:0] v_index;
        v_index = '0;
        for (int v_slot = G_X_RES-1; v_slot >= 0; v_slot--)
            if (!v_used[v_slot]) v_index = C_X_IDX_WIDTH'(v_slot);
        return v_index;
    endfunction
    function automatic logic [C_V_IDX_WIDTH-1:0] first_free_vector(input logic [G_V_RES-1:0] v_used);
        logic [C_V_IDX_WIDTH-1:0] v_index;
        v_index = '0;
        for (int v_slot = G_V_RES-1; v_slot >= 0; v_slot--)
            if (!v_used[v_slot]) v_index = C_V_IDX_WIDTH'(v_slot);
        return v_index;
    endfunction

    always_comb begin
        d_needs_result = allocate.writes_rd && (allocate.rd != 5'd0) && !allocate.exception;
        d_needs_vector_result = allocate.writes_v && !allocate.exception;
        d_free_result = |(~f_result_used);
        d_free_vector_result = |(~f_vector_result_used);
        d_free_result_index = first_free(f_result_used);
        d_free_vector_result_index = first_free_vector(f_vector_result_used);
        d_must_drain = f_sequence_exhausted || f_generation_exhausted[f_tail_index];
    end
    always_comb begin
        empty = (f_count == '0);
        alloc_tag.slot = f_tail_index;
        alloc_tag.generation = f_next_generation[f_tail_index];
        alloc_seq = f_next_sequence;
    end
    always_comb begin
        allocate_ready = !flush && !d_must_drain
            && (f_count != (C_HEAD_WIDTH+1)'(G_ROB_ENTRIES))
            && (!d_needs_result || d_free_result)
            && (!d_needs_vector_result || d_free_vector_result);
    end
    always_comb begin
        head_valid = !empty && !flush;
        head = f_uops[f_head_index];
        retire_valid = head_valid && f_done[f_head_index];
        retire.uop = f_uops[f_head_index];
        retire.data = f_exception[f_head_index] ? '0
            : f_has_vector_result[f_head_index] ? f_vector_result_data[f_vector_result_index[f_head_index]]
            : f_has_result[f_head_index] ? {224'd0, f_result_data[f_result_index[f_head_index]]} : '0;
        retire.exception = f_exception[f_head_index];
        retire.cause = f_cause[f_head_index];
        retire.detail = f_detail[f_head_index];
        retire.bad_addr = f_bad_addr[f_head_index];
        retire.next_pc = f_next_pc[f_head_index];
    end
    always_comb begin
        d_wb_slot = wb.tag.slot;
        d_wb_slot_valid = f_valid[d_wb_slot] && !f_done[d_wb_slot];
        d_wb_slot_match = (f_uops[d_wb_slot].tag == wb.tag);
        d_wb_accepted = wb_valid && !flush && d_wb_slot_valid && d_wb_slot_match;
        wb_accepted = d_wb_accepted;
    end
    always_comb begin
        d_retire_fire = retire_valid && retire_ready;
        d_allocate_fire = allocate_valid && allocate_ready;
    end
    always_comb begin
        for (int v_p = 0; v_p < 2; v_p++) begin
            d_readable[v_p] = !flush && f_valid[query[v_p].slot]
                && f_done[query[v_p].slot] && f_has_result[query[v_p].slot]
                && !f_exception[query[v_p].slot]
                && (f_uops[query[v_p].slot].tag == query[v_p]);
            values[v_p].tag = query[v_p];
            values[v_p].ready = d_readable[v_p];
            values[v_p].value = d_readable[v_p]
                ? f_result_data[f_result_index[query[v_p].slot]] : '0;
        end
        for (int v_p = 0; v_p < 4; v_p++) begin
            d_readable_vector[v_p] = !flush && f_valid[vector_query[v_p].slot]
                && f_done[vector_query[v_p].slot]
                && f_has_vector_result[vector_query[v_p].slot]
                && !f_exception[vector_query[v_p].slot]
                && (f_uops[vector_query[v_p].slot].tag == vector_query[v_p]);
            vector_values[v_p].tag = vector_query[v_p];
            vector_values[v_p].ready = d_readable_vector[v_p];
            vector_values[v_p].value = d_readable_vector[v_p]
                ? f_vector_result_data[f_vector_result_index[vector_query[v_p].slot]] : '0;
        end
    end

    always_ff @(posedge clk) begin
`ifndef SYNTHESIS
        if (!reset && flush) assert (quiescent)
            else $error("ROB flush requires drained completion sources");
`endif
        if (reset) begin
            f_valid <= '0;
            f_done <= '0;
            f_result_used <= '0;
            f_vector_result_used <= '0;
            f_head_index <= '0;
            f_tail_index <= '0;
            f_count <= '0;
            f_next_generation <= '{default: '0};
            f_generation_exhausted <= '0;
            f_next_sequence <= '0;
            f_sequence_exhausted <= 1'b0;
        end else begin
            if (d_wb_accepted) begin
                f_done[d_wb_slot] <= 1'b1;
                f_exception[d_wb_slot] <= wb.exception;
                f_cause[d_wb_slot] <= wb.cause;
                f_detail[d_wb_slot] <= wb.detail;
                f_bad_addr[d_wb_slot] <= wb.bad_addr;
                f_next_pc[d_wb_slot] <= wb.next_pc;
                if (f_has_result[d_wb_slot] && !wb.exception)
                    f_result_data[f_result_index[d_wb_slot]] <= wb.data[31:0];
                if (f_has_vector_result[d_wb_slot] && !wb.exception)
                    f_vector_result_data[f_vector_result_index[d_wb_slot]] <= wb.data;
            end
            if (d_retire_fire) begin
                f_valid[f_head_index] <= 1'b0;
                f_done[f_head_index] <= 1'b0;
                f_head_index <= increment(f_head_index);
                if (f_has_result[f_head_index]) f_result_used[f_result_index[f_head_index]] <= 1'b0;
                if (f_has_vector_result[f_head_index])
                    f_vector_result_used[f_vector_result_index[f_head_index]] <= 1'b0;
            end
            if (d_allocate_fire) begin
                f_valid[f_tail_index] <= 1'b1;
                f_done[f_tail_index] <= allocate.exception;
                f_uops[f_tail_index] <= allocate;
                f_has_result[f_tail_index] <= d_needs_result;
                f_result_index[f_tail_index] <= d_free_result_index;
                f_has_vector_result[f_tail_index] <= d_needs_vector_result;
                f_vector_result_index[f_tail_index] <= d_free_vector_result_index;
                f_exception[f_tail_index] <= allocate.exception;
                f_cause[f_tail_index] <= allocate.cause;
                f_detail[f_tail_index] <= 32'd0;
                f_bad_addr[f_tail_index] <= allocate.bad_addr;
                f_next_pc[f_tail_index] <= allocate.pc + 32'd4;
                f_tail_index <= increment(f_tail_index);
                if (d_needs_result) f_result_used[d_free_result_index] <= 1'b1;
                if (d_needs_vector_result) f_vector_result_used[d_free_vector_result_index] <= 1'b1;
                if (f_next_generation[f_tail_index] == {xj_pkg::C_GENERATION_WIDTH{1'b1}})
                    f_generation_exhausted[f_tail_index] <= 1'b1;
                else f_next_generation[f_tail_index] <= f_next_generation[f_tail_index] + 1'b1;
                if (f_next_sequence == {xj_pkg::C_SEQUENCE_WIDTH{1'b1}})
                    f_sequence_exhausted <= 1'b1;
                else f_next_sequence <= f_next_sequence + 1'b1;
            end
            if (d_allocate_fire != d_retire_fire)
                f_count <= d_allocate_fire ? f_count + 1'b1 : f_count - 1'b1;
            if ((d_must_drain && empty && quiescent) || flush) begin
                f_next_generation <= '{default: '0};
                f_generation_exhausted <= '0;
                f_next_sequence <= '0;
                f_sequence_exhausted <= 1'b0;
                f_head_index <= '0;
                f_tail_index <= '0;
            end
            if (flush) begin
                f_valid <= '0;
                f_done <= '0;
                f_result_used <= '0;
                f_vector_result_used <= '0;
                f_count <= '0;
            end
        end
    end
endmodule
`default_nettype wire
