`default_nettype none
module fp_convert (
    input logic [31:0] value,
    output logic [15:0] narrow,
    output logic [31:0] from_int,
    output logic [31:0] to_int
);
    logic w_sign;
    logic w_nan;
    logic w_inf;
    logic w_zero;
    logic [23:0] w_normalized;
    logic signed [11:0] w_exponent;
    logic [31:0] d_magnitude;
    logic signed [11:0] d_right;
    logic [11:0] d_left;
    logic [4:0] d_left_bounded;
    logic [5:0] d_right_bounded;
    logic [34:0] d_source;
    logic [34:0] d_right_shifted;
    logic [34:0] d_shifted;
    logic [32:0] d_rounded;
    logic d_too_large;
    logic [31:0] d_signed;
    fp_parts i_fp_parts (
        .value(value), .sign(w_sign), .is_nan(w_nan), .is_inf(w_inf), .is_zero(w_zero),
        .normalized(w_normalized), .exponent(w_exponent)
    );
    bf16_narrow_fast i_bf16_narrow_fast (
        .value(value), .result(narrow)
    );
    fp_round #(.G_PRECISION(24), .G_WIDTH(35), .G_PIPELINED(1'b0)) i_from_int (
        .clk(1'b0), .magnitude({d_magnitude, 3'd0}), .scale(-12'sd3),
        .sign(value[31]), .special(1'b0), .special_value(32'd0), .result(from_int)
    );
    always_comb begin
        d_magnitude = value[31] ? ~value + 32'd1 : value;
        d_source = {8'd0, w_normalized, 3'd0};
        d_right = 12'sd23 - w_exponent;
        d_left = d_right < 12'sd0 ? $unsigned(-d_right) : 12'd0;
        d_left_bounded = d_left > 12'd31 ? 5'd31 : d_left[4:0];
        d_right_bounded = d_right >= 12'sd35 ? 6'd35 : 6'(d_right);
        d_right_shifted = d_source >> d_right_bounded;
        d_shifted = d_right >= 12'sd0 ? d_right_shifted
            | 35'(d_source != (d_right_shifted << d_right_bounded)) : d_source << d_left_bounded;
        d_rounded = {1'b0, d_shifted[34:3]}
            + 33'(d_shifted[2] && ((|d_shifted[1:0]) || d_shifted[3]));
        d_too_large = w_exponent >= 12'sd31 || d_rounded >= 33'h080000000;
        d_signed = w_sign ? ~d_rounded[31:0] + 32'd1 : d_rounded[31:0];
        to_int = w_nan ? 32'h7fffffff : (w_inf || d_too_large)
            ? (w_sign ? 32'h80000000 : 32'h7fffffff) : d_signed;
        if (w_zero) to_int = 32'd0;
    end
endmodule
`default_nettype wire
