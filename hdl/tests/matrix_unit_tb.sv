module matrix_unit_tb;
    logic clk = 0;
    logic reset = 1;
    logic flush = 0;
    logic task_valid = 0;
    xj_pkg::matrix_task_t task_data;
    logic commit_valid = 0;
    xj_pkg::rob_tag_t commit;
    logic config_valid = 0;
    xj_pkg::matrix_config_t config_data;
    logic mem_req_ready = 0;
    logic mem_resp_valid = 0;
    xj_pkg::matrix_beat_resp_t mem_resp;
    logic task_ready;
    logic config_ready;
    logic mem_req_valid;
    xj_pkg::matrix_beat_req_t mem_req;
    logic mem_resp_ready;
    logic done;
    logic state_fault;
    logic access_fault;
    logic busy;
    logic [2:0] unit_busy;
    int cycle = 0;
    int tasks = 0;
    logic pending = 0;
    logic inject_error = 0;
    xj_pkg::matrix_beat_resp_t saved_response;
    matrix_unit i_dut (.*);
    /* REFERENCE */

    task automatic check_outputs;
        #1;
        /* CHECKS */
        if (mem_req_valid) assert (mem_req.mask == '1) else $fatal(1, "matrix mask");
    endtask
    task automatic tick;
        logic take_request;
        logic take_response;
        clk = 0;
        mem_req_ready = $urandom_range(0, 3) != 0;
        mem_resp_valid = pending && $urandom_range(0, 3) != 0;
        mem_resp = saved_response;
        check_outputs();
        take_request = mem_req_valid && mem_req_ready;
        take_response = mem_resp_valid && mem_resp_ready;
        if (take_request) begin
            assert (!pending) else $fatal(1, "overlapping matrix request");
            saved_response.error = inject_error;
            for (int word = 0; word < 16; word++) saved_response.data[32*word+:32] = $urandom;
        end
        clk = 1;
        check_outputs();
        clk = 0;
        if (take_request) pending = 1;
        if (take_response) pending = 0;
        cycle++;
    endtask
    task automatic run_task(input logic [1:0] unit_id,
        input xj_pkg::mat_op_t operation, input logic [1:0] buffer_id,
        input logic expect_state_fault);
        int timeout_count;
        while (!task_ready) tick();
        task_data = '{tag:xj_pkg::rob_tag_t'(7'(tasks)), op:operation, unit:unit_id,
            buffer:buffer_id, addr:32'h100};
        task_valid = 1;
        tick(); task_valid = 0;
        timeout_count = 0;
        while (!done) begin
            tick(); timeout_count++;
            if (timeout_count > 2000) $fatal(1, "matrix timeout");
        end
        assert (state_fault == expect_state_fault && access_fault == inject_error)
            else $fatal(1, "matrix fault semantics");
        tick();
        assert (!busy && !task_ready) else $fatal(1, "await commit ownership");
        commit_valid = 1; commit = ~task_data.tag;
        repeat (2) tick();
        assert (!task_ready) else $fatal(1, "wrong commit accepted");
        commit = task_data.tag;
        tick(); commit_valid = 0;
        tasks++;
    endtask
    initial begin
        task_data = '0; config_data = '0; commit = '0; mem_resp = '0; saved_response = '0;
        tick(); tick(); reset = 0;
        for (int unit_id = 0; unit_id < 3; unit_id++) begin
            run_task(2'(unit_id), xj_pkg::MAT_MUL, 0, 1);
            for (int trial = 0; trial < 30; trial++) begin
                config_valid = 1;
                config_data = '{unit:2'(unit_id), mode:1'(trial)};
                tick(); config_valid = 0;
                run_task(2'(unit_id), xj_pkg::MAT_LOAD, 0, 0);
                run_task(2'(unit_id), xj_pkg::MAT_LOAD, 1, 0);
                run_task(2'(unit_id), xj_pkg::MAT_LOAD, 2, 0);
                run_task(2'(unit_id), xj_pkg::MAT_MUL, 0, 0);
                run_task(2'(unit_id), xj_pkg::MAT_STORE, 2, 0);
                run_task(2'(unit_id), xj_pkg::MAT_MACC, 0, 0);
                run_task(2'(unit_id), xj_pkg::MAT_STORE, 2, 0);
                run_task(2'(unit_id), xj_pkg::MAT_EW, 0, 0);
                run_task(2'(unit_id), xj_pkg::MAT_STORE, 2, 0);
                run_task(2'(unit_id), xj_pkg::MAT_TRANS, 0, 0);
                run_task(2'(unit_id), xj_pkg::MAT_STORE, 2, 0);
                run_task(2'(unit_id), xj_pkg::MAT_WAIT, 0, 0);
            end
            inject_error = 1;
            run_task(2'(unit_id), xj_pkg::MAT_LOAD, 0, 0);
            inject_error = 0;
            run_task(2'(unit_id), xj_pkg::MAT_STORE, 2, 0);
            task_data.op = xj_pkg::MAT_MUL;
            task_valid = 1; tick(); task_valid = 0;
            repeat (4) tick();
            flush = 1; tick(); flush = 0;
            run_task(2'(unit_id), xj_pkg::MAT_STORE, 2, 0);
        end
        $display("PASS matrix unit: %0d cycles, %0d tasks", cycle, tasks);
        $finish;
    end
endmodule
