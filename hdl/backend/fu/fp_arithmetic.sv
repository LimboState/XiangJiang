`default_nettype none
module fp_arithmetic #(
    parameter int G_PRECISION = 24,
    parameter int G_OPERATION = 2
) (
    input logic clk,
    input logic [G_PRECISION+7:0] operand_a,
    input logic [G_PRECISION+7:0] operand_b,
    // Add and multiply specializations have no third operand.
    /* verilator lint_off UNUSEDSIGNAL */
    input logic [G_PRECISION+7:0] operand_c,
    /* verilator lint_on UNUSEDSIGNAL */
    output logic [G_PRECISION+7:0] result
);
    localparam logic [G_PRECISION+7:0] C_NAN =
        {1'b0, 8'hff, 1'b1, {(G_PRECISION-2){1'b0}}};
    logic [G_PRECISION+7:0] w_input [3];
    logic w_sign [3];
    logic w_nan [3];
    logic w_inf [3];
    logic w_zero [3];
    logic [G_PRECISION-1:0] w_normalized [3];
    logic signed [11:0] w_exponent [3];
    if (G_OPERATION == 2) begin : gen_input_capture
        logic [G_PRECISION+7:0] f_input [3];
        always_ff @(posedge clk) begin
            f_input[0] <= operand_a;
            f_input[1] <= operand_b;
            f_input[2] <= operand_c;
        end
        assign w_input = f_input;
    end else begin : gen_direct_input
        assign w_input[0] = operand_a;
        assign w_input[1] = operand_b;
        assign w_input[2] = '0;
    end
    for (genvar g_index = 0; g_index < 3; g_index++) begin : gen_parts
        fp_parts #(.G_PRECISION(G_PRECISION)) i_fp_parts (
            .value(w_input[g_index]), .sign(w_sign[g_index]), .is_nan(w_nan[g_index]),
            .is_inf(w_inf[g_index]), .is_zero(w_zero[g_index]),
            .normalized(w_normalized[g_index]), .exponent(w_exponent[g_index])
        );
    end
    if (G_OPERATION == 0) begin : gen_add
        logic d_special;
        logic [G_PRECISION+7:0] d_special_value;
        always_comb begin
            d_special = w_nan[0] || w_nan[1] || w_inf[0] || w_inf[1];
            d_special_value = {w_inf[0] ? w_sign[0] : w_sign[1],
                8'hff, {(G_PRECISION-1){1'b0}}};
            if (w_nan[0] || w_nan[1] || (w_inf[0] && w_inf[1] && w_sign[0] != w_sign[1]))
                d_special_value = C_NAN;
        end
        fp_sum_pipeline #(.G_PRECISION(G_PRECISION), .G_WIDTH(G_PRECISION+5)) i_fp_sum (
            .clk(clk), .magnitude_a({1'b0, w_normalized[0], 4'd0}),
            .magnitude_b({1'b0, w_normalized[1], 4'd0}),
            .exponent_a(w_zero[0] ? -12'sd512 : w_exponent[0]),
            .exponent_b(w_zero[1] ? -12'sd512 : w_exponent[1]),
            .sign_a(w_sign[0]), .sign_b(w_sign[1]), .special(d_special),
            .special_value(d_special_value), .result(result)
        );
    end else if (G_OPERATION == 1) begin : gen_multiply
        logic [G_PRECISION-1:0] f_normalized_a;
        logic [G_PRECISION-1:0] f_normalized_b;
        logic [2*G_PRECISION-1:0] f_product;
        logic signed [11:0] f_scale [2];
        logic [1:0] f_sign;
        logic [1:0] f_special;
        logic [G_PRECISION+7:0] f_special_value [2];
        always_ff @(posedge clk) begin
            f_normalized_a <= w_normalized[0];
            f_normalized_b <= w_normalized[1];
            f_product <= (2*G_PRECISION)'(f_normalized_a) * (2*G_PRECISION)'(f_normalized_b);
            f_scale[0] <= w_exponent[0] + w_exponent[1] - 12'(2*G_PRECISION+1);
            f_scale[1] <= f_scale[0];
            f_sign <= {f_sign[0], w_sign[0] ^ w_sign[1]};
            f_special <= {f_special[0], w_nan[0] || w_nan[1] || w_inf[0] || w_inf[1]};
            f_special_value[0] <= w_nan[0] || w_nan[1] ||
                (w_inf[0] && w_zero[1]) || (w_inf[1] && w_zero[0]) ? C_NAN
                : {w_sign[0] ^ w_sign[1], 8'hff, {(G_PRECISION-1){1'b0}}};
            f_special_value[1] <= f_special_value[0];
        end
        fp_round #(.G_PRECISION(G_PRECISION), .G_WIDTH(2*G_PRECISION+3)) i_fp_round (
            .clk(clk), .magnitude({f_product, 3'd0}), .scale(f_scale[1]), .sign(f_sign[1]),
            .special(f_special[1]), .special_value(f_special_value[1]), .result(result)
        );
    end else begin : gen_fma
        localparam int C_WIDTH = 2*G_PRECISION+5;
        logic [2*G_PRECISION-1:0] f_product;
        logic [C_WIDTH-1:0] f_addend;
        logic signed [11:0] f_product_exponent;
        logic signed [11:0] f_addend_exponent;
        logic f_product_zero;
        logic f_product_sign;
        logic f_addend_sign;
        logic f_special;
        logic [G_PRECISION+7:0] f_special_value;
        logic [C_WIDTH-1:0] d_product_magnitude;
        logic signed [11:0] d_product_exponent;
        always_comb begin
            d_product_magnitude = f_product[2*G_PRECISION-1]
                ? {1'b0, f_product, 4'd0} : {1'b0, f_product[2*G_PRECISION-2:0], 5'd0};
            d_product_exponent = f_product_zero ? -12'sd512
                : f_product_exponent + $signed({11'd0, f_product[2*G_PRECISION-1]});
        end
        always_ff @(posedge clk) begin
            f_product <= (2*G_PRECISION)'(w_normalized[0]) * (2*G_PRECISION)'(w_normalized[1]);
            f_addend <= {1'b0, w_normalized[2], {(G_PRECISION+4){1'b0}}};
            f_product_exponent <= w_exponent[0] + w_exponent[1];
            f_addend_exponent <= w_zero[2] ? -12'sd512 : w_exponent[2];
            f_product_zero <= w_zero[0] || w_zero[1];
            f_product_sign <= w_sign[0] ^ w_sign[1];
            f_addend_sign <= w_sign[2];
            f_special <= w_nan[0] || w_nan[1] || w_nan[2] || w_inf[0] || w_inf[1] || w_inf[2];
            f_special_value <= w_nan[0] || w_nan[1] || w_nan[2]
                || (w_inf[0] && w_zero[1]) || (w_inf[1] && w_zero[0])
                || ((w_inf[0] || w_inf[1]) && w_inf[2] && (w_sign[0]^w_sign[1]) != w_sign[2])
                ? C_NAN : {(w_inf[0] || w_inf[1]) ? (w_sign[0]^w_sign[1]) : w_sign[2],
                    8'hff, {(G_PRECISION-1){1'b0}}};
        end
        fp_sum_pipeline #(.G_PRECISION(G_PRECISION), .G_WIDTH(C_WIDTH)) i_fp_sum (
            .clk(clk), .magnitude_a(d_product_magnitude), .magnitude_b(f_addend),
            .exponent_a(d_product_exponent), .exponent_b(f_addend_exponent),
            .sign_a(f_product_sign), .sign_b(f_addend_sign),
            .special(f_special), .special_value(f_special_value), .result(result)
        );
    end
endmodule
`default_nettype wire
