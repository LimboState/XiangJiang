module vector_tb;
    localparam int C_GROUP = GROUP_VALUE;
    logic clk = 0;
    logic reset = 1;
    logic flush = 0;
    logic req_valid = 0;
    xj_pkg::issue_entry_t req;
    logic resp_ready = 0;
    logic req_ready;
    logic resp_valid;
    xj_pkg::exe_result_t resp;
    logic busy;
    int cycle;
    int completions = 0;
    vector_unit #(.G_GROUP(C_GROUP)) i_dut (.*);
    /* REFERENCE */
    task automatic check_outputs;
        #1;
        /* CHECKS */
        if (resp_valid) assert (!resp.exception && resp.cause == 0 && resp.bad_addr == 0
            && resp.detail == 0) else $fatal(1, "vector exception outputs");
    endtask
    initial begin
        req = '0;
        for (cycle = 0; cycle < 120000; cycle++) begin
            clk = 0;
            reset = cycle < 2 || cycle % 14000 == 0;
            flush = cycle > 2 && cycle % 877 == 0;
            req_valid = $urandom_range(0, 2) != 0;
            resp_ready = $urandom_range(0, 3) != 0;
            req.uop.pc = $urandom;
            req.uop.tag = 7'($urandom);
            req.uop.seq = 16'($urandom);
            req.uop.vec_op = 6'($urandom);
            req.uop.vec_unary = 2'($urandom);
            req.uop.rs1 = 5'($urandom);
            req.uop.unmasked = 1'($urandom);
            req.src1.value = $urandom;
            if (cycle % 3 == 0) req.src1.value = $urandom_range(0, 9);
            for (int lane = 0; lane < 8; lane++) begin
                req.vsrc1.value[32*lane+:32] = $urandom;
                req.vsrc2.value[32*lane+:32] = $urandom;
                req.vold.value[32*lane+:32] = $urandom;
            end
            req.vmask.value = 256'($urandom);
            if (C_GROUP == 2) begin
                req.uop.vec_op = 6'($urandom_range(22, 58));
                case (cycle % 9)
                    0: begin req.vsrc1.value = {8{32'h00000000}};
                        req.vsrc2.value = {8{32'h80000000}}; end
                    1: begin req.vsrc1.value = {8{32'h00007f80}};
                        req.vsrc2.value = {8{32'h0000ff80}}; end
                    2: begin req.vsrc1.value = {8{32'h00000001}};
                        req.vsrc2.value = {8{32'h00000080}}; end
                    3: begin req.vsrc1.value = {8{32'h00003f80}};
                        req.vsrc2.value = {8{32'h00003f80}}; end
                    default: begin end
                endcase
            end
            if (C_GROUP == 3) req.uop.vec_op = 6'd28 + 6'($urandom_range(0, 1));
            if (C_GROUP == 4) req.uop.vec_op = 6'($urandom_range(52, 62));
            check_outputs();
            if (resp_valid && resp_ready) completions++;
            clk = 1;
            check_outputs();
        end
        assert (completions > 100) else $fatal(1, "insufficient vector coverage");
        $display("PASS vector group %0d: %0d cycles, %0d completions", C_GROUP, cycle, completions);
        $finish;
    end
endmodule
