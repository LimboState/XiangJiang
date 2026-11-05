module memory_subsystem_tb;
    logic clk = 0;
    logic reset = 1;
    logic core_busy = 0;
    logic bus_poisoned = 0;
    logic axi_aw_valid = 0;
    xj_pkg::axi_address_t axi_aw;
    xj_pkg::axi_address_t axi_ar;
    logic axi_w_valid = 0;
    xj_pkg::axi_write_data_t axi_w;
    logic axi_b_ready = 0;
    logic axi_ar_valid = 0;
    logic axi_r_ready = 0;
    logic fetch_req_valid = 0;
    logic [31:0] fetch_req = 0;
    logic data_req_valid = 0;
    xj_pkg::word_req_t data_req;
    logic data_resp_ready = 0;
    logic matrix_req_valid = 0;
    xj_pkg::matrix_beat_req_t matrix_req;
    logic matrix_resp_ready = 0;
    logic axi_aw_ready;
    logic axi_w_ready;
    logic axi_b_valid;
    xj_pkg::axi_write_response_t axi_b;
    logic axi_ar_ready;
    logic axi_r_valid;
    xj_pkg::axi_read_data_t axi_r;
    logic fetch_req_ready;
    logic fetch_resp_valid;
    xj_pkg::fetch_packet_t fetch_resp;
    logic data_req_ready;
    logic data_resp_valid;
    xj_pkg::word_resp_t data_resp;
    logic matrix_req_ready;
    logic matrix_resp_valid;
    xj_pkg::matrix_beat_resp_t matrix_resp;
    logic host_idle;
    int cycle = 0;
    int b_responses = 0;
    int r_responses = 0;
    int data_responses = 0;
    int matrix_responses = 0;
    memory_subsystem i_dut (.*);
    /* REFERENCE */

    task automatic check_outputs;
        #1;
        /* CHECKS */
        assert (fetch_req_ready) else $fatal(1, "fetch always ready");
    endtask

    task automatic tick;
        clk = 0;
        check_outputs();
        if (axi_b_valid && axi_b_ready) b_responses++;
        if (axi_r_valid && axi_r_ready) r_responses++;
        if (data_resp_valid && data_resp_ready) data_responses++;
        if (matrix_resp_valid && matrix_resp_ready) matrix_responses++;
        clk = 1;
        check_outputs();
        clk = 0;
        cycle++;
    endtask

    task automatic host_write(input logic [31:0] address, value, input logic w_first);
        axi_aw = '0;
        axi_aw.addr = address;
        axi_aw.size = 2;
        axi_aw.burst = 1;
        axi_w = '{data:value, strb:4'hf, last:1'b1};
        if (w_first) begin
            axi_w_valid = 1; tick(); axi_w_valid = 0;
            repeat (2) tick();
            axi_aw_valid = 1; tick(); axi_aw_valid = 0;
        end else begin
            axi_aw_valid = 1; tick(); axi_aw_valid = 0;
            repeat (2) tick();
            axi_w_valid = 1; tick(); axi_w_valid = 0;
        end
        while (!axi_b_valid) tick();
        assert (axi_b.resp == 0) else $fatal(1, "initialization write failed");
        axi_b_ready = 1; tick(); axi_b_ready = 0;
    endtask

    initial begin
        axi_aw = '0; axi_ar = '0; axi_w = '0; data_req = '0; matrix_req = '0;
        tick(); tick(); reset = 0;
        for (int word = 0; word < 64; word++) begin
            host_write(32'(word*4), 32'h10203040 + 32'(word), 1'(word));
            host_write(32'h10000000 + 32'(word*4), 32'h50607080 + 32'(word), !1'(word));
        end
        for (int iteration = 0; iteration < 35000; iteration++) begin
            logic use_scalar;
            reset = iteration > 0 && iteration % 7000 == 0;
            core_busy = $urandom_range(0, 9) == 0;
            bus_poisoned = $urandom_range(0, 61) == 0;
            axi_aw_valid = $urandom_range(0, 3) == 0;
            axi_aw.id = 4'($urandom);
            axi_aw.addr = 32'($urandom_range(0, 63))*4
                + ($urandom_range(0, 1) == 0 ? 32'h10000000 : 32'd0);
            axi_aw.size = 3'($urandom_range(0, 2));
            axi_aw.len = 8'($urandom_range(0, 4));
            axi_aw.burst = 2'($urandom_range(0, 2));
            axi_aw.lock = iteration % 31 == 0;
            if (iteration % 17 == 0) axi_aw.addr = 32'hfffffffc;
            if (iteration % 19 == 0) axi_aw.addr = 32'h00000ffc;
            if (iteration % 23 == 0) axi_aw.addr[1:0] = 2'd1;
            axi_ar_valid = $urandom_range(0, 3) == 0;
            axi_ar = axi_aw;
            axi_ar.addr = 32'($urandom_range(0, 63))*4
                + ($urandom_range(0, 1) == 0 ? 32'h10000000 : 32'd0);
            if (iteration % 13 == 0) axi_ar.addr = 32'h100ffffc;
            axi_ar.id = 4'($urandom);
            axi_w_valid = $urandom_range(0, 2) != 0;
            axi_w.data = $urandom;
            axi_w.strb = 4'($urandom);
            axi_w.last = 1'($urandom);
            axi_b_ready = $urandom_range(0, 2) != 0;
            axi_r_ready = $urandom_range(0, 2) != 0;
            fetch_req_valid = $urandom_range(0, 3) == 0;
            fetch_req = 32'($urandom_range(0, 63))*4;
            if (iteration % 19 == 0) fetch_req = 32'h40000;
            if (iteration % 23 == 0) fetch_req = 32'd3;
            use_scalar = 1'($urandom);
            data_req_valid = use_scalar && $urandom_range(0, 3) == 0;
            data_req.addr = 32'($urandom_range(0, 63))*4;
            data_req.data = $urandom;
            data_req.write = 1'($urandom);
            data_req.mask = 4'hf;
            if (iteration % 29 == 0) data_req.addr = 32'h100000;
            if (iteration % 31 == 0) data_req.addr[1:0] = 2'd2;
            data_resp_ready = $urandom_range(0, 3) != 0;
            matrix_req_valid = !use_scalar && $urandom_range(0, 3) == 0;
            matrix_req.addr = 32'($urandom_range(0, 3))*64;
            matrix_req.write = 1'($urandom);
            matrix_req.mask = '1;
            for (int word = 0; word < 16; word++) matrix_req.data[32*word+:32] = $urandom;
            if (iteration % 29 == 0) matrix_req.addr = 32'h100000;
            if (iteration % 31 == 0) matrix_req.addr[5:0] = 6'd4;
            matrix_resp_ready = $urandom_range(0, 3) != 0;
            tick();
        end
        assert (b_responses > 200 && r_responses > 200 &&
            data_responses > 200 && matrix_responses > 200) else $fatal(1, "coverage");
        $display("PASS memory subsystem: %0d cycles, B=%0d R=%0d data=%0d matrix=%0d", cycle,
            b_responses, r_responses, data_responses, matrix_responses);
        $finish;
    end
endmodule
