// Reservation station. Valid bits describe independent slots, not an encoded FSM.
`default_nettype none
module issue_unit #(
    parameter int G_ENTRIES = 4,
    parameter int G_HEAD_ONLY = 0,
    parameter int G_VECTOR_ENABLED = 0,
    parameter int G_ISSUE_ON_WAKEUP = 0
) (
    input logic clk,
    input logic reset,
    input logic flush,
    input logic head_valid,
    input xj_pkg::rob_tag_t head_tag,
    input logic dispatch_valid,
    input xj_pkg::issue_entry_t dispatch,
    input logic wakeup_valid,
    // Sequence/fault address fields are intentionally unused by tag-based wakeup.
    /* verilator lint_off UNUSEDSIGNAL */
    input xj_pkg::exe_result_t wakeup,
    /* verilator lint_on UNUSEDSIGNAL */
    input logic issue_ready,
    output logic dispatch_ready,
    output logic issue_valid,
    output xj_pkg::issue_entry_t issue,
    output logic empty
);
    localparam int C_INDEX_WIDTH = (G_ENTRIES > 1) ? $clog2(G_ENTRIES) : 1;
    logic [G_ENTRIES-1:0] f_valid;
    xj_pkg::issue_entry_t f_entries [G_ENTRIES];
    xj_pkg::issue_entry_t d_awakened [G_ENTRIES];
    xj_pkg::issue_entry_t d_candidates [G_ENTRIES];
    logic [G_ENTRIES-1:0] d_eligible;
    logic [C_INDEX_WIDTH-1:0] d_selected;
    logic [C_INDEX_WIDTH-1:0] d_free;
    logic [xj_pkg::C_SEQUENCE_WIDTH-1:0] d_oldest_seq;
    logic d_found;
    logic d_free_found;

    function automatic xj_pkg::operand_t capture_scalar(
        input xj_pkg::operand_t source,
        input logic valid,
        input xj_pkg::rob_tag_t result_tag,
        input logic exception,
        input logic [31:0] data
    );
        xj_pkg::operand_t v_operand;
        v_operand = source;
        if (!source.ready && valid && !exception && (source.tag == result_tag)) begin
            v_operand.ready = 1'b1;
            v_operand.value = data;
        end
        return v_operand;
    endfunction

    function automatic xj_pkg::vector_operand_t capture_vector(
        input xj_pkg::vector_operand_t source,
        input logic valid,
        input xj_pkg::rob_tag_t result_tag,
        input logic exception,
        input logic [xj_pkg::C_VECTOR_WIDTH-1:0] data
    );
        xj_pkg::vector_operand_t v_operand;
        v_operand = source;
        if (G_VECTOR_ENABLED == 0) begin
            v_operand = '0;
            v_operand.ready = 1'b1;
        end else if (!source.ready && valid && !exception && (source.tag == result_tag)) begin
            v_operand.ready = 1'b1;
            v_operand.value = data;
        end
        return v_operand;
    endfunction

    function automatic xj_pkg::issue_entry_t capture_entry(
        input xj_pkg::issue_entry_t entry,
        input logic valid,
        input xj_pkg::rob_tag_t result_tag,
        input logic exception,
        input logic [xj_pkg::C_VECTOR_WIDTH-1:0] data
    );
        xj_pkg::issue_entry_t v_entry;
        v_entry = entry;
        v_entry.src1 = capture_scalar(entry.src1, valid, result_tag, exception, data[31:0]);
        v_entry.src2 = capture_scalar(entry.src2, valid, result_tag, exception, data[31:0]);
        v_entry.vsrc1 = capture_vector(entry.vsrc1, valid, result_tag, exception, data);
        v_entry.vsrc2 = capture_vector(entry.vsrc2, valid, result_tag, exception, data);
        v_entry.vold = capture_vector(entry.vold, valid, result_tag, exception, data);
        v_entry.vmask = capture_vector(entry.vmask, valid, result_tag, exception, data);
        return v_entry;
    endfunction

    always_comb begin
        d_awakened = '{default: '0};
        d_candidates = '{default: '0};
        d_eligible = '0;
        d_selected = '0;
        d_free = '0;
        d_oldest_seq = '0;
        d_found = 1'b0;
        d_free_found = 1'b0;
        issue = '0;
        issue_valid = 1'b0;
        dispatch_ready = !flush && !(&f_valid);
        empty = !(|f_valid);
        for (int v_i = 0; v_i < G_ENTRIES; v_i++) begin
            d_awakened[v_i] = capture_entry(f_entries[v_i], wakeup_valid,
                wakeup.tag, wakeup.exception, wakeup.data);
            d_candidates[v_i] = (G_ISSUE_ON_WAKEUP != 0) ? d_awakened[v_i] : f_entries[v_i];
            d_eligible[v_i] = f_valid[v_i] && d_candidates[v_i].src1.ready
                && d_candidates[v_i].src2.ready
                && ((G_HEAD_ONLY == 0) || (head_valid && (head_tag == f_entries[v_i].uop.tag)))
                && ((G_VECTOR_ENABLED == 0)
                    || ((!f_entries[v_i].uop.uses_v1 || d_candidates[v_i].vsrc1.ready)
                    && (!f_entries[v_i].uop.uses_v2 || d_candidates[v_i].vsrc2.ready)
                    && (!f_entries[v_i].uop.uses_v_old || d_candidates[v_i].vold.ready)
                    && (!f_entries[v_i].uop.uses_v_mask || d_candidates[v_i].vmask.ready)));
            if (d_eligible[v_i] && (!d_found || (f_entries[v_i].uop.seq < d_oldest_seq))) begin
                d_selected = C_INDEX_WIDTH'(v_i);
                d_oldest_seq = f_entries[v_i].uop.seq;
                d_found = 1'b1;
            end
            if (!f_valid[v_i] && !d_free_found) begin
                d_free = C_INDEX_WIDTH'(v_i);
                d_free_found = 1'b1;
            end
        end
        issue = d_candidates[d_selected];
        issue_valid = !flush && d_found;
    end

    always_ff @(posedge clk) begin
        if (reset || flush) f_valid <= '0;
        else begin
            if (issue_valid && issue_ready) f_valid[d_selected] <= 1'b0;
            if (dispatch_valid && dispatch_ready) f_valid[d_free] <= 1'b1;
        end
    end
    // No reset on invalid payloads. Free-slot allocation never reuses a slot
    // being freed on the same edge, matching the original reservation station.
    always_ff @(posedge clk) begin
        for (int v_i = 0; v_i < G_ENTRIES; v_i++) begin
            if (f_valid[v_i]) begin
                f_entries[v_i].src1 <= d_awakened[v_i].src1;
                f_entries[v_i].src2 <= d_awakened[v_i].src2;
                if (G_VECTOR_ENABLED != 0) begin
                    f_entries[v_i].vsrc1 <= d_awakened[v_i].vsrc1;
                    f_entries[v_i].vsrc2 <= d_awakened[v_i].vsrc2;
                    f_entries[v_i].vold <= d_awakened[v_i].vold;
                    f_entries[v_i].vmask <= d_awakened[v_i].vmask;
                end
            end
        end
        if (dispatch_valid && dispatch_ready)
            f_entries[d_free] <= capture_entry(dispatch, wakeup_valid,
                wakeup.tag, wakeup.exception, wakeup.data);
    end
endmodule
`default_nettype wire
