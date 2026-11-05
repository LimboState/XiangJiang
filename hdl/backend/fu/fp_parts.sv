`default_nettype none
module fp_parts #(
    parameter int G_PRECISION = 24
) (
    input logic [G_PRECISION+7:0] value,
    output logic sign,
    output logic is_nan,
    output logic is_inf,
    output logic is_zero,
    output logic [G_PRECISION-1:0] normalized,
    output logic signed [11:0] exponent
);
    logic [G_PRECISION-1:0] d_significand;
    logic [$clog2(G_PRECISION)-1:0] d_leading;
    logic d_found;
    always_comb begin
        sign = value[G_PRECISION+7];
        is_nan = (&value[G_PRECISION+6:G_PRECISION-1]) && (|value[G_PRECISION-2:0]);
        is_inf = (&value[G_PRECISION+6:G_PRECISION-1]) && !(|value[G_PRECISION-2:0]);
        is_zero = !(|value[G_PRECISION+6:0]);
        d_significand = {(|value[G_PRECISION+6:G_PRECISION-1]), value[G_PRECISION-2:0]};
        d_leading = $clog2(G_PRECISION)'(G_PRECISION-1);
        d_found = 1'b0;
        for (int v_index = G_PRECISION-1; v_index >= 0; v_index--) begin
            if (!d_found && d_significand[v_index]) begin
                d_leading = $clog2(G_PRECISION)'(G_PRECISION-1-v_index);
                d_found = 1'b1;
            end
        end
        normalized = d_significand << d_leading;
        exponent = $signed({4'd0, ((|value[G_PRECISION+6:G_PRECISION-1])
            ? value[G_PRECISION+6:G_PRECISION-1] : 8'd1)})
            - 12'sd127 - 12'($signed({1'b0, d_leading}));
    end
endmodule
`default_nettype wire
