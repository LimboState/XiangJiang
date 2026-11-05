// Single-outstanding frontend; preserves the Chisel epoch/kill priorities.
// Request-ready is absent in the generated reference: the TCM is always ready.
`default_nettype none
module frontend (
    input logic clk,
    input logic reset,
    input logic start,
    input logic stop_run,
    input logic barrier,
    input logic redirect_valid,
    input logic [31:0] redirect_pc,
    input logic response_valid,
    input xj_pkg::fetch_packet_t response,
    input logic fetch_ready,
    output logic request_valid,
    output logic [31:0] request_pc,
    output logic fetch_valid,
    output xj_pkg::fetch_packet_t fetch
);
    typedef enum logic [1:0] {
        ST_STOPPED = 2'd0,
        ST_RUNNING = 2'd1,
        ST_PAUSED = 2'd2
    } state_t;
    state_t f_state;
    state_t d_state;
    logic f_pending;
    logic f_valid;
    logic f_epoch;
    logic f_request_epoch;
    logic f_killed;
    logic [31:0] f_pc;
    logic [31:0] f_request_pc;
    logic d_invalidate;
    logic d_accept_response;
    logic d_fetch_fire;

    // 1. State storage, synchronous reset to match the reference.
    always_ff @(posedge clk) begin
        if (reset) f_state <= ST_STOPPED;
        else f_state <= d_state;
    end

    // 2. Lifecycle transitions, stop > start > redirect > barrier.
    always_comb begin
        d_state = f_state;
        unique case (f_state)
            ST_STOPPED: d_state = ST_STOPPED;
            ST_RUNNING: if (f_valid && barrier) d_state = ST_PAUSED;
            ST_PAUSED: if (redirect_valid) d_state = ST_RUNNING;
        endcase
        if (redirect_valid && (f_state != ST_STOPPED)) d_state = ST_RUNNING;
        if (start) d_state = ST_RUNNING;
        if (stop_run) d_state = ST_STOPPED;
    end

    // 3. Combinational outputs and datapath enables.
    always_comb begin
        request_valid = (f_state == ST_RUNNING) && !f_pending && !f_valid && !stop_run;
        request_pc = f_pc;
        fetch_valid = f_valid && (f_state != ST_STOPPED) && !stop_run;
        d_fetch_fire = fetch_valid && fetch_ready;
        d_invalidate = start || redirect_valid || stop_run;
        d_accept_response = response_valid && f_pending && (f_state != ST_STOPPED)
            && !f_killed && !d_invalidate && (f_request_epoch == f_epoch)
            && (response.pc == f_request_pc);
    end

    always_ff @(posedge clk) begin
        if (reset) begin
            f_pending <= 1'b0;
            f_valid <= 1'b0;
            f_pc <= 32'd0;
            f_epoch <= 1'b0;
            f_killed <= 1'b0;
        end else begin
            if (request_valid) begin
                f_pending <= 1'b1;
                f_killed <= 1'b0;
                f_pc <= f_pc + 32'd4;
            end
            if (response_valid && f_pending) begin
                f_pending <= 1'b0;
                if (d_accept_response) f_valid <= 1'b1;
            end
            if (d_invalidate) begin
                f_epoch <= !f_epoch;
                if (f_pending) f_killed <= 1'b1;
            end
            if (d_fetch_fire) f_valid <= 1'b0;
            if (redirect_valid) begin
                f_pc <= redirect_pc;
                f_valid <= 1'b0;
            end
            if (start) begin
                f_pc <= 32'd0;
                f_valid <= 1'b0;
            end
            if (stop_run) f_valid <= 1'b0;
        end
    end
    // Metadata is retained until its outstanding request drains, even on restart.
    always_ff @(posedge clk) begin
        if (request_valid) begin
            f_request_pc <= f_pc;
            f_request_epoch <= f_epoch;
        end
        if (d_accept_response) fetch <= response;
    end
endmodule
`default_nettype wire
