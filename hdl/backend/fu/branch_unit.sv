// BranchUnit translation. Only the selected next PC is checked.
`default_nettype none
module branch_unit #(
    parameter logic [31:0] G_ITCM_BYTES = 32'd262144
) (
    input logic clk,
    input logic reset,
    input logic flush,
    input logic req_valid,
    input logic resp_ready,
    input logic [5:0] op,
    input logic [31:0] a,
    input logic [31:0] b,
    input logic [31:0] imm,
    input logic [31:0] pc,
    input xj_pkg::rob_tag_t tag,
    input logic [xj_pkg::C_SEQUENCE_WIDTH-1:0] seq,
    output logic req_ready,
    output logic resp_valid,
    output xj_pkg::exe_result_t resp,
    output logic busy
);
    logic d_taken;
    logic d_link;
    logic d_misaligned;
    logic d_outside;
    logic [31:0] d_target;
    logic [31:0] d_next_pc;
    xj_pkg::exe_result_t d_result;

    always_comb begin
        d_taken = 1'b0;
        d_link = (op == xj_pkg::OP_JAL) || (op == xj_pkg::OP_JALR);
        d_target = (op == xj_pkg::OP_JALR) ? ((a + imm) & 32'hfffffffe) : pc + imm;
        d_next_pc = pc + 32'd4;
        d_misaligned = 1'b0;
        d_outside = 1'b0;
        d_result = '0;
        case (op)
            xj_pkg::OP_BEQ: d_taken = (a == b);
            xj_pkg::OP_BNE: d_taken = (a != b);
            xj_pkg::OP_BLT: d_taken = ($signed(a) < $signed(b));
            xj_pkg::OP_BGE: d_taken = ($signed(a) >= $signed(b));
            xj_pkg::OP_BLTU: d_taken = (a < b);
            xj_pkg::OP_BGEU: d_taken = (a >= b);
            xj_pkg::OP_JAL, xj_pkg::OP_JALR: d_taken = 1'b1;
            default: d_taken = 1'b0;
        endcase
        d_next_pc = d_taken ? d_target : pc + 32'd4;
        d_misaligned = (d_next_pc[1:0] != 2'b00);
        d_outside = (d_next_pc >= G_ITCM_BYTES);
        d_result.tag = tag;
        d_result.seq = seq;
        d_result.next_pc = d_next_pc;
        d_result.exception = d_misaligned || d_outside;
        d_result.cause = d_misaligned ? 5'd2 : (d_outside ? 5'd3 : 5'd0);
        d_result.bad_addr = d_result.exception ? d_next_pc : 32'd0;
        if (d_link && !d_result.exception) d_result.data[31:0] = pc + 32'd4;
    end
    scalar_completion i_scalar_completion (
        .clk(clk),
        .reset(reset),
        .flush(flush),
        .req_valid(req_valid),
        .resp_ready(resp_ready),
        .result(d_result),
        .req_ready(req_ready),
        .resp_valid(resp_valid),
        .resp(resp),
        .busy(busy)
    );
endmodule
`default_nettype wire
