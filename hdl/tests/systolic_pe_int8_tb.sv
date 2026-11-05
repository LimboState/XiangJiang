module systolic_pe_int8_tb;
    logic clk;
    logic reset;
    logic clear_pipe;
    logic move_a;
    logic move_b;
    logic a_in_valid;
    logic b_in_valid;
    logic [7:0] a_in;
    logic [7:0] b_in;
    logic [2:0] tag_in;
    logic [31:0] sum_in;
    logic mac_enable;
    logic use_accumulator;
    logic use_stored_b;
    logic load_accumulator;
    logic [31:0] accumulator_in;
    logic [7:0] a_out;
    logic [7:0] b_out;
    logic a_out_valid;
    logic b_out_valid;
    logic [2:0] tag_out;
    logic [31:0] sum_out;
    logic sum_out_valid;
    logic [31:0] accumulator;
    logic [7:0] ref_a_out;
    logic [7:0] ref_b_out;
    logic ref_a_valid;
    logic ref_b_valid;
    logic [2:0] ref_tag;
    logic [31:0] ref_sum;
    logic ref_sum_valid;
    logic [31:0] ref_accumulator;
    int cycle;

    systolic_pe_int8 i_dut (.*);
    SystolicPE_64 i_reference (
        .clock(clk), .reset(reset), .io_aIn(a_in), .io_bIn(b_in),
        .io_aInValid(a_in_valid), .io_bInValid(b_in_valid),
        .io_tagIn(tag_in), .io_moveA(move_a), .io_moveB(move_b),
        .io_clearPipe(clear_pipe), .io_sumIn(sum_in), .io_macEnable(mac_enable),
        .io_useAccumulator(use_accumulator), .io_useStoredB(use_stored_b),
        .io_loadAccumulator(load_accumulator), .io_accumulatorIn(accumulator_in),
        .io_aOut(ref_a_out), .io_bOut(ref_b_out), .io_aOutValid(ref_a_valid),
        .io_bOutValid(ref_b_valid), .io_tagOut(ref_tag), .io_sumOut(ref_sum),
        .io_sumOutValid(ref_sum_valid), .io_accumulator(ref_accumulator)
    );

    task automatic compare_outputs;
        #1;
        assert (a_out_valid === ref_a_valid && b_out_valid === ref_b_valid
            && sum_out_valid === ref_sum_valid && sum_out === ref_sum
            && accumulator === ref_accumulator)
            else $fatal(1, "PE status cycle=%0d", cycle);
        if (a_out_valid) assert (a_out === ref_a_out)
            else $fatal(1, "PE a cycle=%0d", cycle);
        if (b_out_valid) assert (b_out === ref_b_out)
            else $fatal(1, "PE b cycle=%0d", cycle);
        if (a_out_valid || sum_out_valid) assert (tag_out === ref_tag)
            else $fatal(1, "PE tag cycle=%0d", cycle);
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        clear_pipe = 1'b0;
        move_a = 1'b0;
        move_b = 1'b0;
        a_in_valid = 1'b0;
        b_in_valid = 1'b0;
        a_in = '0;
        b_in = '0;
        tag_in = '0;
        sum_in = '0;
        mac_enable = 1'b0;
        use_accumulator = 1'b0;
        use_stored_b = 1'b0;
        load_accumulator = 1'b0;
        accumulator_in = '0;
        for (cycle = 0; cycle < 50000; cycle++) begin
            clk = 1'b0;
            reset = cycle < 2 || cycle % 12000 == 0;
            clear_pipe = $urandom_range(0, 7) == 0;
            move_a = 1'($urandom);
            move_b = 1'($urandom);
            a_in_valid = 1'($urandom);
            b_in_valid = 1'($urandom);
            a_in = 8'($urandom);
            b_in = 8'($urandom);
            tag_in = 3'($urandom);
            sum_in = $urandom;
            mac_enable = 1'($urandom);
            use_accumulator = 1'($urandom);
            use_stored_b = 1'($urandom);
            load_accumulator = 1'($urandom);
            accumulator_in = $urandom;
            compare_outputs();
            clk = 1'b1;
            compare_outputs();
        end
        $display("PASS INT8 systolic PE: %0d cycles", cycle);
        $finish;
    end
endmodule
