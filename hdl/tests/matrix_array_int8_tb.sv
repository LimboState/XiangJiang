module matrix_array_int8_tb;
    logic clk;
    logic reset;
    logic flush;
    logic load_beat_valid;
    logic [1:0] load_buffer;
    logic [2:0] load_beat;
    logic [511:0] load_data;
    logic [2:0] store_beat;
    logic start_valid;
    logic [3:0] start_op;
    logic mode;
    logic publish_valid;
    logic [1:0] publish_buffer;
    logic [511:0] store_data;
    logic start_ready;
    logic [2:0] valid_buffers;
    logic busy;
    logic [511:0] ref_store_data;
    logic ref_start_ready;
    logic ref_valid_a;
    logic ref_valid_b;
    logic ref_valid_acc;
    logic ref_busy;
    int cycle;
    int completed;

    matrix_array_int8 i_dut (.*);
    MatrixArray_1 i_reference (
        .clock(clk), .reset(reset), .io_flush(flush),
        .io_loadBeat_valid(load_beat_valid), .io_loadBeat_bits_buffer(load_buffer),
        .io_loadBeat_bits_beat(load_beat), .io_loadBeat_bits_data(load_data),
        .io_storeBeat(store_beat), .io_storeData(ref_store_data),
        .io_start_ready(ref_start_ready), .io_start_valid(start_valid),
        .io_start_bits_op(start_op), .io_mode(mode),
        .io_publish_valid(publish_valid), .io_publish_bits(publish_buffer),
        .io_validBuffers_0(ref_valid_a), .io_validBuffers_1(ref_valid_b),
        .io_validBuffers_2(ref_valid_acc), .io_busy(ref_busy)
    );

    task automatic compare_outputs;
        #1;
        assert (start_ready === ref_start_ready && busy === ref_busy &&
            valid_buffers === {ref_valid_acc, ref_valid_b, ref_valid_a})
            else $fatal(1, "matrix control cycle=%0d", cycle);
        assert (store_data === ref_store_data)
            else $fatal(1, "matrix data cycle=%0d beat=%0d dut=%h ref=%h",
                cycle, store_beat, store_data, ref_store_data);
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        flush = 1'b0;
        load_beat_valid = 1'b0;
        load_buffer = 2'd0;
        load_beat = 3'd0;
        load_data = '0;
        store_beat = 3'd0;
        start_valid = 1'b0;
        start_op = 4'd3;
        mode = 1'b0;
        publish_valid = 1'b0;
        publish_buffer = 2'd0;
        completed = 0;
        for (cycle = 0; cycle < 11000; cycle++) begin
            clk = 1'b0;
            reset = cycle < 2 || cycle % 2800 == 0;
            flush = cycle > 2 && cycle % 907 == 0;
            load_beat_valid = $urandom_range(0, 2) != 0;
            load_buffer = 2'($urandom_range(0, 2));
            load_beat = 3'($urandom_range(0, 3));
            for (int word = 0; word < 16; word++) load_data[32*word+:32] = $urandom;
            store_beat = 3'($urandom_range(0, 3));
            start_valid = $urandom_range(0, 5) == 0;
            case ($urandom_range(0, 3))
                0: start_op = 4'd3;
                1: start_op = 4'd4;
                2: start_op = 4'd5;
                3: start_op = 4'd6;
                default: start_op = 4'd3;
            endcase
            mode = 1'($urandom);
            publish_valid = !busy && $urandom_range(0, 2) == 0;
            publish_buffer = 2'($urandom_range(0, 2));
            compare_outputs();
            if (busy && !ref_busy) $fatal(1, "reference lost busy");
            if (start_valid && start_ready) completed++;
            clk = 1'b1;
            compare_outputs();
        end
        assert (completed > 20) else $fatal(1, "insufficient operations");
        $display("PASS INT8 matrix array: %0d cycles, %0d operations", cycle, completed);
        $finish;
    end
endmodule
