module dma_tb;
    logic clk = 0;
    logic reset = 1;
    logic ack = 0;
    logic command_valid = 0;
    xj_pkg::issue_entry_t command;
    logic result_ready = 0;
    logic commit_valid = 0;
    xj_pkg::rob_tag_t commit;
    logic axi_ar_ready = 0;
    logic axi_aw_ready = 0;
    logic axi_w_ready = 0;
    logic axi_r_valid = 0;
    xj_pkg::axi_read_data_t axi_r;
    logic axi_b_valid = 0;
    xj_pkg::axi_write_response_t axi_b;
    logic local_req_ready = 0;
    logic local_resp_valid = 0;
    xj_pkg::word_resp_t local_resp;
    logic command_ready;
    logic result_valid;
    xj_pkg::exe_result_t result;
    logic axi_ar_valid;
    xj_pkg::axi_address_t axi_ar;
    logic axi_aw_valid;
    xj_pkg::axi_address_t axi_aw;
    logic axi_w_valid;
    xj_pkg::axi_write_data_t axi_w;
    logic axi_r_ready;
    logic axi_b_ready;
    logic local_req_valid;
    xj_pkg::word_req_t local_req;
    logic local_resp_ready;
    xj_pkg::dma_status_t status;
    logic busy;
    xj_pkg::exe_result_t fault;
    int cycle = 0;
    int read_beats = 0;
    int write_beats = 0;
    int commands = 0;
    int inject_error = 0;
    int model_read_index = 0;
    int model_write_index = 0;
    logic model_enable = 1;
    logic pending_b = 0;
    logic pending_local = 0;
    xj_pkg::word_resp_t pending_local_data;
    logic [31:0] model_address;
    logic saw_read_error = 0;
    logic saw_write_error = 0;
    logic saw_local_error = 0;
    dma_engine i_dut (.*);
    /* REFERENCE */

    task automatic check_outputs;
        #1;
        /* CHECKS */
        assert (result.data == '0) else $fatal(1, "DMA must not produce register data");
        if (axi_ar_valid || axi_aw_valid) begin
            assert (axi_ar.id == 0 && axi_aw.id == 0 && axi_ar.size == 2 &&
                axi_aw.size == 2 && axi_ar.burst == 1 && axi_aw.burst == 1 &&
                axi_ar.lock == 0 && axi_ar.cache == 0 && axi_ar.prot == 0 && axi_ar.qos == 0)
                else $fatal(1, "AXI address constants");
        end
        if (axi_w_valid) assert (axi_w.strb == 4'hf) else $fatal(1, "WSTRB");
        if (local_req_valid) assert (local_req.mask == 4'hf) else $fatal(1, "local mask");
    endtask

    task automatic tick;
        logic take_ar, take_aw, take_r, take_w, take_b, take_local, take_local_resp;
        xj_pkg::axi_address_t accepted_ar, accepted_aw;
        xj_pkg::word_req_t accepted_local;
        clk = 0;
        axi_ar_ready = $urandom_range(0, 3) != 0;
        axi_aw_ready = $urandom_range(0, 3) != 0;
        axi_w_ready = $urandom_range(0, 3) != 0;
        local_req_ready = $urandom_range(0, 3) != 0;
        if (model_enable) begin
            if (!axi_r_valid && read_beats > 0 && $urandom_range(0, 2) != 0) begin
                axi_r_valid = 1;
                axi_r.id = 0;
                axi_r.data = model_address ^ (32'h12345678 + 32'(model_read_index));
                axi_r.last = read_beats == 1;
                axi_r.resp = inject_error == 1 && model_read_index == 0 ? 2'd2 : 2'd0;
            end
            if (!axi_b_valid && pending_b && $urandom_range(0, 2) != 0) begin
                axi_b_valid = 1;
                axi_b.id = 0;
                axi_b.resp = inject_error == 2 ? 2'd2 : 2'd0;
            end
            if (!local_resp_valid && pending_local && $urandom_range(0, 2) != 0) begin
                local_resp_valid = 1;
                local_resp = pending_local_data;
            end
        end
        check_outputs();
        take_ar = axi_ar_valid && axi_ar_ready;
        take_aw = axi_aw_valid && axi_aw_ready;
        take_r = axi_r_valid && axi_r_ready;
        take_w = axi_w_valid && axi_w_ready;
        take_b = axi_b_valid && axi_b_ready;
        take_local = local_req_valid && local_req_ready;
        take_local_resp = local_resp_valid && local_resp_ready;
        accepted_ar = axi_ar;
        accepted_aw = axi_aw;
        accepted_local = local_req;
        if (take_r && axi_r.resp != 0) saw_read_error = 1;
        if (take_b && axi_b.resp != 0) saw_write_error = 1;
        if (take_local_resp && local_resp.error) saw_local_error = 1;
        clk = 1;
        check_outputs();
        clk = 0;
        if (take_ar) begin
            assert (read_beats == 0) else $fatal(1, "overlapping AR");
            read_beats = int'(accepted_ar.len) + 1;
            model_address = accepted_ar.addr;
            model_read_index = 0;
            assert (read_beats <= 64 &&
                int'(accepted_ar.addr[11:0]) + 4*read_beats <= 4096)
                else $fatal(1, "burst boundary");
        end
        if (take_aw) begin
            assert (write_beats == 0) else $fatal(1, "overlapping AW");
            write_beats = int'(accepted_aw.len) + 1;
            model_write_index = 0;
        end
        if (take_r) begin
            read_beats--;
            model_read_index++;
            axi_r_valid = 0;
        end
        if (take_w) begin
            assert (write_beats > 0) else $fatal(1, "W without AW");
            write_beats--;
            model_write_index++;
            if (write_beats == 0) pending_b = 1;
        end
        if (take_b) begin pending_b = 0; axi_b_valid = 0; end
        if (take_local) begin
            assert (!pending_local) else $fatal(1, "overlapping local request");
            pending_local = 1;
            pending_local_data.data = accepted_local.addr ^ 32'hc001d00d;
            pending_local_data.error = inject_error == 3;
        end
        if (take_local_resp) begin pending_local = 0; local_resp_valid = 0; end
        cycle++;
    endtask

    task automatic run_command(input xj_pkg::opcode_t operation,
        input logic [31:0] source_a, source_b, input logic expect_fault);
        int timeout_count;
        while (!command_ready) tick();
        command = '0;
        command.uop.op = operation;
        command.uop.pc = 32'(commands*4);
        command.uop.tag = 7'(commands);
        command.uop.seq = 16'(commands);
        command.src1.value = source_a;
        command.src2.value = source_b;
        command_valid = 1;
        tick();
        command_valid = 0;
        timeout_count = 0;
        while (!result_valid) begin
            tick();
            timeout_count++;
            if (timeout_count > 20000) $fatal(1, "DMA timeout op=%0d", operation);
        end
        assert (result.exception == expect_fault) else $fatal(1, "unexpected exception");
        repeat (3) tick();
        result_ready = 1;
        tick();
        result_ready = 0;
        if ((operation == xj_pkg::OP_DSETSIZE || operation == xj_pkg::OP_DSETSTRIDE)
            && !expect_fault) begin
            commit = ~command.uop.tag;
            commit_valid = 1;
            repeat (3) tick();
            assert (!command_ready) else $fatal(1, "wrong commit published config");
            commit = command.uop.tag;
            tick();
            commit_valid = 0;
        end
        commands++;
    endtask

    initial begin
        command = '0; commit = '0; axi_r = '0; axi_b = '0; local_resp = '0;
        tick(); tick(); reset = 0;
        run_command(xj_pkg::OP_MVIN, 32'h1000, 0, 1);
        run_command(xj_pkg::OP_DSETSIZE, 3, 1, 1);
        run_command(xj_pkg::OP_DSETSIZE, 4, 0, 1);
        run_command(xj_pkg::OP_DSETSTRIDE, 1, 4, 1);
        for (int trial = 0; trial < 45; trial++) begin
            logic [31:0] row_bytes;
            logic [31:0] rows;
            row_bytes = 4*$urandom_range(1, 150);
            rows = $urandom_range(1, 3);
            run_command(xj_pkg::OP_DSETSIZE, row_bytes, rows, 0);
            run_command(xj_pkg::OP_DSETSTRIDE, row_bytes+16, row_bytes+32, 0);
            run_command(xj_pkg::OP_MVIN, 32'hff0, 32'h100, 0);
            assert (status.bytes_done == row_bytes*rows && status.rows_done == rows)
                else $fatal(1, "inbound progress");
            run_command(xj_pkg::OP_MVOUT, 32'h1ffc, 32'h100, 0);
            assert (status.bytes_done == row_bytes*rows && status.rows_done == rows)
                else $fatal(1, "outbound progress");
        end
        run_command(xj_pkg::OP_MVIN, 32'hffffffff, 0, 1);
        run_command(xj_pkg::OP_MVIN, 32'hfffffffc, 0, 1);
        run_command(xj_pkg::OP_MVIN, 0, 32'hffffc, 1);
        inject_error = 1;
        run_command(xj_pkg::OP_MVIN, 32'h1000, 0, 1);
        assert (status.bytes_done == 0) else $fatal(1, "failed read burst published");
        inject_error = 2;
        run_command(xj_pkg::OP_MVOUT, 32'h1000, 0, 1);
        inject_error = 3;
        run_command(xj_pkg::OP_MVIN, 32'h1000, 0, 1);
        inject_error = 0;
        ack = 1; tick(); ack = 0;
        assert (!status.error && status.bytes_done == 0) else $fatal(1, "ACK progress");
        model_enable = 0;
        axi_r_valid = 1; axi_r = '0;
        tick(); axi_r_valid = 0;
        assert (status.poisoned && !command_ready && busy &&
            fault.cause == xj_pkg::C_FAULT_DMA_PROTOCOL) else $fatal(1, "unsolicited R");
        ack = 1; tick(); ack = 0;
        assert (status.poisoned) else $fatal(1, "ACK must not clear poison");
        assert (saw_read_error && saw_write_error && saw_local_error)
            else $fatal(1, "missing error coverage");
        $display("PASS DMA: %0d cycles, %0d commands", cycle, commands);
        $finish;
    end
endmodule
