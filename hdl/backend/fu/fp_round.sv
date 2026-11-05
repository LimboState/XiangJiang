`default_nettype none
module fp_round #(
    parameter int G_PRECISION = 24,
    parameter int G_WIDTH = 53,
    parameter bit G_PIPELINED = 1'b1
) (
    // The combinational rounding specialization does not consume a clock.
    /* verilator lint_off UNUSEDSIGNAL */
    input logic clk,
    /* verilator lint_on UNUSEDSIGNAL */
    input logic [G_WIDTH-1:0] magnitude,
    input logic signed [11:0] scale,
    input logic sign,
    input logic special,
    input logic [G_PRECISION+7:0] special_value,
    output logic [G_PRECISION+7:0] result
);
    localparam int C_LEADING_WIDTH = $clog2(G_WIDTH);
    logic [C_LEADING_WIDTH-1:0] d_leading;
    logic d_found;
    logic [G_WIDTH-1:0] d_normalized;
    logic signed [11:0] d_exponent;
    logic [G_WIDTH-1:0] w_normalized;
    logic signed [11:0] w_exponent;
    logic w_sign_0;
    logic w_nonzero_0;
    logic w_special_0;
    logic [G_PRECISION+7:0] w_special_value_0;
    logic [11:0] d_distance;
    logic [$clog2(G_WIDTH+1)-1:0] d_shift;
    logic [G_WIDTH-1:0] d_shifted;
    logic [G_PRECISION+2:0] d_significand;
    logic signed [11:0] d_effective_exponent;
    logic [G_PRECISION+2:0] w_significand;
    logic signed [11:0] w_effective_exponent;
    logic w_sign_1;
    logic w_nonzero_1;
    logic w_special_1;
    logic [G_PRECISION+7:0] w_special_value_1;
    logic [G_PRECISION:0] d_rounded;
    logic signed [11:0] d_final_exponent;
    logic [7:0] d_exp_field;
    logic [G_PRECISION-2:0] d_fraction;

    always_comb begin
        d_leading = C_LEADING_WIDTH'(G_WIDTH-1);
        d_found = 1'b0;
        for (int v_index = G_WIDTH-1; v_index >= 0; v_index--) begin
            if (!d_found && magnitude[v_index]) begin
                d_leading = C_LEADING_WIDTH'(G_WIDTH-1-v_index);
                d_found = 1'b1;
            end
        end
        d_normalized = magnitude << d_leading;
        d_exponent = scale + 12'(G_WIDTH-1) - 12'($signed({1'b0, d_leading}));
        d_distance = 12'(G_WIDTH-G_PRECISION-3) +
            (w_exponent < -12'sd126 ? $unsigned(-12'sd126-w_exponent) : 12'd0);
        d_shift = d_distance >= 12'(G_WIDTH) ? $clog2(G_WIDTH+1)'(G_WIDTH)
            : $clog2(G_WIDTH+1)'(d_distance);
        d_shifted = w_normalized >> d_shift;
        d_significand = (G_PRECISION+3)'(d_shifted)
            | (G_PRECISION+3)'(w_normalized != (d_shifted << d_shift));
        d_effective_exponent = w_exponent < -12'sd126 ? -12'sd126 : w_exponent;
        d_rounded = {1'b0, w_significand[G_PRECISION+2:3]}
            + (G_PRECISION+1)'(w_significand[2] &&
                ((|w_significand[1:0]) || w_significand[3]));
        d_final_exponent = w_effective_exponent + $signed({11'd0, d_rounded[G_PRECISION]});
        d_exp_field = (d_rounded[G_PRECISION] || d_rounded[G_PRECISION-1])
            ? 8'(d_final_exponent + 12'sd127) : 8'd0;
        d_fraction = d_rounded[G_PRECISION] ? d_rounded[G_PRECISION-1:1]
            : d_rounded[G_PRECISION-2:0];
        result = {w_sign_1, d_exp_field, d_fraction};
        if (d_final_exponent > 12'sd127)
            result = {w_sign_1, 8'hff, {(G_PRECISION-1){1'b0}}};
        if (!w_nonzero_1) result = {w_sign_1, {(G_PRECISION+7){1'b0}}};
        if (w_special_1) result = w_special_value_1;
    end
    if (G_PIPELINED) begin : gen_pipeline
        logic [G_WIDTH-1:0] f_normalized;
        logic signed [11:0] f_exponent;
        logic f_sign_0;
        logic f_nonzero_0;
        logic f_special_0;
        logic [G_PRECISION+7:0] f_special_value_0;
        logic [G_PRECISION+2:0] f_significand;
        logic signed [11:0] f_effective_exponent;
        logic f_sign_1;
        logic f_nonzero_1;
        logic f_special_1;
        logic [G_PRECISION+7:0] f_special_value_1;
        always_ff @(posedge clk) begin
            f_normalized <= d_normalized;
            f_exponent <= d_exponent;
            f_sign_0 <= sign;
            f_nonzero_0 <= |magnitude;
            f_special_0 <= special;
            f_special_value_0 <= special_value;
            f_significand <= d_significand;
            f_effective_exponent <= d_effective_exponent;
            f_sign_1 <= w_sign_0;
            f_nonzero_1 <= w_nonzero_0;
            f_special_1 <= w_special_0;
            f_special_value_1 <= w_special_value_0;
        end
        assign w_normalized = f_normalized;
        assign w_exponent = f_exponent;
        assign w_sign_0 = f_sign_0;
        assign w_nonzero_0 = f_nonzero_0;
        assign w_special_0 = f_special_0;
        assign w_special_value_0 = f_special_value_0;
        assign w_significand = f_significand;
        assign w_effective_exponent = f_effective_exponent;
        assign w_sign_1 = f_sign_1;
        assign w_nonzero_1 = f_nonzero_1;
        assign w_special_1 = f_special_1;
        assign w_special_value_1 = f_special_value_1;
    end else begin : gen_combinational
        assign w_normalized = d_normalized;
        assign w_exponent = d_exponent;
        assign w_sign_0 = sign;
        assign w_nonzero_0 = |magnitude;
        assign w_special_0 = special;
        assign w_special_value_0 = special_value;
        assign w_significand = d_significand;
        assign w_effective_exponent = d_effective_exponent;
        assign w_sign_1 = w_sign_0;
        assign w_nonzero_1 = w_nonzero_0;
        assign w_special_1 = w_special_0;
        assign w_special_value_1 = w_special_value_0;
    end
endmodule
`default_nettype wire
