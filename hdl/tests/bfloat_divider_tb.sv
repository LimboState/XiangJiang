module bfloat_divider_tb;
    logic clk;
    logic reset;
    logic flush;
    logic req_valid;
    logic [15:0] req_a;
    logic [15:0] req_b;
    logic req_ready;
    logic resp_ready;
    logic resp_valid;
    logic [15:0] resp_data;
    logic ref_req_ready;
    logic ref_resp_valid;
    logic [15:0] ref_resp_data;
    int cycle;
    int completions;

    bfloat_divider i_dut (
        .clk(clk), .reset(reset), .flush(flush), .req_valid(req_valid),
        .req_a(req_a), .req_b(req_b), .resp_ready(resp_ready),
        .req_ready(req_ready), .resp_valid(resp_valid), .resp_data(resp_data)
    );
    BFloatDivider i_reference (
        .clock(clk), .reset(reset), .io_flush(flush),
        .io_req_valid(req_valid), .io_req_bits_a(req_a), .io_req_bits_b(req_b),
        .io_req_ready(ref_req_ready), .io_resp_ready(resp_ready),
        .io_resp_valid(ref_resp_valid), .io_resp_bits(ref_resp_data)
    );

    task automatic compare_outputs;
        #1;
        assert (req_ready === ref_req_ready) else $fatal(1, "req_ready cycle=%0d", cycle);
        assert (resp_valid === ref_resp_valid) else $fatal(1, "resp_valid cycle=%0d", cycle);
        if (resp_valid) begin
            assert (resp_data === ref_resp_data)
                else $fatal(1, "cycle=%0d a/b result dut=%h ref=%h",
                    cycle, resp_data, ref_resp_data);
        end
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        flush = 1'b0;
        req_valid = 1'b0;
        resp_ready = 1'b0;
        req_a = '0;
        req_b = '0;
        completions = 0;
        for (cycle = 0; cycle < 60000; cycle++) begin
            clk = 1'b0;
            reset = (cycle < 2 || cycle % 16000 == 0);
            flush = (cycle > 2 && $urandom_range(0, 91) == 0);
            req_valid = 1'($urandom);
            resp_ready = 1'($urandom);
            req_a = 16'($urandom);
            req_b = 16'($urandom);
            case (cycle % 51)
                0: begin req_a = 16'h0000; req_b = 16'h0000; end
                1: begin req_a = 16'h7f80; req_b = 16'h7f80; end
                2: begin req_a = 16'h7fc1; req_b = 16'h3f80; end
                3: begin req_a = 16'h3f80; req_b = 16'h0000; end
                4: begin req_a = 16'h8001; req_b = 16'h3f00; end
                5: begin req_a = 16'h0080; req_b = 16'h7f80; end
                6: begin req_a = 16'h7f7f; req_b = 16'h0001; end
                7: begin req_a = 16'h3f80; req_b = 16'h4040; end
                default: begin end
            endcase
            compare_outputs();
            if (resp_valid && resp_ready) completions++;
            clk = 1'b1;
            compare_outputs();
        end
        assert (completions > 300) else $fatal(1, "too few completions");
        $display("PASS bfloat divider: %0d cycles, %0d completions", cycle, completions);
        $finish;
    end
endmodule
