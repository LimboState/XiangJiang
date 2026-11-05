`default_nettype none
module fp_compare #(
    parameter int G_PRECISION = 8
) (
    input logic [G_PRECISION+7:0] operand_a,
    input logic [G_PRECISION+7:0] operand_b,
    output logic equal,
    output logic less,
    output logic [G_PRECISION+7:0] minimum,
    output logic [G_PRECISION+7:0] maximum
);
    logic d_nan_a;
    logic d_nan_b;
    logic d_zero_a;
    logic d_zero_b;
    logic d_less;
    localparam logic [G_PRECISION+7:0] C_NAN =
        {1'b0, 8'hff, 1'b1, {(G_PRECISION-2){1'b0}}};
    always_comb begin
        d_nan_a = (&operand_a[G_PRECISION+6:G_PRECISION-1]) && (|operand_a[G_PRECISION-2:0]);
        d_nan_b = (&operand_b[G_PRECISION+6:G_PRECISION-1]) && (|operand_b[G_PRECISION-2:0]);
        d_zero_a = !(|operand_a[G_PRECISION+6:0]);
        d_zero_b = !(|operand_b[G_PRECISION+6:0]);
        d_less = operand_a[G_PRECISION+7] != operand_b[G_PRECISION+7]
            ? operand_a[G_PRECISION+7] : operand_a[G_PRECISION+7]
            ? operand_a[G_PRECISION+6:0] > operand_b[G_PRECISION+6:0]
            : operand_a[G_PRECISION+6:0] < operand_b[G_PRECISION+6:0];
        equal = !d_nan_a && !d_nan_b && (operand_a == operand_b || (d_zero_a && d_zero_b));
        less = !d_nan_a && !d_nan_b && !(d_zero_a && d_zero_b) && d_less;
        minimum = less ? operand_a : operand_b;
        maximum = d_less ? operand_b : operand_a;
        if (d_zero_a && d_zero_b) begin
            minimum = {operand_a[G_PRECISION+7] || operand_b[G_PRECISION+7],
                {(G_PRECISION+7){1'b0}}};
            maximum = {operand_a[G_PRECISION+7] && operand_b[G_PRECISION+7],
                {(G_PRECISION+7){1'b0}}};
        end
        if (d_nan_a) begin minimum = operand_b; maximum = operand_b; end
        if (d_nan_b) begin minimum = operand_a; maximum = operand_a; end
        if (d_nan_a && d_nan_b) begin minimum = C_NAN; maximum = C_NAN; end
    end
endmodule
`default_nettype wire
