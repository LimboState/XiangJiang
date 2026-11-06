`default_nettype none
module systolic_pe_bf16 (
    input logic clk,
    input logic reset,
    input logic clear_pipe,
    input logic move_a,
    input logic move_b,
    input logic a_in_valid,
    input logic b_in_valid,
    input logic [15:0] a_in,
    input logic [15:0] b_in,
    input logic mac_enable,
    input logic load_accumulator,
    input logic [31:0] accumulator_in,
    output logic [15:0] a_out,
    output logic [15:0] b_out,
    output logic a_out_valid,
    output logic b_out_valid,
    output logic [31:0] accumulator
);
    logic [15:0] f_a;
    logic [15:0] f_b;
    logic f_a_valid;
    logic f_b_valid;
    logic [31:0] f_accumulator;
    logic [5:0] f_pending;
    logic [5:0] f_negative_zero_pending;
    logic [31:0] w_result;
    logic w_negative_zero_launch;
    logic [31:0] w_result_ieee;
    fp_arithmetic #(.G_PRECISION(24), .G_OPERATION(2)) i_fma (
        .clk(clk), .operand_a({a_in, 16'd0}), .operand_b({b_in, 16'd0}),
        .operand_c(f_accumulator), .result(w_result)
    );
    assign a_out = f_a;
    assign b_out = f_b;
    assign a_out_valid = f_a_valid;
    assign b_out_valid = f_b_valid;
    assign accumulator = f_accumulator;
    assign w_negative_zero_launch = mac_enable
        && ((a_in[14:0] == 15'd0) || (b_in[14:0] == 15'd0))
        && (f_accumulator[30:0] == 31'd0)
        && (a_in[15] ^ b_in[15]) && f_accumulator[31];
    assign w_result_ieee = (f_negative_zero_pending[5] && w_result[30:0] == 31'd0)
        ? {1'b1, 31'd0} : w_result;
    always_ff @(posedge clk) begin
        if (move_a && !clear_pipe) f_a <= a_in;
        if (move_b && !clear_pipe) f_b <= b_in;
        if (reset) begin
            f_a_valid <= 1'b0;
            f_b_valid <= 1'b0;
            f_accumulator <= 32'd0;
            f_pending <= '0;
            f_negative_zero_pending <= '0;
        end else begin
            f_a_valid <= !clear_pipe && (move_a ? a_in_valid : f_a_valid);
            f_b_valid <= !clear_pipe && (move_b ? b_in_valid : f_b_valid);
            f_pending <= clear_pipe || load_accumulator ? 6'd0 : {f_pending[4:0], mac_enable};
            f_negative_zero_pending <= clear_pipe || load_accumulator ? 6'd0
                : {f_negative_zero_pending[4:0], w_negative_zero_launch};
            if (load_accumulator) f_accumulator <= accumulator_in;
            else if (f_pending[5] && !clear_pipe) f_accumulator <= w_result_ieee;
        end
    end
endmodule
`default_nettype wire
