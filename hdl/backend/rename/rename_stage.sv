// Captures source versions before installing the current destination mapping.
// No encoded FSM: ARF and RAT valid bits are independent registers.
`default_nettype none
module rename_stage (
    input logic clk,
    input logic reset,
    input logic dispatch,
    input logic flush,
    input xj_pkg::micro_op_t uop,
    // Wakeup/commit carry payload fields only needed on matching tags.
    /* verilator lint_off UNUSEDSIGNAL */
    input logic wakeup_valid,
    input xj_pkg::exe_result_t wakeup,
    input logic commit_valid,
    input xj_pkg::commit_record_t commit,
    /* verilator lint_on UNUSEDSIGNAL */
    input xj_pkg::operand_t lookup_values [2],
    input xj_pkg::vector_operand_t vector_lookup_values [4],
    output xj_pkg::issue_entry_t renamed,
    output xj_pkg::rob_tag_t lookup_tags [2],
    output xj_pkg::rob_tag_t vector_lookup_tags [4],
    output logic [31:0] debug_regs [32],
    output logic [xj_pkg::C_VECTOR_WIDTH-1:0] debug_v_regs [32]
);
    logic [31:0] f_registers [31];
    logic [30:0] f_mapped;
    xj_pkg::rob_tag_t f_mappings [31];
    logic [xj_pkg::C_VECTOR_WIDTH-1:0] f_vector_registers [32];
    logic [31:0] f_vector_mapped;
    xj_pkg::rob_tag_t f_vector_mappings [32];
    logic d_commit_writes;
    logic d_commit_writes_vector;
    logic d_store_source;
    logic [4:0] d_scalar_source_regs [2];
    logic [1:0] d_scalar_used;
    logic [4:0] d_vector_source_regs [4];
    logic [3:0] d_vector_used;

    always_comb begin
        d_commit_writes = commit_valid && !commit.exception && commit.uop.writes_rd
            && (commit.uop.rd != 5'd0);
        d_commit_writes_vector = commit_valid && !commit.exception && commit.uop.writes_v;
        d_store_source = (uop.op == xj_pkg::OP_VECTOR) && (uop.vec_op == 6'd60);
        d_scalar_source_regs[0] = uop.rs1;
        d_scalar_source_regs[1] = uop.rs2;
        d_scalar_used = {uop.uses_rs2, uop.uses_rs1};
        d_vector_source_regs[0] = uop.rs1;
        d_vector_source_regs[1] = uop.rs2;
        d_vector_source_regs[2] = d_store_source ? uop.rs2 : uop.rd;
        d_vector_source_regs[3] = uop.mask_reg;
        d_vector_used = {uop.uses_v_mask, uop.uses_v_old, uop.uses_v2, uop.uses_v1};
    end
    always_comb begin
        renamed = '0;
        renamed.uop = uop;
        renamed.src1.ready = 1'b1;
        renamed.src2.ready = 1'b1;
        renamed.vsrc1.ready = 1'b1;
        renamed.vsrc2.ready = 1'b1;
        renamed.vold.ready = 1'b1;
        renamed.vmask.ready = 1'b1;
        debug_regs[0] = 32'd0;
        for (int v_i = 1; v_i < 32; v_i++) debug_regs[v_i] = f_registers[v_i-1];
        debug_v_regs = f_vector_registers;
        for (int v_p = 0; v_p < 2; v_p++) begin
            logic [4:0] v_rs;
            logic [4:0] v_index;
            logic v_commit_same;
            v_rs = d_scalar_source_regs[v_p];
            v_index = v_rs - 5'd1;
            v_commit_same = d_commit_writes && (commit.uop.rd == v_rs);
            if (d_scalar_used[v_p] && (v_rs != 5'd0)) begin
                if (f_mapped[v_index]) begin
                    if (v_p == 0) begin
                        renamed.src1.ready = 1'b0; renamed.src1.tag = f_mappings[v_index];
                    end else begin
                        renamed.src2.ready = 1'b0; renamed.src2.tag = f_mappings[v_index];
                    end
                    if (lookup_values[v_p].ready && (lookup_values[v_p].tag == f_mappings[v_index])) begin
                        if (v_p == 0) begin
                            renamed.src1.ready = 1'b1; renamed.src1.value = lookup_values[v_p].value;
                        end else begin
                            renamed.src2.ready = 1'b1; renamed.src2.value = lookup_values[v_p].value;
                        end
                    end
                    if (wakeup_valid && !wakeup.exception && (wakeup.tag == f_mappings[v_index])) begin
                        if (v_p == 0) begin
                            renamed.src1.ready = 1'b1; renamed.src1.value = wakeup.data[31:0];
                        end else begin
                            renamed.src2.ready = 1'b1; renamed.src2.value = wakeup.data[31:0];
                        end
                    end
                    if (v_commit_same && (commit.uop.tag == f_mappings[v_index])) begin
                        if (v_p == 0) begin
                            renamed.src1.ready = 1'b1; renamed.src1.value = commit.data[31:0];
                        end else begin
                            renamed.src2.ready = 1'b1; renamed.src2.value = commit.data[31:0];
                        end
                    end
                end else begin
                    if (v_p == 0) renamed.src1.value = v_commit_same ? commit.data[31:0]
                        : f_registers[v_index];
                    else renamed.src2.value = v_commit_same ? commit.data[31:0]
                        : f_registers[v_index];
                end
            end
        end
        for (int v_p = 0; v_p < 4; v_p++) begin
            logic [4:0] v_rs;
            logic v_commit_same;
            v_rs = d_vector_source_regs[v_p];
            v_commit_same = d_commit_writes_vector && (commit.uop.rd == v_rs);
            if (d_vector_used[v_p]) begin
                if (f_vector_mapped[v_rs]) begin
                    case (v_p)
                        0: begin renamed.vsrc1.ready = 1'b0;
                            renamed.vsrc1.tag = f_vector_mappings[v_rs]; end
                        1: begin renamed.vsrc2.ready = 1'b0;
                            renamed.vsrc2.tag = f_vector_mappings[v_rs]; end
                        2: begin renamed.vold.ready = 1'b0;
                            renamed.vold.tag = f_vector_mappings[v_rs]; end
                        3: begin renamed.vmask.ready = 1'b0;
                            renamed.vmask.tag = f_vector_mappings[v_rs]; end
                        default: begin end
                    endcase
                    if (vector_lookup_values[v_p].ready
                        && (vector_lookup_values[v_p].tag == f_vector_mappings[v_rs])) begin
                        case (v_p)
                            0: begin renamed.vsrc1.ready = 1'b1;
                                renamed.vsrc1.value = vector_lookup_values[v_p].value; end
                            1: begin renamed.vsrc2.ready = 1'b1;
                                renamed.vsrc2.value = vector_lookup_values[v_p].value; end
                            2: begin renamed.vold.ready = 1'b1;
                                renamed.vold.value = vector_lookup_values[v_p].value; end
                            3: begin renamed.vmask.ready = 1'b1;
                                renamed.vmask.value = vector_lookup_values[v_p].value; end
                            default: begin end
                        endcase
                    end
                    if (wakeup_valid && !wakeup.exception && (wakeup.tag == f_vector_mappings[v_rs])) begin
                        case (v_p)
                            0: begin renamed.vsrc1.ready = 1'b1; renamed.vsrc1.value = wakeup.data; end
                            1: begin renamed.vsrc2.ready = 1'b1; renamed.vsrc2.value = wakeup.data; end
                            2: begin renamed.vold.ready = 1'b1; renamed.vold.value = wakeup.data; end
                            3: begin renamed.vmask.ready = 1'b1; renamed.vmask.value = wakeup.data; end
                            default: begin end
                        endcase
                    end
                    if (v_commit_same && (commit.uop.tag == f_vector_mappings[v_rs])) begin
                        case (v_p)
                            0: begin renamed.vsrc1.ready = 1'b1; renamed.vsrc1.value = commit.data; end
                            1: begin renamed.vsrc2.ready = 1'b1; renamed.vsrc2.value = commit.data; end
                            2: begin renamed.vold.ready = 1'b1; renamed.vold.value = commit.data; end
                            3: begin renamed.vmask.ready = 1'b1; renamed.vmask.value = commit.data; end
                            default: begin end
                        endcase
                    end
                end else begin
                    case (v_p)
                        0: renamed.vsrc1.value = v_commit_same ? commit.data : f_vector_registers[v_rs];
                        1: renamed.vsrc2.value = v_commit_same ? commit.data : f_vector_registers[v_rs];
                        2: renamed.vold.value = v_commit_same ? commit.data : f_vector_registers[v_rs];
                        3: renamed.vmask.value = v_commit_same ? commit.data : f_vector_registers[v_rs];
                        default: begin end
                    endcase
                end
            end
        end
    end

    always_comb begin
        lookup_tags = '{default: '0};
        vector_lookup_tags = '{default: '0};
        for (int v_port = 0; v_port < 2; v_port++) begin
            if (d_scalar_used[v_port] && d_scalar_source_regs[v_port] != 5'd0
                && f_mapped[d_scalar_source_regs[v_port] - 5'd1])
                lookup_tags[v_port] = f_mappings[d_scalar_source_regs[v_port] - 5'd1];
        end
        for (int v_port = 0; v_port < 4; v_port++) begin
            if (d_vector_used[v_port] && f_vector_mapped[d_vector_source_regs[v_port]])
                vector_lookup_tags[v_port] = f_vector_mappings[d_vector_source_regs[v_port]];
        end
    end

    always_ff @(posedge clk) begin
        if (reset) begin
            f_mapped <= '0;
            f_vector_mapped <= '0;
            for (int v_i = 0; v_i < 31; v_i++) f_registers[v_i] <= '0;
            for (int v_i = 0; v_i < 32; v_i++) f_vector_registers[v_i] <= '0;
        end else begin
            if (flush) begin
                f_mapped <= '0;
                f_vector_mapped <= '0;
            end
            if (d_commit_writes) begin
                f_registers[commit.uop.rd - 5'd1] <= commit.data[31:0];
                if (!flush && f_mapped[commit.uop.rd - 5'd1]
                    && (f_mappings[commit.uop.rd - 5'd1] == commit.uop.tag))
                    f_mapped[commit.uop.rd - 5'd1] <= 1'b0;
            end
            // Last-connect priority preserves a new writer over an older commit.
            if (dispatch && !flush && uop.writes_rd && (uop.rd != 5'd0) && !uop.exception) begin
                f_mapped[uop.rd - 5'd1] <= 1'b1;
                f_mappings[uop.rd - 5'd1] <= uop.tag;
            end
            if (d_commit_writes_vector) begin
                f_vector_registers[commit.uop.rd] <= commit.data;
                if (!flush && f_vector_mapped[commit.uop.rd]
                    && (f_vector_mappings[commit.uop.rd] == commit.uop.tag))
                    f_vector_mapped[commit.uop.rd] <= 1'b0;
            end
            if (dispatch && !flush && uop.writes_v && !uop.exception) begin
                f_vector_mapped[uop.rd] <= 1'b1;
                f_vector_mappings[uop.rd] <= uop.tag;
            end
        end
    end
endmodule
`default_nettype wire
