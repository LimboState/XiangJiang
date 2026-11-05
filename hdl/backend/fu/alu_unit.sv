// Handwritten translation of ALUUnit; no ISA changes.
`default_nettype none
module alu_unit (
    input logic clk,
    input logic reset,
    input logic flush,
    input logic req_valid,
    input logic resp_ready,
    input logic [5:0] op,
    input logic use_imm,
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
    logic [31:0] d_rhs;
    logic [31:0] d_value;
    xj_pkg::exe_result_t d_result;
    always_comb begin
        d_rhs = use_imm ? imm : b;
        d_value = 32'd0;
        d_result = '0;
        case (op)
            xj_pkg::OP_ADD, xj_pkg::OP_ADDI: d_value = a + d_rhs;
            xj_pkg::OP_SUB: d_value = a - d_rhs;
            xj_pkg::OP_AND, xj_pkg::OP_ANDI: d_value = a & d_rhs;
            xj_pkg::OP_OR, xj_pkg::OP_ORI: d_value = a | d_rhs;
            xj_pkg::OP_XOR, xj_pkg::OP_XORI: d_value = a ^ d_rhs;
            xj_pkg::OP_SLL, xj_pkg::OP_SLLI: d_value = a << d_rhs[4:0];
            xj_pkg::OP_SRL, xj_pkg::OP_SRLI: d_value = a >> d_rhs[4:0];
            xj_pkg::OP_SRA, xj_pkg::OP_SRAI: d_value = $signed(a) >>> d_rhs[4:0];
            xj_pkg::OP_SLT, xj_pkg::OP_SLTI:
                d_value = {31'd0, ($signed(a) < $signed(d_rhs))};
            xj_pkg::OP_SLTU, xj_pkg::OP_SLTIU: d_value = {31'd0, (a < d_rhs)};
            xj_pkg::OP_LUI: d_value = imm;
            xj_pkg::OP_AUIPC: d_value = pc + imm;
            default: d_value = 32'd0;
        endcase
        d_result.tag = tag;
        d_result.seq = seq;
        d_result.next_pc = pc + 32'd4;
        d_result.data[31:0] = d_value;
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
