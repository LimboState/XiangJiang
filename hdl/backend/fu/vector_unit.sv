`default_nettype none
module vector_unit #(
    parameter int G_GROUP = -1
) (
    input logic clk,
    input logic reset,
    input logic flush,
    input logic req_valid,
    // Vector execution ignores scalar-only fields in the common issue bundle.
    /* verilator lint_off UNUSEDSIGNAL */
    input xj_pkg::issue_entry_t req,
    /* verilator lint_on UNUSEDSIGNAL */
    input logic resp_ready,
    output logic req_ready,
    output logic resp_valid,
    output xj_pkg::exe_result_t resp,
    output logic busy
);
    typedef enum logic [1:0] {
        ST_IDLE = 2'd0, ST_SELECT = 2'd1, ST_RUN = 2'd2, ST_COMPLETE = 2'd3
    } state_t;
    state_t f_state;
    state_t d_state;
    logic [5:0] f_op;
    logic [1:0] f_unary;
    logic [4:0] f_rs1;
    logic f_unmasked;
    logic [31:0] f_pc;
    xj_pkg::rob_tag_t f_tag;
    logic [15:0] f_seq;
    logic [255:0] f_source1;
    logic [255:0] f_source2;
    logic [255:0] f_old;
    logic [31:0] f_scalar;
    logic [7:0] f_mask;
    logic [2:0] f_lane;
    logic [255:0] f_accumulated;
    logic [31:0] f_reduction;
    xj_pkg::exe_result_t f_completion;
    logic [31:0] f_a;
    logic [31:0] f_b;
    // Only the floating-point group consumes the saved FMA addend.
    /* verilator lint_off UNUSEDSIGNAL */
    logic [15:0] f_old_lane;
    /* verilator lint_on UNUSEDSIGNAL */
    logic [2:0] f_arithmetic_cycle;
    logic f_div_sent;
    logic d_active;
    logic d_comparison_op;
    logic d_reduction_op;
    logic [31:0] d_value;
    logic d_comparison;
    logic d_lane_done;
    logic [255:0] d_next_vector;
    logic [31:0] d_next_reduction;
    logic [31:0] d_distance;
    logic [2:0] d_up_index;
    logic [3:0] d_down_index;
    logic [31:0] d_index_value;
    logic d_divide;
    logic d_div_req_valid;
    logic d_div_resp_ready;
    logic w_div_req_ready;
    logic w_div_resp_valid;
    logic [15:0] w_div_result;
    logic [15:0] w_add;
    logic [15:0] w_sub;
    logic [15:0] w_mul;
    logic [15:0] w_fma;
    logic w_eq;
    logic w_lt;
    logic [15:0] w_min;
    logic [15:0] w_max;
    logic [15:0] w_narrow;
    logic [31:0] w_from_int;
    logic [31:0] w_to_int;
    logic [31:0] w_reduce_add;
    logic [31:0] w_reduce_max;

    if (G_GROUP == -1 || G_GROUP == 2) begin : gen_float
        fp_arithmetic #(.G_PRECISION(8), .G_OPERATION(0)) i_add (
            .clk(clk), .operand_a(f_a[15:0]), .operand_b(f_b[15:0]),
            .operand_c(16'd0), .result(w_add)
        );
        fp_arithmetic #(.G_PRECISION(8), .G_OPERATION(0)) i_sub (
            .clk(clk), .operand_a(f_a[15:0]), .operand_b({!f_b[15], f_b[14:0]}),
            .operand_c(16'd0), .result(w_sub)
        );
        fp_arithmetic #(.G_PRECISION(8), .G_OPERATION(1)) i_mul (
            .clk(clk), .operand_a(f_a[15:0]), .operand_b(f_b[15:0]),
            .operand_c(16'd0), .result(w_mul)
        );
        fp_arithmetic #(.G_PRECISION(8), .G_OPERATION(2)) i_fma (
            .clk(clk), .operand_a(f_a[15:0]), .operand_b(f_b[15:0]),
            .operand_c(f_old_lane), .result(w_fma)
        );
        fp_compare i_compare (.operand_a(f_a[15:0]), .operand_b(f_b[15:0]),
            .equal(w_eq), .less(w_lt), .minimum(w_min), .maximum(w_max));
        fp_convert i_convert (.value(f_a), .narrow(w_narrow),
            .from_int(w_from_int), .to_int(w_to_int));
    end else begin : gen_no_float
        assign w_add = '0;
        assign w_sub = '0;
        assign w_mul = '0;
        assign w_fma = '0;
        assign w_eq = 1'b0;
        assign w_lt = 1'b0;
        assign w_min = '0;
        assign w_max = '0;
        assign w_narrow = '0;
        assign w_from_int = '0;
        assign w_to_int = '0;
    end
    if (G_GROUP == -1 || G_GROUP == 3) begin : gen_divide
        bfloat_divider i_bfloat_divider (
            .clk(clk), .reset(reset), .flush(flush), .req_valid(d_div_req_valid),
            .req_a(f_a[15:0]), .req_b(f_b[15:0]), .resp_ready(d_div_resp_ready),
            .req_ready(w_div_req_ready), .resp_valid(w_div_resp_valid), .resp_data(w_div_result)
        );
    end else begin : gen_no_divide
        assign w_div_req_ready = 1'b0;
        assign w_div_resp_valid = 1'b0;
        assign w_div_result = '0;
    end
    if (G_GROUP == -1 || G_GROUP == 4) begin : gen_reduction
        logic w_unused_eq;
        logic w_unused_lt;
        logic [31:0] w_unused_min;
        fp_arithmetic #(.G_PRECISION(24), .G_OPERATION(0)) i_reduce_add (
            .clk(clk), .operand_a(f_reduction), .operand_b(f_a), .operand_c(32'd0),
            .result(w_reduce_add)
        );
        fp_compare #(.G_PRECISION(24)) i_reduce_max (
            .operand_a(f_reduction), .operand_b(f_a), .equal(w_unused_eq), .less(w_unused_lt),
            .minimum(w_unused_min), .maximum(w_reduce_max)
        );
    end else begin : gen_no_reduction
        assign w_reduce_add = '0;
        assign w_reduce_max = '0;
    end

    always_comb begin
        req_ready = f_state == ST_IDLE && !flush;
        resp_valid = f_state == ST_COMPLETE && !flush;
        resp = f_completion;
        busy = f_state != ST_IDLE;
        d_active = f_unmasked || f_mask[f_lane];
        d_comparison_op = f_op >= 6'd36 && f_op <= 6'd51;
        d_reduction_op = f_op == 6'd61 || f_op == 6'd62;
        d_divide = f_op == 6'd28 || f_op == 6'd29;
        d_div_req_valid = f_state == ST_RUN && d_divide && d_active && !f_div_sent;
        d_div_resp_ready = f_state == ST_RUN && d_divide;
        d_distance = f_op[0] ? {27'd0, f_rs1} : f_scalar;
        d_up_index = f_lane - d_distance[2:0];
        d_down_index = {1'b0, f_lane} + {1'b0, d_distance[2:0]};
        d_index_value = f_source1[32*int'(f_lane)+:32];
        d_value = 32'd0;
        d_comparison = 1'b0;
        d_lane_done = 1'b1;
        if (G_GROUP == -1 || G_GROUP == 0) begin
            case (f_op)
                6'd0, 6'd1: d_value = f_a + f_b;
                6'd2, 6'd3: d_value = f_a - f_b;
                6'd6, 6'd7: d_value = $signed(f_a) > $signed(f_b) ? f_a : f_b;
                6'd8, 6'd9: d_value = $signed(f_a) < $signed(f_b) ? f_a : f_b;
                6'd10, 6'd11: d_value = f_a & f_b;
                6'd12, 6'd13: d_value = f_a | f_b;
                6'd14, 6'd15: d_value = f_a ^ f_b;
                6'd16, 6'd17: d_value = f_a << f_b[4:0];
                6'd18, 6'd19: d_value = f_a >> f_b[4:0];
                6'd20, 6'd21: d_value = $signed(f_a) >>> f_b[4:0];
                6'd36, 6'd37: d_comparison = f_a == f_b;
                6'd38, 6'd39: d_comparison = $signed(f_a) < $signed(f_b);
                6'd40, 6'd41: d_comparison = $signed(f_a) <= $signed(f_b);
                6'd42, 6'd43: d_comparison = f_a < f_b;
                6'd44, 6'd45: d_comparison = f_a <= f_b;
                default: begin end
            endcase
        end
        if ((G_GROUP == -1 || G_GROUP == 1) && (f_op == 6'd4 || f_op == 6'd5))
            d_value = f_a * f_b;
        if ((G_GROUP == -1 || G_GROUP == 3) && d_divide) begin
            d_value = {16'd0, w_div_result};
            d_lane_done = !d_active || w_div_resp_valid;
        end
        if (G_GROUP == -1 || G_GROUP == 2) begin
            case (f_op)
                6'd22, 6'd23: begin
                    d_value = {16'd0, w_add};
                    d_lane_done = f_arithmetic_cycle == 3'd4;
                end
                6'd24, 6'd25: begin
                    d_value = {16'd0, w_sub};
                    d_lane_done = f_arithmetic_cycle == 3'd4;
                end
                6'd26, 6'd27: begin
                    d_value = {16'd0, w_mul};
                    d_lane_done = f_arithmetic_cycle == 3'd4;
                end
                6'd30, 6'd31: begin
                    d_value = {16'd0, w_fma};
                    d_lane_done = f_arithmetic_cycle == 3'd6;
                end
                6'd32, 6'd33: d_value = {16'd0, w_max};
                6'd34, 6'd35: d_value = {16'd0, w_min};
                6'd46, 6'd47: d_comparison = w_eq;
                6'd48, 6'd49: d_comparison = w_lt;
                6'd50, 6'd51: d_comparison = w_lt || w_eq;
                6'd58: begin
                    case (f_unary)
                        2'd0: d_value = {f_a[15:0], 16'd0};
                        2'd1: d_value = {16'd0, w_narrow};
                        2'd2: d_value = w_from_int;
                        2'd3: d_value = w_to_int;
                        default: begin end
                    endcase
                end
                default: begin end
            endcase
        end
        if (G_GROUP == -1 || G_GROUP == 4) begin
            case (f_op)
                6'd52, 6'd53: d_value = d_distance < 32'd8 && 32'(f_lane) >= d_distance
                    ? f_source2[32*int'(d_up_index)+:32] : 32'd0;
                6'd54, 6'd55: d_value = d_distance < 32'd8 && d_down_index < 4'd8
                    ? f_source2[32*int'(d_down_index[2:0])+:32] : 32'd0;
                6'd56: d_value = f_mask[f_lane] ? d_index_value : f_a;
                6'd57: d_value = d_index_value < 32'd8
                    ? f_source2[32*int'(d_index_value[2:0])+:32] : 32'd0;
                default: begin end
            endcase
            if (f_op == 6'd61) d_lane_done = f_arithmetic_cycle == 3'd4;
        end
        d_next_vector = f_accumulated;
        if (d_active || f_op == 6'd56) d_next_vector[32*int'(f_lane)+:32] = d_value;
        if (d_comparison_op) begin
            d_next_vector = f_accumulated;
            if (d_active) d_next_vector[{5'd0, f_lane}] = d_comparison;
        end
        d_next_reduction = f_reduction;
        if (d_active) d_next_reduction = f_op == 6'd61 ? w_reduce_add : w_reduce_max;
    end

    always_comb begin
        d_state = f_state;
        unique case (f_state)
            ST_IDLE: if (req_valid && !flush) d_state = ST_SELECT;
            ST_SELECT: d_state = ST_RUN;
            ST_RUN: if (d_lane_done) d_state = f_lane == 3'd7 ? ST_COMPLETE : ST_SELECT;
            ST_COMPLETE: if (resp_ready && !flush) d_state = ST_IDLE;
        endcase
        if (flush) d_state = ST_IDLE;
    end
    always_ff @(posedge clk) begin
        if (reset) begin
            f_state <= ST_IDLE;
            f_arithmetic_cycle <= '0;
            f_div_sent <= 1'b0;
        end else begin
            f_state <= d_state;
            if (f_state == ST_SELECT) f_arithmetic_cycle <= '0;
            if (f_state == ST_RUN) f_arithmetic_cycle <= f_arithmetic_cycle + 3'd1;
            if (d_div_req_valid && w_div_req_ready) f_div_sent <= 1'b1;
            if ((d_div_resp_ready && w_div_resp_valid) || flush) f_div_sent <= 1'b0;
        end
    end
    always_ff @(posedge clk) begin
        if (f_state == ST_SELECT) begin
            f_a <= d_active || f_op == 6'd56 ? f_source2[32*int'(f_lane)+:32] : 32'd0;
            f_b <= d_active ? (f_op[0] ? f_scalar : f_source1[32*int'(f_lane)+:32]) : 32'd0;
            f_old_lane <= d_active ? f_old[32*int'(f_lane)+:16] : 16'd0;
        end
        if (!flush) begin
            if (req_valid && req_ready) begin
                f_op <= req.uop.vec_op;
                f_unary <= req.uop.vec_unary;
                f_rs1 <= req.uop.rs1;
                f_unmasked <= req.uop.unmasked;
                f_pc <= req.uop.pc;
                f_tag <= req.uop.tag;
                f_seq <= req.uop.seq;
                f_source1 <= req.vsrc1.value;
                f_source2 <= req.vsrc2.value;
                f_old <= req.vold.value;
                f_scalar <= req.src1.value;
                f_mask <= req.vmask.value[7:0];
                f_accumulated <= req.uop.vec_op >= 6'd36 && req.uop.vec_op <= 6'd51
                    ? {248'd0, req.vold.value[7:0]} : req.vold.value;
                f_reduction <= req.vsrc1.value[31:0];
                f_lane <= '0;
            end
            if (f_state == ST_RUN && d_lane_done) begin
                f_accumulated <= d_next_vector;
                f_reduction <= d_next_reduction;
                if (f_lane == 3'd7) begin
                    f_completion <= '0;
                    f_completion.tag <= f_tag;
                    f_completion.seq <= f_seq;
                    f_completion.next_pc <= f_pc + 32'd4;
                    f_completion.data <= d_reduction_op ? {f_old[255:32], d_next_reduction}
                        : d_next_vector;
                end else f_lane <= f_lane + 3'd1;
            end
        end
    end
endmodule
`default_nettype wire
