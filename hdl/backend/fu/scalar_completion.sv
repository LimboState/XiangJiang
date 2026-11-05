// ScalarFunctionalUnit completion slot: cycle-equivalent, synchronous active-high reset.
`default_nettype none
module scalar_completion (
    input logic clk,
    input logic reset,
    input logic flush,
    input logic req_valid,
    input logic resp_ready,
    input xj_pkg::exe_result_t result,
    output logic req_ready,
    output logic resp_valid,
    output xj_pkg::exe_result_t resp,
    output logic busy
);
    typedef enum logic { ST_EMPTY = 1'b0, ST_FULL = 1'b1 } state_t;
    state_t f_state;
    state_t d_state;
    logic d_accept;

    // 1. State register.
    always_ff @(posedge clk) begin
        if (reset) f_state <= ST_EMPTY;
        else f_state <= d_state;
    end

    // 2. Next-state logic. A consumed result may be replaced on the same edge.
    always_comb begin
        d_state = f_state;
        unique case (f_state)
            ST_EMPTY: if (req_valid) d_state = ST_FULL;
            ST_FULL: if (resp_ready && !req_valid) d_state = ST_EMPTY;
        endcase
        if (flush) d_state = ST_EMPTY;
    end

    // 3. Output/control decode; payload register is separate from state.
    always_comb begin
        busy = (f_state == ST_FULL);
        req_ready = !flush && ((f_state == ST_EMPTY) || resp_ready);
        resp_valid = !flush && (f_state == ST_FULL);
        d_accept = req_valid && req_ready;
    end
    // Datapath intentionally unreset, exactly as the Chisel completion record.
    always_ff @(posedge clk) begin
        if (d_accept) resp <= result;
    end
endmodule
`default_nettype wire
