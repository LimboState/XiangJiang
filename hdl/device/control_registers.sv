// AXI4-Lite control, version 2. AW/W can arrive independently, including W first.
// Unused AXI protection bits are not exposed, matching optimized reference ports.
`default_nettype none
module control_registers #(
    parameter logic [31:0] G_ITCM_BYTES = 32'd262144,
    parameter logic [31:0] G_DTCM_BYTES = 32'd1048576,
    parameter logic [31:0] G_ROB_ENTRIES = 32'd8,
    parameter logic [31:0] G_RESULT_ENTRIES = 32'd6,
    parameter logic [31:0] G_MEMQ_ENTRIES = 32'd4,
    parameter logic [31:0] G_ISSUE_ENTRIES = 32'd4
) (
    input logic clk,
    input logic reset,
    input logic host_idle,
    input xj_pkg::core_status_t status,
    input logic aw_valid,
    input logic [31:0] aw_addr,
    input logic w_valid,
    // CONTROL only defines low-byte START/ACK; other write bits are ignored by the ISA.
    /* verilator lint_off UNUSEDSIGNAL */
    input logic [31:0] w_data,
    input logic [3:0] w_strb,
    /* verilator lint_on UNUSEDSIGNAL */
    input logic b_ready,
    input logic ar_valid,
    input logic [31:0] ar_addr,
    input logic r_ready,
    input logic [31:0] debug_regs [32],
    output logic aw_ready,
    output logic w_ready,
    output logic b_valid,
    output logic [1:0] b_resp,
    output logic ar_ready,
    output logic r_valid,
    output logic [31:0] r_data,
    output logic [1:0] r_resp,
    output logic start,
    output logic ack
);
    typedef enum logic [2:0] {
        WR_EMPTY = 3'd0, WR_ADDR = 3'd1, WR_DATA = 3'd2,
        WR_EXECUTE = 3'd3, WR_RESPONSE = 3'd4
    } write_state_t;
    typedef enum logic { RD_EMPTY = 1'b0, RD_RESPONSE = 1'b1 } read_state_t;
    write_state_t f_write_state;
    write_state_t d_write_state;
    read_state_t f_read_state;
    read_state_t d_read_state;
    logic [31:0] f_address;
    logic f_request_start;
    logic f_request_ack;
    logic d_allowed_start;
    logic d_allowed_ack;
    logic d_write_error;
    logic [31:0] d_read_data;
    logic d_read_known;

    // 1. Independent read/write state registers.
    always_ff @(posedge clk) begin
        if (reset) begin
            f_write_state <= WR_EMPTY;
            f_read_state <= RD_EMPTY;
        end else begin
            f_write_state <= d_write_state;
            f_read_state <= d_read_state;
        end
    end

    // 2. Next state. No combinational fall-through response or replacement.
    always_comb begin
        d_write_state = f_write_state;
        d_read_state = f_read_state;
        unique case (f_write_state)
            WR_EMPTY: begin
                if (aw_valid && w_valid) d_write_state = WR_EXECUTE;
                else if (aw_valid) d_write_state = WR_ADDR;
                else if (w_valid) d_write_state = WR_DATA;
            end
            WR_ADDR: if (w_valid) d_write_state = WR_EXECUTE;
            WR_DATA: if (aw_valid) d_write_state = WR_EXECUTE;
            WR_EXECUTE: d_write_state = WR_RESPONSE;
            WR_RESPONSE: if (b_ready) d_write_state = WR_EMPTY;
        endcase
        unique case (f_read_state)
            RD_EMPTY: if (ar_valid) d_read_state = RD_RESPONSE;
            RD_RESPONSE: if (r_ready) d_read_state = RD_EMPTY;
        endcase
    end

    // 3. Output decode, permissions and read mux.
    always_comb begin
        aw_ready = (f_write_state == WR_EMPTY) || (f_write_state == WR_DATA);
        w_ready = (f_write_state == WR_EMPTY) || (f_write_state == WR_ADDR);
        b_valid = (f_write_state == WR_RESPONSE);
        ar_ready = (f_read_state == RD_EMPTY);
        r_valid = (f_read_state == RD_RESPONSE);
        d_allowed_start = !status.busy && !status.done && !status.fault
            && !status.dma.poisoned && host_idle;
        d_allowed_ack = !status.busy && !status.dma.poisoned;
        d_write_error = (f_address != 32'd0) || (f_request_start && f_request_ack)
            || (f_request_start && !d_allowed_start) || (f_request_ack && !d_allowed_ack);
        start = (f_write_state == WR_EXECUTE) && !d_write_error && f_request_start;
        ack = (f_write_state == WR_EXECUTE) && !d_write_error && f_request_ack;
        d_read_data = 32'd0;
        d_read_known = (ar_addr <= 32'h50) || ((ar_addr >= 32'h100) && (ar_addr <= 32'h17c));
        case (ar_addr)
            32'h04: d_read_data = {29'd0, status.fault, status.done, status.busy};
            32'h08: d_read_data = status.fault_pc;
            32'h0c: d_read_data = status.fault_inst;
            32'h10: d_read_data = {27'd0, status.fault_cause};
            32'h14: d_read_data = status.fault_addr;
            32'h18: d_read_data = G_ITCM_BYTES;
            32'h1c: d_read_data = G_DTCM_BYTES;
            32'h20: d_read_data = G_ROB_ENTRIES;
            32'h24: d_read_data = G_RESULT_ENTRIES;
            32'h28: d_read_data = G_MEMQ_ENTRIES;
            32'h2c: d_read_data = G_ISSUE_ENTRIES;
            32'h30: d_read_data = 32'd2;
            32'h34: d_read_data = status.fault_detail;
            32'h38: d_read_data = status.dma.row_bytes;
            32'h3c: d_read_data = status.dma.rows;
            32'h40: d_read_data = status.dma.external_stride;
            32'h44: d_read_data = status.dma.local_stride;
            32'h48: d_read_data = status.dma.bytes_done;
            32'h4c: d_read_data = status.dma.rows_done;
            32'h50: d_read_data = {28'd0, status.dma.poisoned, status.dma.error,
                status.dma.outbound, status.dma.active};
            default: begin end
        endcase
        if ((ar_addr >= 32'h100) && (ar_addr <= 32'h17c))
            d_read_data = debug_regs[ar_addr[6:2]];
    end

    always_ff @(posedge clk) begin
        if (aw_valid && aw_ready) f_address <= aw_addr;
        if (w_valid && w_ready) begin
            f_request_start <= w_strb[0] && w_data[0];
            f_request_ack <= w_strb[0] && w_data[1];
        end
        if (f_write_state == WR_EXECUTE) b_resp <= d_write_error ? 2'd2 : 2'd0;
        if (ar_valid && ar_ready) begin
            r_data <= d_read_data;
            r_resp <= (d_read_known && (ar_addr[1:0] == 2'b00)) ? 2'd0 : 2'd2;
        end
    end
endmodule
`default_nettype wire
