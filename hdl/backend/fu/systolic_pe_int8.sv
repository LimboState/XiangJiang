`default_nettype none
module systolic_pe_int8 (
    input  logic        clk,
    input  logic        reset,
    input  logic        clear_pipe,
    input  logic        move_a,
    input  logic        move_b,
    input  logic        a_in_valid,
    input  logic        b_in_valid,
    input  logic [7:0]  a_in,
    input  logic [7:0]  b_in,
    input  logic [2:0]  tag_in,
    input  logic [31:0] sum_in,
    input  logic        mac_enable,
    input  logic        use_accumulator,
    input  logic        use_stored_b,
    input  logic        load_accumulator,
    input  logic [31:0] accumulator_in,
    output logic [7:0]  a_out,
    output logic [7:0]  b_out,
    output logic        a_out_valid,
    output logic        b_out_valid,
    output logic [2:0]  tag_out,
    output logic [31:0] sum_out,
    output logic        sum_out_valid,
    output logic [31:0] accumulator
);
    logic [7:0] f_a;
    logic [7:0] f_b;
    logic f_a_valid;
    logic f_b_valid;
    logic [2:0] f_tag;
    logic [31:0] f_sum;
    logic f_sum_valid;
    logic [31:0] f_accumulator;
    logic signed [15:0] d_product;
    logic [31:0] d_mac_result;

    always_comb begin
        d_product = $signed(a_in) * $signed(use_stored_b ? f_b : b_in);
        d_mac_result = (use_accumulator ? f_accumulator : sum_in)
            + {{16{d_product[15]}}, d_product};
        a_out = f_a;
        b_out = f_b;
        a_out_valid = f_a_valid;
        b_out_valid = f_b_valid;
        tag_out = f_tag;
        sum_out = f_sum;
        sum_out_valid = f_sum_valid;
        accumulator = f_accumulator;
    end

    always_ff @(posedge clk) begin
        if (move_a && !clear_pipe) begin
            f_a <= a_in;
            f_tag <= tag_in;
        end
        if (move_b && !clear_pipe) f_b <= b_in;
        if (mac_enable && !clear_pipe && !use_accumulator && !load_accumulator)
            f_tag <= tag_in;
        if (reset) begin
            f_a_valid <= 1'b0;
            f_b_valid <= 1'b0;
            f_sum <= 32'd0;
            f_sum_valid <= 1'b0;
            f_accumulator <= 32'd0;
        end else begin
            f_a_valid <= !clear_pipe && (move_a ? a_in_valid : f_a_valid);
            f_b_valid <= !clear_pipe && (move_b ? b_in_valid : f_b_valid);
            f_sum_valid <= mac_enable && !clear_pipe && !use_accumulator && !load_accumulator;
            if (mac_enable && !clear_pipe && !use_accumulator && !load_accumulator)
                f_sum <= d_mac_result;
            if (load_accumulator) f_accumulator <= accumulator_in;
            else if (mac_enable && !clear_pipe && use_accumulator)
                f_accumulator <= d_mac_result;
        end
    end
endmodule
`default_nettype wire
