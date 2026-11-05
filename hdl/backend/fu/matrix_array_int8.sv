`default_nettype none
module matrix_array_int8 (
    input  logic         clk,
    input  logic         reset,
    input  logic         flush,
    input  logic         load_beat_valid,
    input  logic [1:0]   load_buffer,
    input  logic [2:0]   load_beat,
    input  logic [511:0] load_data,
    /* verilator lint_off UNUSEDSIGNAL */
    input  logic [2:0]   store_beat,
    /* verilator lint_on UNUSEDSIGNAL */
    input  logic         start_valid,
    input  logic [3:0]   start_op,
    input  logic         mode,
    input  logic         publish_valid,
    input  logic [1:0]   publish_buffer,
    output logic [511:0] store_data,
    output logic         start_ready,
    output logic [2:0]   valid_buffers,
    output logic         busy
);
    typedef enum logic [2:0] {
        ST_IDLE = 3'd0,
        ST_PREPARE = 3'd1,
        ST_LOAD_WEIGHTS = 3'd2,
        ST_COMPUTE_OS = 3'd3,
        ST_COMPUTE_WS = 3'd4,
        ST_CAPTURE_WS = 3'd5,
        ST_ELEMENTWISE = 3'd6,
        ST_COMPLETE = 3'd7
    } state_t;
    localparam logic [3:0] C_MMUL = 4'd3;
    localparam logic [3:0] C_MMACC = 4'd4;
    localparam logic [3:0] C_MEW = 4'd5;
    state_t f_state;
    state_t d_state;
    logic [3:0] f_op;
    logic f_ws;
    logic [6:0] f_phase;
    logic [7:0] f_a [64];
    logic [7:0] f_b [64];
    logic [31:0] f_acc [64];
    logic [31:0] f_staging [64];
    logic [2:0] f_valid_buffers;
    logic [7:0] w_a_out [8][8];
    logic [7:0] w_b_out [8][8];
    logic w_a_valid [8][8];
    logic w_b_valid [8][8];
    logic [2:0] w_tag [8][8];
    logic [31:0] w_sum [8][8];
    logic w_sum_valid [8][8];
    logic [31:0] w_accumulator [8][8];
    logic [7:0] w_a_in [8][8];
    logic [7:0] w_b_in [8][8];
    logic w_a_in_valid [8][8];
    logic w_b_in_valid [8][8];
    logic [2:0] w_tag_in [8][8];
    logic [31:0] w_sum_in [8][8];
    logic w_sum_in_valid [8][8];
    logic w_move_a [8][8];
    logic w_move_b [8][8];
    logic w_mac_enable [8][8];
    logic w_use_accumulator [8][8];
    logic w_use_stored_b [8][8];

    for (genvar g_row = 0; g_row < 8; g_row++) begin : gen_row
        for (genvar g_column = 0; g_column < 8; g_column++) begin : gen_column
            systolic_pe_int8 i_systolic_pe_int8 (
                .clk(clk),
                .reset(reset),
                .clear_pipe(flush || f_state == ST_PREPARE),
                .move_a(w_move_a[g_row][g_column]),
                .move_b(w_move_b[g_row][g_column]),
                .a_in_valid(w_a_in_valid[g_row][g_column]),
                .b_in_valid(w_b_in_valid[g_row][g_column]),
                .a_in(w_a_in[g_row][g_column]),
                .b_in(w_b_in[g_row][g_column]),
                .tag_in(w_tag_in[g_row][g_column]),
                .sum_in(w_sum_in[g_row][g_column]),
                .mac_enable(w_mac_enable[g_row][g_column]),
                .use_accumulator(w_use_accumulator[g_row][g_column]),
                .use_stored_b(w_use_stored_b[g_row][g_column]),
                .load_accumulator(f_state == ST_PREPARE),
                .accumulator_in(f_op == C_MMACC ? f_acc[g_row*8+g_column] : 32'd0),
                .a_out(w_a_out[g_row][g_column]),
                .b_out(w_b_out[g_row][g_column]),
                .a_out_valid(w_a_valid[g_row][g_column]),
                .b_out_valid(w_b_valid[g_row][g_column]),
                .tag_out(w_tag[g_row][g_column]),
                .sum_out(w_sum[g_row][g_column]),
                .sum_out_valid(w_sum_valid[g_row][g_column]),
                .accumulator(w_accumulator[g_row][g_column])
            );
        end
    end

    always_comb begin
        start_ready = (f_state == ST_IDLE) && !flush;
        busy = (f_state != ST_IDLE);
        valid_buffers = f_valid_buffers;
        store_data = '0;
        for (int v_index = 0; v_index < 16; v_index++)
            store_data[32*v_index+:32] = f_acc[{store_beat[1:0], 4'(v_index)}];
        for (int v_row = 0; v_row < 8; v_row++) begin
            for (int v_column = 0; v_column < 8; v_column++) begin
                w_a_in[v_row][v_column] = v_column == 0
                    ? f_a[v_row*8 + int'(3'((f_phase - 7'(v_row))))]
                    : w_a_out[v_row][v_column == 0 ? 0 : v_column-1];
                w_a_in_valid[v_row][v_column] = v_column == 0
                    ? f_state == ST_COMPUTE_OS && f_phase >= 7'(v_row)
                        && f_phase <= 7'(v_row+7)
                    : w_a_valid[v_row][v_column == 0 ? 0 : v_column-1];
                w_b_in[v_row][v_column] = v_row == 0
                    ? f_b[3'((f_phase - 7'(v_column)))*8+v_column]
                    : w_b_out[v_row == 0 ? 0 : v_row-1][v_column];
                w_b_in_valid[v_row][v_column] = v_row == 0
                    ? f_state == ST_COMPUTE_OS && f_phase >= 7'(v_column)
                        && f_phase <= 7'(v_column+7)
                    : w_b_valid[v_row == 0 ? 0 : v_row-1][v_column];
                w_tag_in[v_row][v_column] = 3'd0;
                w_sum_in[v_row][v_column] = 32'd0;
                w_sum_in_valid[v_row][v_column] = 1'b0;
                w_move_a[v_row][v_column] = f_state == ST_COMPUTE_OS;
                w_move_b[v_row][v_column] = f_state == ST_COMPUTE_OS;
                w_mac_enable[v_row][v_column] = f_state == ST_COMPUTE_OS
                    && w_a_in_valid[v_row][v_column] && w_b_in_valid[v_row][v_column];
                w_use_accumulator[v_row][v_column] = f_state == ST_COMPUTE_OS;
                w_use_stored_b[v_row][v_column] = 1'b0;

                if (f_state == ST_LOAD_WEIGHTS) begin
                    w_b_in[v_row][v_column] = v_row == 0
                        ? f_b[(7 - int'(f_phase[2:0]))*8+v_column]
                        : w_b_out[v_row == 0 ? 0 : v_row-1][v_column];
                    w_b_in_valid[v_row][v_column] = v_row == 0
                        ? 1'b1 : w_b_valid[v_row == 0 ? 0 : v_row-1][v_column];
                    w_move_b[v_row][v_column] = 1'b1;
                end
                if (f_state == ST_COMPUTE_WS) begin
                    if (v_column == 0) begin
                        w_a_in[v_row][v_column] =
                            f_a[3'((f_phase - 7'(v_row)))*8+v_row];
                        w_a_in_valid[v_row][v_column] = f_phase >= 7'(v_row)
                            && f_phase < 7'(v_row+8);
                        w_tag_in[v_row][v_column] = 3'(f_phase - 7'(v_row));
                    end else begin
                        w_a_in[v_row][v_column] = w_a_out[v_row][v_column-1];
                        w_a_in_valid[v_row][v_column] = w_a_valid[v_row][v_column-1];
                        w_tag_in[v_row][v_column] = w_tag[v_row][v_column-1];
                    end
                    if (v_row == 0) begin
                        w_sum_in[v_row][v_column] = f_op == C_MMACC
                            ? f_acc[8*int'(w_tag_in[v_row][v_column])+v_column] : 32'd0;
                        w_sum_in_valid[v_row][v_column] = w_a_in_valid[v_row][v_column];
                    end else begin
                        w_sum_in[v_row][v_column] = w_sum[v_row-1][v_column];
                        w_sum_in_valid[v_row][v_column] = w_sum_valid[v_row-1][v_column];
                    end
                    w_move_a[v_row][v_column] = 1'b1;
                    w_mac_enable[v_row][v_column] = w_a_in_valid[v_row][v_column]
                        && w_sum_in_valid[v_row][v_column];
                    w_use_stored_b[v_row][v_column] = 1'b1;
                end
                if (f_state == ST_ELEMENTWISE) begin
                    if (f_op == C_MEW) begin
                        w_a_in[v_row][v_column] = f_a[v_row*8+v_column];
                        w_b_in[v_row][v_column] = f_b[v_row*8+v_column];
                        w_a_in_valid[v_row][v_column] = 1'b1;
                        w_b_in_valid[v_row][v_column] = 1'b1;
                        w_mac_enable[v_row][v_column] = f_phase == 7'd0;
                        w_use_accumulator[v_row][v_column] = 1'b1;
                    end
                end
            end
        end
    end

    always_comb begin
        d_state = f_state;
        unique case (f_state)
            ST_IDLE: if (start_valid && !flush) d_state = ST_PREPARE;
            ST_PREPARE: d_state = (f_op == C_MMUL || f_op == C_MMACC)
                ? (f_ws ? ST_LOAD_WEIGHTS : ST_COMPUTE_OS) : ST_ELEMENTWISE;
            ST_LOAD_WEIGHTS: if (f_phase == 7'd7) d_state = ST_COMPUTE_WS;
            ST_COMPUTE_OS: if (f_phase == 7'd21) d_state = ST_COMPLETE;
            ST_COMPUTE_WS: if (f_phase == 7'd21) d_state = ST_CAPTURE_WS;
            ST_CAPTURE_WS: d_state = ST_COMPLETE;
            ST_ELEMENTWISE: d_state = ST_COMPLETE;
            ST_COMPLETE: d_state = ST_IDLE;
        endcase
        if (flush) d_state = ST_IDLE;
    end

    always_ff @(posedge clk) begin
        if (reset) f_state <= ST_IDLE;
        else f_state <= d_state;
    end

    always_ff @(posedge clk) begin
        if (reset) begin
            f_valid_buffers <= '0;
            f_phase <= '0;
            f_ws <= 1'b0;
            f_op <= '0;
            for (int v_index = 0; v_index < 64; v_index++) begin
                f_a[v_index] <= '0;
                f_b[v_index] <= '0;
                f_acc[v_index] <= '0;
                f_staging[v_index] <= '0;
            end
        end else begin
            if (load_beat_valid && !flush) begin
                for (int v_index = 0; v_index < 64; v_index++) begin
                    if (load_buffer != 2'd2 && load_beat == 3'd0)
                        f_staging[v_index] <= {24'd0, load_data[8*v_index+:8]};
                    if (load_buffer == 2'd2 && load_beat == 3'(v_index/16))
                        f_staging[v_index] <= load_data[32*(v_index%16)+:32];
                end
            end
            if (f_state == ST_ELEMENTWISE && f_op != C_MEW) begin
                for (int v_row = 0; v_row < 8; v_row++)
                    for (int v_column = 0; v_column < 8; v_column++)
                        f_staging[8*v_row+v_column] <=
                            {{24{f_a[8*v_column+v_row][7]}}, f_a[8*v_column+v_row]};
            end
            if (f_state == ST_COMPUTE_WS || f_state == ST_CAPTURE_WS) begin
                for (int v_column = 0; v_column < 8; v_column++)
                    if (w_sum_valid[7][v_column])
                        f_staging[8*int'(w_tag[7][v_column])+v_column]
                            <= w_sum[7][v_column];
            end
            if (f_state == ST_COMPLETE && !flush && !f_ws &&
                (f_op == C_MMUL || f_op == C_MMACC || f_op == C_MEW)) begin
                for (int v_row = 0; v_row < 8; v_row++)
                    for (int v_column = 0; v_column < 8; v_column++)
                        f_staging[8*v_row+v_column] <= w_accumulator[v_row][v_column];
            end
            if (publish_valid && !flush) begin
                f_valid_buffers[publish_buffer] <= 1'b1;
                for (int v_index = 0; v_index < 64; v_index++) begin
                    if (publish_buffer == 2'd0) f_a[v_index] <= f_staging[v_index][7:0];
                    if (publish_buffer == 2'd1) f_b[v_index] <= f_staging[v_index][7:0];
                    if (publish_buffer == 2'd2) f_acc[v_index] <= f_staging[v_index];
                end
            end
            if (start_valid && start_ready) begin
                f_op <= start_op;
                f_ws <= mode && (start_op == C_MMUL || start_op == C_MMACC);
                f_phase <= 7'd0;
            end else if (f_state == ST_PREPARE ||
                (f_state == ST_LOAD_WEIGHTS && f_phase == 7'd7))
                f_phase <= 7'd0;
            else if (f_state == ST_LOAD_WEIGHTS || f_state == ST_COMPUTE_OS ||
                f_state == ST_COMPUTE_WS) begin
                if (d_state == f_state) f_phase <= f_phase + 7'd1;
            end
        end
    end
endmodule
`default_nettype wire
