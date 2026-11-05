module lsu_tb;
    logic clk = 0;
    logic reset = 1;
    logic stop = 0;
    logic flush = 0;
    logic allocate_valid = 0;
    xj_pkg::issue_entry_t allocate;
    logic wakeup_valid = 0;
    xj_pkg::exe_result_t wakeup;
    logic head_valid = 0;
    xj_pkg::rob_tag_t head;
    logic request_ready = 0;
    logic response_valid = 0;
    xj_pkg::word_resp_t response;
    logic dma_task_ready = 0;
    logic dma_result_valid = 0;
    xj_pkg::exe_result_t dma_result;
    logic matrix_task_ready = 0;
    logic matrix_done = 0;
    logic matrix_state_fault = 0;
    logic matrix_access_fault = 0;
    logic result_ready = 0;
    logic allocate_ready;
    logic request_valid;
    xj_pkg::word_req_t request;
    logic response_ready;
    logic dma_task_valid;
    xj_pkg::issue_entry_t dma_task;
    logic dma_result_ready;
    logic matrix_task_valid;
    xj_pkg::matrix_task_t matrix_task;
    logic result_valid;
    xj_pkg::exe_result_t result;
    logic empty;
    logic quiescent;
    logic address_issued_valid;
    xj_pkg::rob_tag_t address_issued;
    int cycle;
    int allocations = 0;
    int completions = 0;
    int errors = 0;
    int requests = 0;
    int dma_tasks = 0;
    int matrix_tasks = 0;
    int queue_head = 0;
    int queue_tail = 0;
    int queue_count = 0;
    xj_pkg::rob_tag_t queue_tags[4];
    logic pending_response = 0;
    xj_pkg::word_resp_t saved_response;
    logic pending_dma = 0;
    xj_pkg::exe_result_t saved_dma;
    logic pending_matrix = 0;
    logic fault_seen = 0;
    lsu i_dut (.*);
    /* REFERENCE */

    task automatic check_outputs;
        #1;
        /* CHECKS */
        if (request_valid) assert (request.mask == 4'hf)
            else $fatal(1, "memory mask");
    endtask

    initial begin
        allocate = '0; wakeup = '0; head = '0; response = '0; dma_result = '0;
        saved_response = '0; saved_dma = '0;
        for (cycle = 0; cycle < 65000; cycle++) begin
            logic take_allocate, take_result, take_request, take_response, take_dma, take_dma_result;
            logic take_matrix;
            logic result_error;
            xj_pkg::rob_tag_t allocated_tag;
            clk = 0;
            reset = cycle < 2;
            stop = fault_seen || $urandom_range(0, 23) == 0;
            flush = cycle > 2 && quiescent && (fault_seen || cycle % 137 == 0);
            head_valid = queue_count != 0 && $urandom_range(0, 4) != 0;
            head = queue_tags[queue_head];
            allocate_valid = $urandom_range(0, 2) != 0;
            allocate = '0;
            case ($urandom_range(0, 7))
                0: allocate.uop.op = xj_pkg::OP_LW;
                1: allocate.uop.op = xj_pkg::OP_SW;
                2, 3: allocate.uop.op = xj_pkg::OP_VECTOR;
                4, 5: allocate.uop.op = xj_pkg::OP_MATRIX;
                6: allocate.uop.op = xj_pkg::OP_MVIN;
                7: allocate.uop.op = xj_pkg::OP_MVOUT;
                default: allocate.uop.op = xj_pkg::OP_LW;
            endcase
            allocate.uop.fu = xj_pkg::FU_MEM;
            allocate.uop.pc = 32'(cycle*4);
            allocate.uop.tag = 7'(allocations);
            allocate.uop.seq = 16'(cycle);
            allocate.uop.vec_op = $urandom_range(0, 1) == 0 ? 6'd59 : 6'd60;
            allocate.uop.uses_v_old = 1'($urandom);
            allocate.uop.uses_v_mask = 1'($urandom);
            allocate.uop.mat_op = $urandom_range(0, 1) == 0 ? xj_pkg::MAT_LOAD : xj_pkg::MAT_STORE;
            allocate.uop.mat_unit = 2'($urandom_range(0, 2));
            allocate.uop.mat_buffer = 2'($urandom_range(0, 2));
            allocate.src1.ready = $urandom_range(0, 3) != 0;
            allocate.src2.ready = $urandom_range(0, 3) != 0;
            allocate.vold.ready = $urandom_range(0, 3) != 0;
            allocate.vmask.ready = $urandom_range(0, 3) != 0;
            allocate.src1.tag = 7'($urandom_range(0, 7));
            allocate.src2.tag = 7'($urandom_range(0, 7));
            allocate.vold.tag = 7'($urandom_range(0, 7));
            allocate.vmask.tag = 7'($urandom_range(0, 7));
            allocate.src1.value = 32'($urandom_range(0, 8191))*64;
            if (cycle % 13 == 0) allocate.src1.value = 32'h100000;
            if (cycle % 17 == 0) allocate.uop.imm = 32'd1;
            allocate.src2.value = $urandom;
            for (int lane = 0; lane < 8; lane++) begin
                allocate.vold.value[32*lane+:32] = $urandom;
                allocate.vmask.value[32*lane+:32] = $urandom;
            end
            wakeup_valid = 1;
            wakeup = '0;
            wakeup.tag = 7'(cycle%8);
            for (int lane = 0; lane < 8; lane++) wakeup.data[32*lane+:32] = $urandom;
            wakeup.data[31:0] = 32'($urandom_range(0, 8191))*64;
            wakeup.exception = cycle % 31 == 0;
            request_ready = $urandom_range(0, 3) != 0;
            dma_task_ready = $urandom_range(0, 3) != 0;
            matrix_task_ready = $urandom_range(0, 3) != 0;
            result_ready = $urandom_range(0, 3) != 0;
            response_valid = pending_response && $urandom_range(0, 2) != 0;
            response = saved_response;
            dma_result_valid = pending_dma && $urandom_range(0, 3) != 0;
            dma_result = saved_dma;
            if (cycle % 11 == 0) dma_result.tag = ~saved_dma.tag;
            matrix_done = pending_matrix && $urandom_range(0, 3) != 0;
            matrix_state_fault = cycle % 19 == 0;
            matrix_access_fault = cycle % 17 == 0;
            check_outputs();
            take_allocate = allocate_valid && allocate_ready;
            allocated_tag = allocate.uop.tag;
            take_result = result_valid && result_ready;
            result_error = result.exception;
            take_request = request_valid && request_ready;
            take_response = response_valid && response_ready;
            take_dma = dma_task_valid && dma_task_ready;
            take_dma_result = dma_result_valid && dma_result_ready;
            take_matrix = matrix_task_valid && matrix_task_ready;
            if (take_request) begin
                assert (!pending_response) else $fatal(1, "overlapping request");
                saved_response.data = request.addr ^ 32'hdeadbeef;
                saved_response.error = cycle % 29 == 0;
                requests++;
            end
            if (take_dma) begin
                saved_dma = '0;
                saved_dma.tag = dma_task.uop.tag;
                saved_dma.seq = dma_task.uop.seq;
                saved_dma.next_pc = dma_task.uop.pc + 4;
                saved_dma.exception = cycle % 23 == 0;
                saved_dma.cause = saved_dma.exception ? xj_pkg::C_FAULT_DMA_READ : 5'd0;
                dma_tasks++;
            end
            if (take_matrix) matrix_tasks++;
            clk = 1;
            check_outputs();
            if (take_allocate) begin
                queue_tags[queue_tail] = allocated_tag;
                queue_tail = (queue_tail+1)%4;
                queue_count++;
                allocations++;
            end
            if (take_result) begin
                completions++;
                if (result_error) begin fault_seen = 1; errors++; end
                else begin queue_head = (queue_head+1)%4; queue_count--; end
            end
            if (take_request) pending_response = 1;
            if (take_response) pending_response = 0;
            if (take_dma) pending_dma = 1;
            if (take_dma_result && dma_result.tag == saved_dma.tag) pending_dma = 0;
            if (take_matrix) pending_matrix = 1;
            if (matrix_done) pending_matrix = 0;
            if (flush || reset) begin
                queue_head = 0; queue_tail = 0; queue_count = 0;
                pending_response = 0; pending_dma = 0; pending_matrix = 0; fault_seen = 0;
            end
        end
        assert (requests > 100 && dma_tasks > 100 && matrix_tasks > 100 && errors > 20)
            else $fatal(1, "insufficient memory coverage");
        $display("PASS LSU: %0d cycles, %0d allocations, %0d results, %0d faults", cycle,
            allocations, completions, errors);
        $finish;
    end
endmodule
