`default_nettype none
module matrix_array_bf16 (
    input logic clk,
    input logic reset,
    input logic flush,
    input logic load_beat_valid,
    input logic [1:0] load_buffer,
    input logic [2:0] load_beat,
    input logic [511:0] load_data,
    // A BF16 tile has two beats; the common three-bit index uses only bit zero.
    /* verilator lint_off UNUSEDSIGNAL */
    input logic [2:0] store_beat,
    /* verilator lint_on UNUSEDSIGNAL */
    input logic start_valid,
    input logic [3:0] start_op,
    input logic publish_valid,
    input logic [1:0] publish_buffer,
    output logic [511:0] store_data,
    output logic start_ready,
    output logic [2:0] valid_buffers,
    output logic busy
);
    typedef enum logic [2:0] {
        ST_IDLE = 3'd0, ST_PREPARE = 3'd1, ST_COMPUTE = 3'd2,
        ST_ELEMENTWISE = 3'd3, ST_COMPLETE = 3'd4
    } state_t;
    state_t f_state;
    state_t d_state;
    logic [3:0] f_op;
    logic [6:0] f_phase;
    logic [15:0] f_a [64];
    logic [15:0] f_b [64];
    logic [31:0] f_acc [64];
    logic [31:0] f_staging [64];
    logic [2:0] f_valid;
    logic [15:0] w_a [8][8];
    logic [15:0] w_b [8][8];
    logic w_a_valid [8][8];
    logic w_b_valid [8][8];
    logic [31:0] w_acc [8][8];
    logic [15:0] d_a [8][8];
    logic [15:0] d_b [8][8];
    logic d_a_valid [8][8];
    logic d_b_valid [8][8];
    logic [15:0] w_narrow_result [64];
    for (genvar g_row = 0; g_row < 8; g_row++) begin : gen_row
        for (genvar g_column = 0; g_column < 8; g_column++) begin : gen_column
            systolic_pe_bf16 i_systolic_pe_bf16 (
                .clk(clk), .reset(reset), .clear_pipe(flush || f_state == ST_PREPARE),
                .move_a(f_state == ST_COMPUTE), .move_b(f_state == ST_COMPUTE),
                .a_in_valid(d_a_valid[g_row][g_column]),
                .b_in_valid(d_b_valid[g_row][g_column]),
                .a_in(d_a[g_row][g_column]), .b_in(d_b[g_row][g_column]),
                .mac_enable((f_state == ST_COMPUTE && d_a_valid[g_row][g_column]
                    && d_b_valid[g_row][g_column]) ||
                    (f_state == ST_ELEMENTWISE && f_op == 4'd5 && f_phase == 7'd0)),
                .load_accumulator(f_state == ST_PREPARE),
                .accumulator_in(f_op == 4'd4 ? f_acc[g_row*8+g_column] : 32'd0),
                .a_out(w_a[g_row][g_column]), .b_out(w_b[g_row][g_column]),
                .a_out_valid(w_a_valid[g_row][g_column]),
                .b_out_valid(w_b_valid[g_row][g_column]), .accumulator(w_acc[g_row][g_column])
            );
        end
    end
    // Narrow all committed accumulator elements before the beat select. This
    // keeps store_beat out of the FP32 classification/rounding cone; the
    // timing path to DTCM contains only a 2:1 mux after each narrow result.
    for (genvar g_index = 0; g_index < 64; g_index++) begin : gen_narrow
        bf16_narrow_fast i_bf16_narrow_fast (
            .value(f_acc[g_index]),
            .result(w_narrow_result[g_index])
        );
    end
    for (genvar g_index = 0; g_index < 32; g_index++) begin : gen_store_word
        assign store_data[16*g_index+:16] = store_beat[0]
            ? w_narrow_result[g_index+32] : w_narrow_result[g_index];
    end
    always_comb begin
        start_ready = f_state == ST_IDLE && !flush;
        busy = f_state != ST_IDLE;
        valid_buffers = f_valid;
        for (int v_row = 0; v_row < 8; v_row++) begin
            for (int v_column = 0; v_column < 8; v_column++) begin
                logic [5:0] v_a_offset;
                logic [5:0] v_b_offset;
                v_a_offset = 6'(f_phase - 7'(v_row));
                v_b_offset = 6'(f_phase - 7'(v_column));
                d_a[v_row][v_column] = v_column == 0
                    ? f_a[v_row*8+int'(v_a_offset[5:3])]
                    : w_a[v_row][v_column == 0 ? 0 : v_column-1];
                d_b[v_row][v_column] = v_row == 0
                    ? f_b[int'(v_b_offset[5:3])*8+v_column]
                    : w_b[v_row == 0 ? 0 : v_row-1][v_column];
                d_a_valid[v_row][v_column] = v_column == 0
                    ? f_state == ST_COMPUTE && f_phase >= 7'(v_row)
                        && f_phase <= 7'(v_row+56) && v_a_offset[2:0] == 3'd0
                    : w_a_valid[v_row][v_column == 0 ? 0 : v_column-1];
                d_b_valid[v_row][v_column] = v_row == 0
                    ? f_state == ST_COMPUTE && f_phase >= 7'(v_column)
                        && f_phase <= 7'(v_column+56) && v_b_offset[2:0] == 3'd0
                    : w_b_valid[v_row == 0 ? 0 : v_row-1][v_column];
                if (f_state == ST_ELEMENTWISE && f_op == 4'd5) begin
                    d_a[v_row][v_column] = f_a[v_row*8+v_column];
                    d_b[v_row][v_column] = f_b[v_row*8+v_column];
                    d_a_valid[v_row][v_column] = 1'b1;
                    d_b_valid[v_row][v_column] = 1'b1;
                end
            end
        end
    end
    always_comb begin
        d_state = f_state;
        unique case (f_state)
            ST_IDLE: if (start_valid && !flush) d_state = ST_PREPARE;
            ST_PREPARE: d_state = f_op == 4'd3 || f_op == 4'd4 ? ST_COMPUTE : ST_ELEMENTWISE;
            ST_COMPUTE: if (f_phase == 7'd76) d_state = ST_COMPLETE;
            ST_ELEMENTWISE: if (f_phase == 7'd6) d_state = ST_COMPLETE;
            ST_COMPLETE: d_state = ST_IDLE;
        endcase
        if (flush) d_state = ST_IDLE;
    end
    always_ff @(posedge clk) begin
        if (reset) f_state <= ST_IDLE;
        else f_state <= d_state;
    end
    always_ff @(posedge clk) begin
        if (start_valid && start_ready) f_op <= start_op;
        if (reset) begin
            f_phase <= '0;
            f_valid <= '0;
            for (int v_index = 0; v_index < 64; v_index++) begin
                f_a[v_index] <= '0;
                f_b[v_index] <= '0;
                f_acc[v_index] <= '0;
                f_staging[v_index] <= '0;
            end
        end else begin
            if ((start_valid && start_ready) || f_state == ST_PREPARE) f_phase <= '0;
            if (f_state == ST_COMPUTE && f_phase != 7'd76) f_phase <= f_phase + 7'd1;
            if (f_state == ST_ELEMENTWISE && f_phase != 7'd6) f_phase <= f_phase + 7'd1;
            if (load_beat_valid && !flush) begin
                for (int v_index = 0; v_index < 64; v_index++)
                    if (load_beat == 3'(v_index/32))
                        f_staging[v_index] <= load_buffer == 2'd2
                            ? {load_data[16*(v_index%32)+:16], 16'd0}
                            : {16'd0, load_data[16*(v_index%32)+:16]};
            end
            if (f_state == ST_ELEMENTWISE && f_op != 4'd5) begin
                for (int v_row = 0; v_row < 8; v_row++)
                    for (int v_column = 0; v_column < 8; v_column++)
                        f_staging[8*v_row+v_column] <= {f_a[8*v_column+v_row], 16'd0};
            end
            if (f_state == ST_COMPLETE && !flush &&
                (f_op == 4'd3 || f_op == 4'd4 || f_op == 4'd5)) begin
                for (int v_row = 0; v_row < 8; v_row++)
                    for (int v_column = 0; v_column < 8; v_column++)
                        f_staging[8*v_row+v_column] <= w_acc[v_row][v_column];
            end
            if (publish_valid && !flush) begin
                f_valid[publish_buffer] <= 1'b1;
                for (int v_index = 0; v_index < 64; v_index++) begin
                    if (publish_buffer == 2'd0) f_a[v_index] <= f_staging[v_index][15:0];
                    if (publish_buffer == 2'd1) f_b[v_index] <= f_staging[v_index][15:0];
                    if (publish_buffer == 2'd2) f_acc[v_index] <= f_staging[v_index];
                end
            end
        end
    end
endmodule
`default_nettype wire
