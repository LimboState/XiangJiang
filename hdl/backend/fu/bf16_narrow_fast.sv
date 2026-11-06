`default_nettype none
module bf16_narrow_fast (
    input logic [31:0] value,
    output logic [15:0] result
);
    logic d_nan;
    logic d_increment;
    logic [15:0] d_rounded;

    // FP32 and BF16 use the same sign and exponent.  RNE therefore keeps
    // value[30:16], with bit 15 as guard and bits [14:0] as round/sticky.
    // Keep the carry bit explicitly: it is the exponent carry at a BF16
    // rounding boundary.  The sign is concatenated after rounding so that
    // both +0 and -0 are preserved.
    always_comb begin
        d_nan = (&value[30:23]) && (|value[22:0]);
        d_increment = value[15] && ((|value[14:0]) || value[16]);
        d_rounded = {1'b0, value[30:16]} + {15'd0, d_increment};
        result = d_nan ? 16'h7fc0 : {value[31], d_rounded[14:0]};
    end
endmodule
`default_nettype wire
