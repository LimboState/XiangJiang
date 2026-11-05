module bf16_narrow_fast_tb;
    logic [31:0] value;
    logic [15:0] result;
    logic [15:0] reference_result;
    logic [15:0] convert_result;
    logic [31:0] unused_from_int;
    logic [31:0] unused_to_int;
    logic reference_sign;
    logic reference_nan;
    logic reference_inf;
    logic unused_zero;
    logic [23:0] reference_normalized;
    logic signed [11:0] reference_exponent;
    logic [15:0] tails [11];
    logic [15:0] boundaries [16];
    int checked = 0;

    bf16_narrow_fast i_dut (.value(value), .result(result));
    fp_convert i_convert (
        .value(value), .narrow(convert_result),
        .from_int(unused_from_int), .to_int(unused_to_int)
    );
    fp_parts i_reference_parts (
        .value(value), .sign(reference_sign), .is_nan(reference_nan),
        .is_inf(reference_inf), .is_zero(unused_zero),
        .normalized(reference_normalized), .exponent(reference_exponent)
    );
    fp_round #(.G_PRECISION(8), .G_WIDTH(27), .G_PIPELINED(1'b0)) i_reference_round (
        .clk(1'b0), .magnitude({reference_normalized, 3'd0}),
        .scale(reference_exponent - 12'sd26), .sign(reference_sign),
        .special(reference_nan || reference_inf),
        .special_value(reference_nan ? 16'h7fc0 : {reference_sign, 15'h7f80}),
        .result(reference_result)
    );

    task automatic compare(input logic [31:0] operand);
        value = operand;
        #1;
        assert (result === reference_result && convert_result === reference_result)
            else $fatal(1, "narrow input=%h fast=%h convert=%h generic=%h",
                value, result, convert_result, reference_result);
        checked++;
    endtask

    task automatic expect_value(input logic [31:0] operand, input logic [15:0] expected);
        compare(operand);
        assert (result === expected)
            else $fatal(1, "narrow input=%h expected=%h actual=%h", operand, expected, result);
    endtask

    initial begin
        value = '0;
        tails = '{16'h0000, 16'h0001, 16'h0002, 16'h3fff, 16'h4000,
            16'h7ffe, 16'h7fff, 16'h8000, 16'h8001, 16'hfffe, 16'hffff};
        boundaries = '{16'h0000, 16'h0001, 16'h007f, 16'h0080, 16'h3f7f,
            16'h3f80, 16'h3f81, 16'h7f7f, 16'h7f80, 16'h7fc0,
            16'h8000, 16'h807f, 16'h8080, 16'hbf80, 16'hff7f, 16'hff80};
        expect_value(32'h00000000, 16'h0000);
        expect_value(32'h80000000, 16'h8000);
        expect_value(32'h00008000, 16'h0000);
        expect_value(32'h80008000, 16'h8000);
        expect_value(32'h00008001, 16'h0001);
        expect_value(32'h80008001, 16'h8001);
        expect_value(32'h00018000, 16'h0002);
        expect_value(32'h007f8000, 16'h0080);
        expect_value(32'h807f8000, 16'h8080);
        expect_value(32'h3f808000, 16'h3f80);
        expect_value(32'h3f818000, 16'h3f82);
        expect_value(32'h3fff8000, 16'h4000);
        expect_value(32'h7f7f7fff, 16'h7f7f);
        expect_value(32'h7f7f8000, 16'h7f80);
        expect_value(32'hff7f8000, 16'hff80);
        expect_value(32'h7f800000, 16'h7f80);
        expect_value(32'hff800000, 16'hff80);
        expect_value(32'h7f800001, 16'h7fc0);
        expect_value(32'hff800001, 16'h7fc0);
        expect_value(32'hffffffff, 16'h7fc0);
        for (int upper = 0; upper < 65536; upper++)
            for (int tail = 0; tail < 11; tail++) compare({16'(upper), tails[tail]});
        for (int boundary = 0; boundary < 16; boundary++)
            for (int lower = 0; lower < 65536; lower++) compare({boundaries[boundary], 16'(lower)});
        for (int sample = 0; sample < 100000; sample++) compare($urandom);
        $display("PASS BF16 fast narrow: %0d inputs against original generic narrow", checked);
        $finish;
    end
endmodule
