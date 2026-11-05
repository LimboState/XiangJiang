`default_nettype none
module bf16_narrow_fast (
    input logic [31:0] value,
    output logic [15:0] result
);
    logic d_nan;
    logic d_increment;
    logic [14:0] d_rounded;

    always_comb begin
        d_nan = (&value[30:23]) && (|value[22:0]);
        d_increment = value[15] && ((|value[14:0]) || value[16]);
        d_rounded = value[30:16] + 15'(d_increment);
        result = d_nan ? 16'h7fc0 : {value[31], d_rounded};
    end
endmodule
`default_nettype wire
