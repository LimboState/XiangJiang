`default_nettype none
module matrix_unit (
    input logic clk,
    input logic reset,
    input logic flush,
    input logic task_valid,
    input xj_pkg::matrix_task_t task_data,
    input logic commit_valid,
    input xj_pkg::rob_tag_t commit,
    input logic config_valid,
    input xj_pkg::matrix_config_t config_data,
    input logic mem_req_ready,
    input logic mem_resp_valid,
    input xj_pkg::matrix_beat_resp_t mem_resp,
    output logic task_ready,
    output logic config_ready,
    output logic mem_req_valid,
    output xj_pkg::matrix_beat_req_t mem_req,
    output logic mem_resp_ready,
    output logic done,
    output logic state_fault,
    output logic access_fault,
    output logic busy,
    output logic [2:0] unit_busy
);
    typedef enum logic [2:0] {
        ST_IDLE = 3'd0, ST_TRANSFER = 3'd1, ST_WAIT_MEM = 3'd2,
        ST_LAUNCH = 3'd3, ST_COMPUTE = 3'd4, ST_COMPLETED = 3'd5,
        ST_AWAIT_COMMIT = 3'd6
    } state_t;
    state_t f_state;
    state_t d_state;
    xj_pkg::matrix_task_t f_task;
    logic [2:0] f_beat;
    logic [2:0] f_total_beats;
    logic f_state_fault;
    logic f_access_fault;
    // BF16 always uses OS; only INT8 units consume stored mode bits.
    /* verilator lint_off UNUSEDSIGNAL */
    logic [2:0] f_modes;
    /* verilator lint_on UNUSEDSIGNAL */
    logic [2:0] w_valid [3];
    logic [511:0] w_store_data [3];
    logic [2:0] w_start_ready;
    logic [2:0] w_busy;
    logic [2:0] d_load_valid;
    logic [2:0] d_start_valid;
    logic [2:0] d_publish_valid;
    logic [1:0] d_publish_buffer;
    logic d_invalid;
    logic d_publish;
    logic d_commit_match;
    logic d_task_fire;
    logic d_mem_resp_fire;
    logic d_needs_a;
    logic d_needs_b;
    logic d_needs_acc;
    matrix_array_bf16 i_matrix_array_bf16 (
        .clk(clk), .reset(reset), .flush(flush), .load_beat_valid(d_load_valid[0]),
        .load_buffer(f_task.buffer), .load_beat(f_beat), .load_data(mem_resp.data),
        .store_beat(f_beat), .start_valid(d_start_valid[0]), .start_op(f_task.op),
        .publish_valid(d_publish_valid[0]), .publish_buffer(d_publish_buffer),
        .store_data(w_store_data[0]), .start_ready(w_start_ready[0]),
        .valid_buffers(w_valid[0]), .busy(w_busy[0])
    );
    for (genvar g_unit = 1; g_unit < 3; g_unit++) begin : gen_int8
        matrix_array_int8 i_matrix_array_int8 (
            .clk(clk), .reset(reset), .flush(flush), .load_beat_valid(d_load_valid[g_unit]),
            .load_buffer(f_task.buffer), .load_beat(f_beat), .load_data(mem_resp.data),
            .store_beat(f_beat), .start_valid(d_start_valid[g_unit]), .start_op(f_task.op),
            .mode(f_modes[g_unit]), .publish_valid(d_publish_valid[g_unit]),
            .publish_buffer(d_publish_buffer), .store_data(w_store_data[g_unit]),
            .start_ready(w_start_ready[g_unit]), .valid_buffers(w_valid[g_unit]),
            .busy(w_busy[g_unit])
        );
    end
    always_comb begin
        config_ready = f_state == ST_IDLE && !flush;
        task_ready = f_state == ST_IDLE && !flush;
        done = f_state == ST_COMPLETED;
        busy = f_state != ST_IDLE && f_state != ST_AWAIT_COMMIT;
        state_fault = f_state_fault;
        access_fault = f_access_fault;
        d_task_fire = task_valid && task_ready;
        d_commit_match = commit_valid && commit == f_task.tag;
        d_publish = f_state == ST_AWAIT_COMMIT && d_commit_match
            && !f_state_fault && !f_access_fault;
        d_publish_buffer = f_task.op == xj_pkg::MAT_LOAD ? f_task.buffer : 2'd2;
        d_needs_a = task_data.op == xj_pkg::MAT_MUL || task_data.op == xj_pkg::MAT_MACC
            || task_data.op == xj_pkg::MAT_EW || task_data.op == xj_pkg::MAT_TRANS;
        d_needs_b = task_data.op == xj_pkg::MAT_MUL || task_data.op == xj_pkg::MAT_MACC
            || task_data.op == xj_pkg::MAT_EW;
        d_needs_acc = task_data.op == xj_pkg::MAT_MACC || task_data.op == xj_pkg::MAT_STORE;
        d_invalid = (d_needs_a && !w_valid[task_data.unit][0])
            || (d_needs_b && !w_valid[task_data.unit][1])
            || (d_needs_acc && !w_valid[task_data.unit][2]);
        mem_req_valid = f_state == ST_TRANSFER && !flush;
        mem_req.addr = f_task.addr + {23'd0, f_beat, 6'd0};
        mem_req.write = f_task.op == xj_pkg::MAT_STORE;
        mem_req.data = w_store_data[f_task.unit];
        mem_req.mask = '1;
        mem_resp_ready = f_state == ST_WAIT_MEM;
        d_mem_resp_fire = mem_resp_valid && mem_resp_ready;
        unit_busy = '0;
        d_load_valid = '0;
        d_start_valid = '0;
        d_publish_valid = '0;
        for (int v_unit = 0; v_unit < 3; v_unit++) begin
            unit_busy[v_unit] = f_state != ST_IDLE && f_task.unit == 2'(v_unit);
            d_load_valid[v_unit] = d_mem_resp_fire && !mem_resp.error
                && f_task.op == xj_pkg::MAT_LOAD && f_task.unit == 2'(v_unit);
            d_start_valid[v_unit] = f_state == ST_LAUNCH && f_task.unit == 2'(v_unit);
            d_publish_valid[v_unit] = d_publish && f_task.unit == 2'(v_unit)
                && f_task.op != xj_pkg::MAT_STORE && f_task.op != xj_pkg::MAT_WAIT;
        end
    end
    always_comb begin
        d_state = f_state;
        unique case (f_state)
            ST_IDLE: if (d_task_fire) begin
                if (d_invalid || task_data.op == xj_pkg::MAT_WAIT) d_state = ST_COMPLETED;
                else if (task_data.op == xj_pkg::MAT_LOAD || task_data.op == xj_pkg::MAT_STORE)
                    d_state = ST_TRANSFER;
                else d_state = ST_LAUNCH;
            end
            ST_TRANSFER: if (mem_req_ready && !flush) d_state = ST_WAIT_MEM;
            ST_WAIT_MEM: if (d_mem_resp_fire)
                d_state = mem_resp.error || f_beat == f_total_beats - 3'd1
                    ? ST_COMPLETED : ST_TRANSFER;
            ST_LAUNCH: if (w_start_ready[f_task.unit]) d_state = ST_COMPUTE;
            ST_COMPUTE: if (!w_busy[f_task.unit]) d_state = ST_COMPLETED;
            ST_COMPLETED: d_state = ST_AWAIT_COMMIT;
            ST_AWAIT_COMMIT: if (d_commit_match) d_state = ST_IDLE;
        endcase
        if (flush) d_state = ST_IDLE;
    end
    always_ff @(posedge clk) begin
        if (reset) f_state <= ST_IDLE;
        else f_state <= d_state;
    end
    always_ff @(posedge clk) begin
        if (reset) begin
            f_modes <= '0;
            f_state_fault <= 1'b0;
            f_access_fault <= 1'b0;
        end else begin
            if (config_valid && config_ready) f_modes[config_data.unit] <= config_data.mode;
            if (d_task_fire) begin
                f_state_fault <= d_invalid;
                f_access_fault <= 1'b0;
            end
            if (d_mem_resp_fire && mem_resp.error) f_access_fault <= 1'b1;
            if (flush) begin
                f_state_fault <= 1'b0;
                f_access_fault <= 1'b0;
            end
        end
        if (d_task_fire) begin
            f_task <= task_data;
            f_beat <= '0;
            f_total_beats <= task_data.unit == 2'd0 ? 3'd2
                : task_data.op == xj_pkg::MAT_LOAD && task_data.buffer != 2'd2 ? 3'd1 : 3'd4;
        end
        if (d_mem_resp_fire && !mem_resp.error && f_beat != f_total_beats - 3'd1)
            f_beat <= f_beat + 3'd1;
    end
endmodule
`default_nettype wire
