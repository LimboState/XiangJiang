`default_nettype none
module bfloat_divider (
    input  logic        clk,
    input  logic        reset,
    input  logic        flush,
    input  logic        req_valid,
    input  logic [15:0] req_a,
    input  logic [15:0] req_b,
    input  logic        resp_ready,
    output logic        req_ready,
    output logic        resp_valid,
    output logic [15:0] resp_data
);
    typedef enum logic [1:0] {
        ST_IDLE = 2'd0,
        ST_DIVIDE = 2'd1,
        ST_RESULT = 2'd2
    } state_t;

    state_t f_state;
    state_t d_state;
    logic [15:0] f_a;
    logic [15:0] f_b;
    logic [19:0] f_numerator;
    logic [7:0] f_denominator;
    logic [11:0] f_quotient;
    logic [7:0] f_remainder;
    logic [4:0] f_count;
    logic [15:0] f_answer;
    logic [8:0] d_trial;
    logic d_take;
    logic [8:0] d_remainder;
    logic [12:0] d_quotient;

    function automatic logic [7:0] normalized_significand(input logic [14:0] v_value);
        logic [7:0] v_significand;
        logic [7:0] v_normalized;
        logic v_found;
        v_significand = {(|v_value[14:7]), v_value[6:0]};
        v_normalized = v_significand;
        v_found = 1'b0;
        for (int v_index = 7; v_index >= 0; v_index--) begin
            if (!v_found && v_significand[v_index]) begin
                v_normalized = v_significand << (7-v_index);
                v_found = 1'b1;
            end
        end
        return v_normalized;
    endfunction

    function automatic logic signed [11:0] unbiased_exponent(input logic [14:0] v_value);
        logic [7:0] v_significand;
        logic [3:0] v_leading;
        logic v_found;
        v_significand = {(|v_value[14:7]), v_value[6:0]};
        v_leading = 4'd7;
        v_found = 1'b0;
        for (int v_index = 7; v_index >= 0; v_index--) begin
            if (!v_found && v_significand[v_index]) begin
                v_leading = 4'(7-v_index);
                v_found = 1'b1;
            end
        end
        return $signed({4'd0, ((|v_value[14:7]) ? v_value[14:7] : 8'd1)})
            - 12'sd127 - $signed({8'd0, v_leading});
    endfunction

    function automatic logic [12:0] jam_shift(
        input logic [12:0] v_magnitude,
        input logic [11:0] v_distance
    );
        logic [3:0] v_shift;
        logic [12:0] v_shifted;
        v_shift = v_distance >= 12'd13 ? 4'd13 : v_distance[3:0];
        v_shifted = v_magnitude >> v_shift;
        return v_shifted | 13'(v_magnitude != (v_shifted << v_shift));
    endfunction

    function automatic logic [15:0] division_result(
        input logic [15:0] v_a,
        input logic [15:0] v_b,
        input logic [12:0] v_quotient,
        input logic [8:0] v_remainder
    );
        logic v_sign;
        logic v_a_nan;
        logic v_b_nan;
        logic v_a_inf;
        logic v_b_inf;
        logic v_a_zero;
        logic v_b_zero;
        logic [12:0] v_magnitude;
        logic [12:0] v_normalized;
        logic [10:0] v_shifted;
        logic [3:0] v_leading;
        logic v_found;
        logic signed [11:0] v_exponent;
        logic signed [11:0] v_effective_exponent;
        logic signed [11:0] v_final_exponent;
        logic [8:0] v_rounded;
        logic [7:0] v_biased;
        logic [7:0] v_exp_field;
        logic [6:0] v_fraction;

        v_sign = v_a[15] ^ v_b[15];
        v_a_nan = (&v_a[14:7]) && (|v_a[6:0]);
        v_b_nan = (&v_b[14:7]) && (|v_b[6:0]);
        v_a_inf = (&v_a[14:7]) && !(|v_a[6:0]);
        v_b_inf = (&v_b[14:7]) && !(|v_b[6:0]);
        v_a_zero = !(|v_a[14:7]) && !(|v_a[6:0]);
        v_b_zero = !(|v_b[14:7]) && !(|v_b[6:0]);
        v_magnitude = v_quotient | 13'(|v_remainder);
        v_leading = 4'd12;
        v_found = 1'b0;
        for (int v_index = 12; v_index >= 0; v_index--) begin
            if (!v_found && v_magnitude[v_index]) begin
                v_leading = 4'(12-v_index);
                v_found = 1'b1;
            end
        end
        v_normalized = v_magnitude << v_leading;
        v_exponent = unbiased_exponent(v_a[14:0]) - unbiased_exponent(v_b[14:0])
            - $signed({8'd0, v_leading});
        v_effective_exponent = v_exponent < -12'sd126 ? -12'sd126 : v_exponent;
        v_shifted = 11'(jam_shift(v_normalized, 12'd2
            + (v_exponent < -12'sd126 ? $unsigned(-12'sd126 - v_exponent) : 12'd0)));
        v_rounded = {1'b0, v_shifted[10:3]}
            + 9'(v_shifted[2] && ((|v_shifted[1:0]) || v_shifted[3]));
        v_final_exponent = v_effective_exponent + $signed({11'd0, v_rounded[8]});
        v_biased = 8'(v_final_exponent + 12'sd127);
        v_exp_field = (v_rounded[8] || v_rounded[7]) ? v_biased : 8'd0;
        v_fraction = v_rounded[8] ? v_rounded[7:1] : v_rounded[6:0];
        if (v_a_nan || v_b_nan || (v_a_zero && v_b_zero) || (v_a_inf && v_b_inf))
            return 16'h7fc0;
        if (v_a_inf || v_b_zero) return {v_sign, 15'h7f80};
        if (v_a_zero || v_b_inf || (v_magnitude == 13'd0)) return {v_sign, 15'd0};
        if (v_final_exponent > 12'sd127) return {v_sign, 15'h7f80};
        return {v_sign, v_exp_field, v_fraction};
    endfunction

    always_comb begin
        d_state = f_state;
        unique case (f_state)
            ST_IDLE: if (req_valid && !flush) d_state = ST_DIVIDE;
            ST_DIVIDE: if (f_count == 5'd19) d_state = ST_RESULT;
            ST_RESULT: if (resp_ready && !flush) d_state = ST_IDLE;
        endcase
        if (flush) d_state = ST_IDLE;
    end

    always_comb begin
        req_ready = (f_state == ST_IDLE) && !flush;
        resp_valid = (f_state == ST_RESULT) && !flush;
        resp_data = f_answer;
        d_trial = {f_remainder[7:0], f_numerator[19]};
        d_take = d_trial >= {1'b0, f_denominator};
        d_remainder = d_take ? d_trial - {1'b0, f_denominator} : d_trial;
        d_quotient = {f_quotient[11:0], d_take};
    end

    always_ff @(posedge clk) begin
        if (reset) f_state <= ST_IDLE;
        else f_state <= d_state;
    end

    always_ff @(posedge clk) begin
        if (req_valid && req_ready) begin
            f_a <= req_a;
            f_b <= req_b;
            f_numerator <= {normalized_significand(req_a[14:0]), 12'd0};
            f_denominator <= (req_b[14:0] == 15'd0) ? 8'd1
                : normalized_significand(req_b[14:0]);
            f_quotient <= 12'd0;
            f_remainder <= 8'd0;
            f_count <= 5'd0;
        end
        if (f_state == ST_DIVIDE) begin
            f_numerator <= {f_numerator[18:0], 1'b0};
            f_remainder <= d_remainder[7:0];
            f_quotient <= d_quotient[11:0];
            if (f_count == 5'd19)
                f_answer <= division_result(f_a, f_b, d_quotient, d_remainder);
            else f_count <= f_count + 5'd1;
        end
    end
endmodule
`default_nettype wire
