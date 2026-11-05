`default_nettype none
module scalar_core (
    input logic clk,
    input logic reset,
    input logic start,
    input logic ack,
    input logic fetch_resp_valid,
    input xj_pkg::fetch_packet_t fetch_resp,
    input logic data_req_ready,
    input logic data_resp_valid,
    input xj_pkg::word_resp_t data_resp,
    input logic matrix_req_ready,
    input logic matrix_resp_valid,
    input xj_pkg::matrix_beat_resp_t matrix_resp,
    input logic external_ar_ready,
    input logic external_aw_ready,
    input logic external_w_ready,
    input logic external_r_valid,
    input xj_pkg::axi_read_data_t external_r,
    input logic external_b_valid,
    input xj_pkg::axi_write_response_t external_b,
    output logic fetch_req_valid,
    output logic [31:0] fetch_req,
    output logic data_req_valid,
    output xj_pkg::word_req_t data_req,
    output logic data_resp_ready,
    output logic matrix_req_valid,
    output xj_pkg::matrix_beat_req_t matrix_req,
    output logic matrix_resp_ready,
    output logic external_ar_valid,
    output xj_pkg::axi_address_t external_ar,
    output logic external_aw_valid,
    output xj_pkg::axi_address_t external_aw,
    output logic external_w_valid,
    output xj_pkg::axi_write_data_t external_w,
    output logic external_r_ready,
    output logic external_b_ready,
    output xj_pkg::core_status_t status,
    output logic [31:0] debug_regs [32],
    output logic [255:0] debug_v_regs [32],
    output logic retire_valid,
    output xj_pkg::commit_record_t retire,
    output logic writeback_valid,
    output xj_pkg::exe_result_t writeback,
    output logic alu_issued_valid,
    output xj_pkg::rob_tag_t alu_issued,
    output logic agu_issued_valid,
    output xj_pkg::rob_tag_t agu_issued
);
    typedef enum logic [2:0] {
        ST_IDLE = 3'd0, ST_RUNNING = 3'd1, ST_DRAINING = 3'd2,
        ST_DONE = 3'd3, ST_FAULT = 3'd4
    } state_t;
    state_t f_state;
    state_t d_state;
    logic [31:0] f_fault_pc;
    logic [31:0] f_fault_inst;
    logic [4:0] f_fault_cause;
    logic [31:0] f_fault_addr;
    logic [31:0] f_fault_detail;
    logic f_system_valid;
    logic f_system_sent;
    xj_pkg::exe_result_t f_system_result;
    logic f_matrix_control_active;
    logic f_matrix_control_valid;
    xj_pkg::exe_result_t f_matrix_control_result;
    logic f_writeback_pending;
    xj_pkg::exe_result_t f_writeback_result;
    logic f_dma_config_active;
    logic d_running;
    logic d_draining;
    logic d_start;
    logic d_fault_detected;
    logic d_stop_issue;
    logic d_quiescent;
    logic d_finish_fault;
    logic d_flush;
    logic d_stop_frontend;
    logic d_redirect;
    logic d_fetch_ready;
    logic w_fetch_valid;
    xj_pkg::fetch_packet_t w_fetch;
    xj_pkg::micro_op_t w_decoded;
    xj_pkg::micro_op_t d_uop;
    xj_pkg::issue_entry_t w_renamed;
    logic w_rob_allocate_ready;
    xj_pkg::rob_tag_t w_alloc_tag;
    logic [15:0] w_alloc_seq;
    logic w_head_valid;
    // The head bundle carries additional dispatch metadata unused by commit control.
    /* verilator lint_off UNUSEDSIGNAL */
    xj_pkg::micro_op_t w_head;
    /* verilator lint_on UNUSEDSIGNAL */
    logic w_retire_valid;
    xj_pkg::commit_record_t w_retire;
    logic d_retire_ready;
    logic w_wb_accepted;
    // Observed only by the flush assertion; synthesis has no consumer.
    /* verilator lint_off UNUSEDSIGNAL */
    logic w_rob_empty;
    /* verilator lint_on UNUSEDSIGNAL */
    xj_pkg::rob_tag_t w_queries [2];
    xj_pkg::rob_tag_t w_vector_queries [4];
    xj_pkg::operand_t w_values [2];
    xj_pkg::vector_operand_t w_vector_values [4];
    logic d_dispatch;
    logic d_target_ready;
    logic [2:0] d_vector_group;
    logic [8:0] d_dispatch_valid;
    logic [8:0] w_dispatch_ready;
    logic [8:0] w_issue_valid;
    logic [8:0] d_issue_ready;
    xj_pkg::issue_entry_t w_issue [9];
    logic [8:0] w_issue_empty;
    logic [6:0] w_fu_req_ready;
    logic [6:0] w_fu_busy;
    logic [6:0] w_fu_resp_valid;
    xj_pkg::exe_result_t w_fu_resp [7];
    logic [6:0] d_fu_resp_ready;
    logic w_lsu_allocate_ready;
    logic d_lsu_allocate_valid;
    logic w_lsu_empty;
    logic w_lsu_quiescent;
    logic w_lsu_req_valid;
    xj_pkg::word_req_t w_lsu_req;
    logic w_lsu_resp_ready;
    logic w_lsu_result_valid;
    xj_pkg::exe_result_t w_lsu_result;
    logic w_lsu_dma_valid;
    xj_pkg::issue_entry_t w_lsu_dma;
    logic w_lsu_dma_result_ready;
    logic w_lsu_matrix_valid;
    xj_pkg::matrix_task_t w_lsu_matrix;
    logic d_lsu_matrix_ready;
    logic d_matrix_done_lsu;
    logic d_dma_command_valid;
    xj_pkg::issue_entry_t d_dma_command;
    logic w_dma_command_ready;
    logic w_dma_result_valid;
    xj_pkg::exe_result_t w_dma_result;
    logic d_dma_result_ready;
    logic w_dma_local_req_valid;
    xj_pkg::word_req_t w_dma_local_req;
    logic w_dma_local_resp_ready;
    xj_pkg::dma_status_t w_dma_status;
    logic w_dma_busy;
    // Protocol poison reports only the DMA fault address and detail.
    /* verilator lint_off UNUSEDSIGNAL */
    xj_pkg::exe_result_t w_dma_fault;
    /* verilator lint_on UNUSEDSIGNAL */
    logic d_matrix_task_valid;
    xj_pkg::matrix_task_t d_matrix_task;
    logic w_matrix_task_ready;
    logic w_matrix_done;
    logic w_matrix_state_fault;
    logic w_matrix_access_fault;
    logic w_matrix_busy;
    // Configuration barriers guarantee readiness; these signals check that contract.
    /* verilator lint_off UNUSEDSIGNAL */
    logic [2:0] w_matrix_unit_busy;
    logic w_matrix_config_ready;
    /* verilator lint_on UNUSEDSIGNAL */
    logic d_matrix_config_valid;
    xj_pkg::matrix_config_t d_matrix_config;
    logic d_start_control;
    logic d_control_fire;
    logic d_system_start;
    logic [10:0] d_completion_valid;
    xj_pkg::exe_result_t d_completion [11];
    logic [10:0] d_completion_ready;
    logic d_tree_valid [31];
    logic [15:0] d_tree_seq [31];
    logic [3:0] d_tree_index [31];
    logic d_commit;

    frontend i_frontend (
        .clk(clk), .reset(reset), .start(d_start), .stop_run(d_stop_frontend),
        .barrier(w_decoded.barrier), .redirect_valid(d_redirect), .redirect_pc(w_retire.next_pc),
        .response_valid(fetch_resp_valid), .response(fetch_resp), .fetch_ready(d_fetch_ready),
        .request_valid(fetch_req_valid), .request_pc(fetch_req),
        .fetch_valid(w_fetch_valid), .fetch(w_fetch)
    );
    decode_unit i_decode_unit (.packet(w_fetch), .uop(w_decoded));
    rename_stage i_rename_stage (
        .clk(clk), .reset(reset), .dispatch(d_dispatch), .flush(d_flush), .uop(d_uop),
        .wakeup_valid(w_wb_accepted), .wakeup(f_writeback_result),
        .commit_valid(d_commit), .commit(w_retire), .lookup_values(w_values),
        .vector_lookup_values(w_vector_values), .renamed(w_renamed),
        .lookup_tags(w_queries), .vector_lookup_tags(w_vector_queries),
        .debug_regs(debug_regs), .debug_v_regs(debug_v_regs)
    );
    rob i_rob (
        .clk(clk), .reset(reset), .flush(d_flush), .quiescent(d_quiescent),
        .allocate_valid(d_dispatch), .allocate(d_uop), .retire_ready(d_retire_ready),
        .wb_valid(f_writeback_pending && !d_flush), .wb(f_writeback_result),
        .query(w_queries), .vector_query(w_vector_queries),
        .allocate_ready(w_rob_allocate_ready), .alloc_tag(w_alloc_tag), .alloc_seq(w_alloc_seq),
        .head_valid(w_head_valid), .head(w_head), .retire_valid(w_retire_valid),
        .retire(w_retire), .wb_accepted(w_wb_accepted), .values(w_values),
        .vector_values(w_vector_values), .empty(w_rob_empty)
    );
    for (genvar g_station = 0; g_station < 9; g_station++) begin : gen_issue
        issue_unit #(.G_HEAD_ONLY(int'(g_station == 1 || g_station >= 7)),
            .G_VECTOR_ENABLED(int'(g_station >= 2 && g_station <= 6)),
            .G_ISSUE_ON_WAKEUP(0)) i_issue_unit (
            .clk(clk), .reset(reset), .flush(d_flush), .head_valid(w_head_valid),
            .head_tag(w_head.tag), .dispatch_valid(d_dispatch_valid[g_station]),
            .dispatch(w_renamed), .wakeup_valid(w_wb_accepted), .wakeup(f_writeback_result),
            .issue_ready(d_issue_ready[g_station]), .dispatch_ready(w_dispatch_ready[g_station]),
            .issue_valid(w_issue_valid[g_station]), .issue(w_issue[g_station]),
            .empty(w_issue_empty[g_station])
        );
    end
    alu_unit i_alu_unit (
        .clk(clk), .reset(reset), .flush(d_flush), .req_valid(w_issue_valid[0] && !d_stop_issue),
        .resp_ready(d_fu_resp_ready[0]), .op(w_issue[0].uop.op), .use_imm(w_issue[0].uop.use_imm),
        .a(w_issue[0].src1.value), .b(w_issue[0].src2.value), .imm(w_issue[0].uop.imm),
        .pc(w_issue[0].uop.pc), .tag(w_issue[0].uop.tag), .seq(w_issue[0].uop.seq),
        .req_ready(w_fu_req_ready[0]), .resp_valid(w_fu_resp_valid[0]),
        .resp(w_fu_resp[0]), .busy(w_fu_busy[0])
    );
    branch_unit i_branch_unit (
        .clk(clk), .reset(reset), .flush(d_flush), .req_valid(w_issue_valid[1] && !d_stop_issue),
        .resp_ready(d_fu_resp_ready[1]), .op(w_issue[1].uop.op),
        .a(w_issue[1].src1.value), .b(w_issue[1].src2.value), .imm(w_issue[1].uop.imm),
        .pc(w_issue[1].uop.pc), .tag(w_issue[1].uop.tag), .seq(w_issue[1].uop.seq),
        .req_ready(w_fu_req_ready[1]), .resp_valid(w_fu_resp_valid[1]),
        .resp(w_fu_resp[1]), .busy(w_fu_busy[1])
    );
    for (genvar g_group = 0; g_group < 5; g_group++) begin : gen_vector
        vector_unit #(.G_GROUP(g_group)) i_vector_unit (
            .clk(clk), .reset(reset), .flush(d_flush),
            .req_valid(w_issue_valid[g_group+2] && !d_stop_issue), .req(w_issue[g_group+2]),
            .resp_ready(d_fu_resp_ready[g_group+2]), .req_ready(w_fu_req_ready[g_group+2]),
            .resp_valid(w_fu_resp_valid[g_group+2]), .resp(w_fu_resp[g_group+2]),
            .busy(w_fu_busy[g_group+2])
        );
    end
    lsu i_lsu (
        .clk(clk), .reset(reset), .stop(d_stop_issue), .flush(d_flush),
        .allocate_valid(d_lsu_allocate_valid), .allocate(w_renamed),
        .wakeup_valid(w_wb_accepted), .wakeup(f_writeback_result),
        .head_valid(w_head_valid), .head(w_head.tag),
        .request_ready(data_req_ready && !w_dma_status.active),
        .response_valid(data_resp_valid && !w_dma_status.active), .response(data_resp),
        .dma_task_ready(w_dma_command_ready),
        .dma_result_valid(w_dma_result_valid && !f_dma_config_active), .dma_result(w_dma_result),
        .matrix_task_ready(d_lsu_matrix_ready), .matrix_done(d_matrix_done_lsu),
        .matrix_state_fault(w_matrix_state_fault), .matrix_access_fault(w_matrix_access_fault),
        .result_ready(d_completion_ready[2]), .allocate_ready(w_lsu_allocate_ready),
        .request_valid(w_lsu_req_valid), .request(w_lsu_req), .response_ready(w_lsu_resp_ready),
        .dma_task_valid(w_lsu_dma_valid), .dma_task(w_lsu_dma),
        .dma_result_ready(w_lsu_dma_result_ready), .matrix_task_valid(w_lsu_matrix_valid),
        .matrix_task(w_lsu_matrix), .result_valid(w_lsu_result_valid), .result(w_lsu_result),
        .empty(w_lsu_empty), .quiescent(w_lsu_quiescent),
        .address_issued_valid(agu_issued_valid), .address_issued(agu_issued)
    );
    dma_engine i_dma_engine (
        .clk(clk), .reset(reset), .ack(ack), .command_valid(d_dma_command_valid),
        .command(d_dma_command), .result_ready(d_dma_result_ready),
        .commit_valid(d_commit && w_retire.uop.fu == xj_pkg::FU_DMA_CONFIG),
        .commit(w_retire.uop.tag), .axi_ar_ready(external_ar_ready),
        .axi_aw_ready(external_aw_ready), .axi_w_ready(external_w_ready),
        .axi_r_valid(external_r_valid), .axi_r(external_r),
        .axi_b_valid(external_b_valid), .axi_b(external_b),
        .local_req_ready(data_req_ready && w_dma_status.active),
        .local_resp_valid(data_resp_valid && w_dma_status.active), .local_resp(data_resp),
        .command_ready(w_dma_command_ready), .result_valid(w_dma_result_valid), .result(w_dma_result),
        .axi_ar_valid(external_ar_valid), .axi_ar(external_ar),
        .axi_aw_valid(external_aw_valid), .axi_aw(external_aw),
        .axi_w_valid(external_w_valid), .axi_w(external_w),
        .axi_r_ready(external_r_ready), .axi_b_ready(external_b_ready),
        .local_req_valid(w_dma_local_req_valid), .local_req(w_dma_local_req),
        .local_resp_ready(w_dma_local_resp_ready), .status(w_dma_status),
        .busy(w_dma_busy), .fault(w_dma_fault)
    );
    matrix_unit i_matrix_unit (
        .clk(clk), .reset(reset), .flush(d_flush), .task_valid(d_matrix_task_valid),
        .task_data(d_matrix_task), .commit_valid(d_commit && w_retire.uop.op == xj_pkg::OP_MATRIX),
        .commit(w_retire.uop.tag), .config_valid(d_matrix_config_valid), .config_data(d_matrix_config),
        .mem_req_ready(matrix_req_ready), .mem_resp_valid(matrix_resp_valid), .mem_resp(matrix_resp),
        .task_ready(w_matrix_task_ready), .config_ready(w_matrix_config_ready),
        .mem_req_valid(matrix_req_valid), .mem_req(matrix_req), .mem_resp_ready(matrix_resp_ready),
        .done(w_matrix_done), .state_fault(w_matrix_state_fault), .access_fault(w_matrix_access_fault),
        .busy(w_matrix_busy), .unit_busy(w_matrix_unit_busy)
    );

    always_comb begin
        d_running = f_state == ST_RUNNING || f_state == ST_DRAINING;
        d_draining = f_state == ST_DRAINING;
        d_start = start && f_state == ST_IDLE && !w_dma_status.poisoned;
        d_fault_detected = f_state == ST_RUNNING && w_retire_valid && w_retire.exception;
        d_stop_issue = !d_running || d_draining || d_fault_detected || w_dma_status.poisoned;
    end
    always_comb begin
        d_quiescent = !(|w_fu_busy) && !w_matrix_busy && w_lsu_quiescent && !w_dma_busy
            && !f_system_valid && !f_matrix_control_active && !f_matrix_control_valid
            && !f_writeback_pending;
    end
    always_comb begin
        d_finish_fault = d_draining && d_quiescent;
        d_flush = d_start || d_finish_fault;
        d_stop_frontend = (!d_running && !d_start) || d_draining
            || d_fault_detected || w_dma_status.poisoned;
    end
    always_comb begin
        d_uop = w_decoded;
        d_uop.tag = w_alloc_tag;
        d_uop.seq = w_alloc_seq;
        d_vector_group = (d_uop.vec_op == 6'd4 || d_uop.vec_op == 6'd5) ? 3'd1
            : (d_uop.vec_op == 6'd28 || d_uop.vec_op == 6'd29) ? 3'd3
            : ((d_uop.vec_op >= 6'd22 && d_uop.vec_op <= 6'd35)
                || (d_uop.vec_op >= 6'd46 && d_uop.vec_op <= 6'd51) || d_uop.vec_op == 6'd58)
            ? 3'd2 : d_uop.vec_op >= 6'd52 ? 3'd4 : 3'd0;
    end
    always_comb begin
        d_target_ready = 1'b1;
        if (!d_uop.exception) begin
            case (d_uop.fu)
                xj_pkg::FU_ALU: d_target_ready = w_dispatch_ready[0];
                xj_pkg::FU_BRU: d_target_ready = w_dispatch_ready[1];
                xj_pkg::FU_VECTOR: d_target_ready = w_dispatch_ready[4'(d_vector_group)+4'd2];
                xj_pkg::FU_MEM: d_target_ready = w_lsu_allocate_ready;
                xj_pkg::FU_MATRIX: d_target_ready = w_dispatch_ready[7];
                xj_pkg::FU_DMA_CONFIG: d_target_ready = w_dispatch_ready[8];
                default: begin end
            endcase
        end
    end
    always_comb begin
        d_fetch_ready = !d_stop_issue && !d_flush && w_rob_allocate_ready && d_target_ready;
        d_dispatch = w_fetch_valid && d_fetch_ready;
        d_dispatch_valid = '0;
        d_lsu_allocate_valid = 1'b0;
        if (d_dispatch && !d_uop.exception) begin
            case (d_uop.fu)
                xj_pkg::FU_ALU: d_dispatch_valid[0] = 1'b1;
                xj_pkg::FU_BRU: d_dispatch_valid[1] = 1'b1;
                xj_pkg::FU_VECTOR: d_dispatch_valid[4'(d_vector_group)+4'd2] = 1'b1;
                xj_pkg::FU_MEM: d_lsu_allocate_valid = 1'b1;
                xj_pkg::FU_MATRIX: d_dispatch_valid[7] = 1'b1;
                xj_pkg::FU_DMA_CONFIG: d_dispatch_valid[8] = 1'b1;
                default: begin end
            endcase
        end
    end
    always_comb begin
        d_issue_ready = '0;
        for (int v_index = 0; v_index < 7; v_index++)
            d_issue_ready[v_index] = w_fu_req_ready[v_index] && !d_stop_issue;
        alu_issued_valid = w_issue_valid[0] && d_issue_ready[0];
        alu_issued = w_issue[0].uop.tag;
        data_req_valid = w_dma_status.active ? w_dma_local_req_valid : w_lsu_req_valid;
        data_req = w_dma_status.active ? w_dma_local_req : w_lsu_req;
        data_resp_ready = w_dma_status.active ? w_dma_local_resp_ready : w_lsu_resp_ready;
        d_dma_command_valid = w_lsu_dma_valid || (w_issue_valid[8] && !d_stop_issue);
        d_dma_command = w_lsu_dma_valid ? w_lsu_dma : w_issue[8];
        d_issue_ready[8] = w_dma_command_ready && !w_lsu_dma_valid && !d_stop_issue;
        d_start_control = w_issue_valid[7] && !d_stop_issue
            && !f_matrix_control_active && !f_matrix_control_valid;
        d_matrix_task_valid = w_lsu_matrix_valid || d_start_control;
        d_matrix_task = w_lsu_matrix;
        if (!w_lsu_matrix_valid)
            d_matrix_task = '{tag:w_issue[7].uop.tag, op:w_issue[7].uop.mat_op,
                unit:w_issue[7].uop.mat_unit, buffer:2'd0, addr:32'd0};
        d_lsu_matrix_ready = w_matrix_task_ready;
        d_issue_ready[7] = w_matrix_task_ready && !w_lsu_matrix_valid && d_start_control;
        d_control_fire = w_issue_valid[7] && d_issue_ready[7];
        d_matrix_done_lsu = w_matrix_done && !f_matrix_control_active;
    end
    always_comb begin
        d_system_start = w_head_valid && w_head.fu == xj_pkg::FU_SYSTEM && !w_head.exception
            && !f_system_sent && !d_stop_issue && w_lsu_empty && !(|w_fu_busy) && !w_matrix_busy
            && (&w_issue_empty) && !w_dma_busy && !f_writeback_pending;
    end
    always_comb begin
        d_retire_ready = d_running && !d_draining && !w_retire.exception && !w_dma_status.poisoned;
        d_commit = w_retire_valid && d_retire_ready;
        retire_valid = d_commit;
        retire = w_retire;
        writeback_valid = w_wb_accepted;
        writeback = f_writeback_result;
        d_matrix_config_valid = d_commit && w_retire.uop.op == xj_pkg::OP_MATRIX
            && w_retire.uop.mat_op == xj_pkg::MAT_SETMODE;
        d_matrix_config = '{unit:w_retire.uop.mat_unit, mode:w_retire.uop.mat_mode};
        d_redirect = d_commit && w_retire.uop.barrier && w_retire.uop.op != xj_pkg::OP_HALT;
    end
    always_comb begin
        status = '{busy:(d_running || w_dma_status.poisoned), done:(f_state == ST_DONE),
            fault:(f_state == ST_FAULT), fault_pc:f_fault_pc, fault_inst:f_fault_inst,
            fault_cause:f_fault_cause, fault_addr:f_fault_addr, fault_detail:f_fault_detail,
            dma:w_dma_status};
    end

    always_comb begin
        d_completion_valid = '0;
        d_completion = '{default:'0};
        d_completion_valid[0] = w_fu_resp_valid[0];
        d_completion[0] = w_fu_resp[0];
        d_completion_valid[1] = w_fu_resp_valid[1];
        d_completion[1] = w_fu_resp[1];
        d_completion_valid[2] = w_lsu_result_valid;
        d_completion[2] = w_lsu_result;
        d_completion_valid[3] = w_fu_resp_valid[2];
        d_completion[3] = w_fu_resp[2];
        d_completion_valid[4] = f_system_valid;
        d_completion[4] = f_system_result;
        d_completion_valid[5] = f_matrix_control_valid;
        d_completion[5] = f_matrix_control_result;
        d_completion_valid[6] = w_dma_result_valid && f_dma_config_active;
        d_completion[6] = w_dma_result;
        for (int v_group = 1; v_group < 5; v_group++) begin
            d_completion_valid[6+v_group] = w_fu_resp_valid[2+v_group];
            d_completion[6+v_group] = w_fu_resp[2+v_group];
        end
        d_tree_valid = '{default:1'b0};
        d_tree_seq = '{default:'0};
        d_tree_index = '{default:'0};
        for (int v_index = 0; v_index < 11; v_index++) begin
            d_tree_valid[15+v_index] = d_completion_valid[v_index];
            d_tree_seq[15+v_index] = d_completion[v_index].seq;
            d_tree_index[15+v_index] = 4'(v_index);
        end
        for (int v_node = 14; v_node >= 0; v_node--) begin
            logic v_take_right;
            v_take_right = d_tree_valid[2*v_node+2] && (!d_tree_valid[2*v_node+1]
                || d_tree_seq[2*v_node+2] < d_tree_seq[2*v_node+1]);
            d_tree_valid[v_node] = d_tree_valid[2*v_node+1] || d_tree_valid[2*v_node+2];
            d_tree_seq[v_node] = v_take_right ? d_tree_seq[2*v_node+2] : d_tree_seq[2*v_node+1];
            d_tree_index[v_node] = v_take_right ? d_tree_index[2*v_node+2]
                : d_tree_index[2*v_node+1];
        end
        d_completion_ready = '0;
        for (int v_index = 0; v_index < 11; v_index++)
            d_completion_ready[v_index] = !d_flush && d_tree_valid[0]
                && d_tree_index[0] == 4'(v_index);
        d_fu_resp_ready[0] = d_completion_ready[0];
        d_fu_resp_ready[1] = d_completion_ready[1];
        d_fu_resp_ready[2] = d_completion_ready[3];
        for (int v_group = 1; v_group < 5; v_group++)
            d_fu_resp_ready[2+v_group] = d_completion_ready[6+v_group];
        d_dma_result_ready = f_dma_config_active ? d_completion_ready[6] : w_lsu_dma_result_ready;
    end

    always_comb begin
        d_state = f_state;
        unique case (f_state)
            ST_IDLE: if (d_start) d_state = ST_RUNNING;
            ST_RUNNING: begin
                if (d_commit && w_retire.uop.barrier && w_retire.uop.op == xj_pkg::OP_HALT)
                    d_state = ST_DONE;
                if (d_fault_detected) d_state = ST_DRAINING;
            end
            ST_DRAINING: if (d_finish_fault) d_state = ST_FAULT;
            ST_DONE, ST_FAULT: if ((ack && !w_dma_status.poisoned) || w_dma_status.poisoned)
                d_state = ST_IDLE;
        endcase
    end
    always_ff @(posedge clk) begin
        if (reset) f_state <= ST_IDLE;
        else f_state <= d_state;
    end
    always_ff @(posedge clk) begin
        if (reset) begin
            f_fault_pc <= '0;
            f_fault_inst <= '0;
            f_fault_cause <= '0;
            f_fault_addr <= '0;
            f_fault_detail <= '0;
            f_system_valid <= 1'b0;
            f_system_sent <= 1'b0;
            f_matrix_control_active <= 1'b0;
            f_matrix_control_valid <= 1'b0;
            f_writeback_pending <= 1'b0;
            f_dma_config_active <= 1'b0;
        end else begin
            f_writeback_pending <= d_tree_valid[0] && !d_flush;
            if (d_dma_command_valid && w_dma_command_ready)
                f_dma_config_active <= d_dma_command.uop.fu == xj_pkg::FU_DMA_CONFIG;
            if (d_control_fire) f_matrix_control_active <= 1'b1;
            if (w_matrix_done && f_matrix_control_active) begin
                f_matrix_control_active <= 1'b0;
                f_matrix_control_valid <= 1'b1;
            end
            if (d_completion_ready[5] && f_matrix_control_valid) f_matrix_control_valid <= 1'b0;
            if (d_system_start) begin f_system_valid <= 1'b1; f_system_sent <= 1'b1; end
            if (d_completion_ready[4] && f_system_valid) f_system_valid <= 1'b0;
            if (d_commit && w_retire.uop.barrier) f_system_sent <= 1'b0;
            if (d_flush) begin
                f_matrix_control_active <= 1'b0;
                f_matrix_control_valid <= 1'b0;
                f_system_valid <= 1'b0;
                f_system_sent <= 1'b0;
            end
            if (d_fault_detected) begin
                f_fault_pc <= w_retire.uop.pc;
                f_fault_inst <= w_retire.uop.inst;
                f_fault_cause <= w_retire.cause;
                f_fault_addr <= w_retire.bad_addr;
                f_fault_detail <= w_retire.detail;
            end
            if (ack && !d_running && !w_dma_status.poisoned) begin
                f_fault_pc <= '0;
                f_fault_inst <= '0;
                f_fault_cause <= '0;
                f_fault_addr <= '0;
                f_fault_detail <= '0;
            end
            if (w_dma_status.poisoned) begin
                f_fault_cause <= xj_pkg::C_FAULT_DMA_PROTOCOL;
                f_fault_addr <= w_dma_fault.bad_addr;
                f_fault_detail <= w_dma_fault.detail;
                if (w_head_valid) begin f_fault_pc <= w_head.pc; f_fault_inst <= w_head.inst; end
            end
        end
        if (d_tree_valid[0] && !d_flush) f_writeback_result <= d_completion[d_tree_index[0]];
        if (d_control_fire) begin
            f_matrix_control_result <= '0;
            f_matrix_control_result.tag <= w_issue[7].uop.tag;
            f_matrix_control_result.seq <= w_issue[7].uop.seq;
            f_matrix_control_result.next_pc <= w_issue[7].uop.pc + 32'd4;
        end
        if (w_matrix_done && f_matrix_control_active) begin
            f_matrix_control_result.exception <= w_matrix_state_fault;
            f_matrix_control_result.cause <= w_matrix_state_fault
                ? xj_pkg::C_FAULT_MATRIX_STATE : xj_pkg::C_FAULT_NONE;
            f_matrix_control_result.detail <= w_matrix_state_fault
                ? 32'h02010000 | {22'd0, w_head.mat_unit, 8'd0} : 32'd0;
        end
        if (d_system_start) begin
            f_system_result <= '0;
            f_system_result.tag <= w_head.tag;
            f_system_result.seq <= w_head.seq;
            f_system_result.next_pc <= w_head.pc + 32'd4;
        end
`ifndef SYNTHESIS
        if (!reset && d_flush) begin
            assert (!f_writeback_pending) else $error("flush before writeback drain");
            assert (w_rob_empty || d_finish_fault) else $error("start with occupied ROB");
        end
        if (!reset && d_matrix_config_valid)
            assert (w_matrix_config_ready && !(|w_matrix_unit_busy))
                else $error("matrix configuration without quiescence");
`endif
    end
endmodule
`default_nettype wire
