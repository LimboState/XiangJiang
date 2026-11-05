`default_nettype none
module fp_sum_pipeline #(
    parameter int G_PRECISION = 24,
    parameter int G_WIDTH = 53
) (
    input logic clk,
    input logic [G_WIDTH-1:0] magnitude_a,
    input logic [G_WIDTH-1:0] magnitude_b,
    input logic signed [11:0] exponent_a,
    input logic signed [11:0] exponent_b,
    input logic sign_a,
    input logic sign_b,
    input logic special,
    input logic [G_PRECISION+7:0] special_value,
    output logic [G_PRECISION+7:0] result
);
    logic signed [11:0] d_common;
    logic [G_WIDTH-1:0] f_aligned_a;
    logic [G_WIDTH-1:0] f_aligned_b;
    logic f_sign_a;
    logic f_sign_b;
    logic signed [11:0] f_scale_0;
    logic f_special_0;
    logic [G_PRECISION+7:0] f_special_value_0;
    logic [G_WIDTH-1:0] d_magnitude;
    logic d_sign;
    logic [G_WIDTH-1:0] f_magnitude;
    logic f_sign;
    logic signed [11:0] f_scale;
    logic f_special;
    logic [G_PRECISION+7:0] f_special_value;
    function automatic logic [G_WIDTH-1:0] jam(
        input logic [G_WIDTH-1:0] v_value, input logic [11:0] v_distance
    );
        logic [$clog2(G_WIDTH+1)-1:0] v_shift;
        logic [G_WIDTH-1:0] v_shifted;
        v_shift = v_distance >= 12'(G_WIDTH) ? $clog2(G_WIDTH+1)'(G_WIDTH)
            : $clog2(G_WIDTH+1)'(v_distance);
        v_shifted = v_value >> v_shift;
        return v_shifted | G_WIDTH'(v_value != (v_shifted << v_shift));
    endfunction
    always_comb begin
        d_common = exponent_a >= exponent_b ? exponent_a : exponent_b;
        d_magnitude = f_sign_a == f_sign_b ? f_aligned_a + f_aligned_b
            : f_aligned_a >= f_aligned_b ? f_aligned_a - f_aligned_b : f_aligned_b - f_aligned_a;
        d_sign = !(|d_magnitude) ? f_sign_a && f_sign_b
            : (f_sign_a == f_sign_b || f_aligned_a >= f_aligned_b) ? f_sign_a : f_sign_b;
    end
    always_ff @(posedge clk) begin
        f_aligned_a <= jam(magnitude_a, $unsigned(d_common - exponent_a));
        f_aligned_b <= jam(magnitude_b, $unsigned(d_common - exponent_b));
        f_sign_a <= sign_a;
        f_sign_b <= sign_b;
        f_scale_0 <= d_common - 12'(G_WIDTH-2);
        f_special_0 <= special;
        f_special_value_0 <= special_value;
        f_magnitude <= d_magnitude;
        f_sign <= d_sign;
        f_scale <= f_scale_0;
        f_special <= f_special_0;
        f_special_value <= f_special_value_0;
    end
    fp_round #(.G_PRECISION(G_PRECISION), .G_WIDTH(G_WIDTH)) i_fp_round (
        .clk(clk), .magnitude(f_magnitude), .scale(f_scale), .sign(f_sign),
        .special(f_special), .special_value(f_special_value), .result(result)
    );
endmodule
`default_nettype wire
