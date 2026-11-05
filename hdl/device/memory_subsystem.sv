`default_nettype none
module memory_subsystem #(
    parameter int unsigned G_ITCM_BYTES = 262144,
    parameter int unsigned G_BANK_BYTES = 262144
) (
    input logic clk,
    input logic reset,
    input logic core_busy,
    input logic bus_poisoned,
    input logic axi_aw_valid,
    // AXI cache/protection/QoS attributes have no effect on the local TCM windows.
    /* verilator lint_off UNUSEDSIGNAL */
    input xj_pkg::axi_address_t axi_aw,
    input xj_pkg::axi_address_t axi_ar,
    /* verilator lint_on UNUSEDSIGNAL */
    input logic axi_w_valid,
    input xj_pkg::axi_write_data_t axi_w,
    input logic axi_b_ready,
    input logic axi_ar_valid,
    input logic axi_r_ready,
    input logic fetch_req_valid,
    input logic [31:0] fetch_req,
    input logic data_req_valid,
    input xj_pkg::word_req_t data_req,
    input logic data_resp_ready,
    input logic matrix_req_valid,
    input xj_pkg::matrix_beat_req_t matrix_req,
    input logic matrix_resp_ready,
    output logic axi_aw_ready,
    output logic axi_w_ready,
    output logic axi_b_valid,
    output xj_pkg::axi_write_response_t axi_b,
    output logic axi_ar_ready,
    output logic axi_r_valid,
    output xj_pkg::axi_read_data_t axi_r,
    output logic fetch_req_ready,
    output logic fetch_resp_valid,
    output xj_pkg::fetch_packet_t fetch_resp,
    output logic data_req_ready,
    output logic data_resp_valid,
    output xj_pkg::word_resp_t data_resp,
    output logic matrix_req_ready,
    output logic matrix_resp_valid,
    output xj_pkg::matrix_beat_resp_t matrix_resp,
    output logic host_idle
);
    localparam int C_DTCM_BYTES = 4*G_BANK_BYTES;
    localparam int C_ITCM_ADDR_WIDTH = $clog2(G_ITCM_BYTES/4);
    localparam int C_DTCM_ADDR_WIDTH = $clog2(G_BANK_BYTES/64);
    localparam logic [31:0] C_DTCM_BASE = 32'h10000000;
    typedef enum logic [1:0] {
        WR_IDLE = 2'd0, WR_BURST = 2'd1, WR_RESPONSE = 2'd2
    } write_state_t;
    typedef enum logic [1:0] {
        RD_IDLE = 2'd0, RD_ISSUE = 2'd1, RD_PENDING = 2'd2, RD_RESPONSE = 2'd3
    } read_state_t;
    typedef enum logic [1:0] {
        SLOT_IDLE = 2'd0, SLOT_PENDING = 2'd1, SLOT_FULL = 2'd2
    } slot_state_t;
    write_state_t f_write_state;
    write_state_t d_write_state;
    read_state_t f_read_state;
    read_state_t d_read_state;
    slot_state_t f_data_state;
    slot_state_t d_data_state;
    slot_state_t f_matrix_state;
    slot_state_t d_matrix_state;
    typedef struct packed {
        logic [3:0] id;
        logic [31:0] addr;
        logic [7:0] len;
        logic [2:0] size;
        logic [1:0] burst;
    } burst_t;
    burst_t f_aw;
    burst_t f_ar;
    logic [31:0] f_write_addr;
    logic [31:0] f_read_addr;
    logic [7:0] f_write_beat;
    logic [7:0] f_read_beat;
    logic f_write_error;
    logic f_read_error;
    logic f_write_valid;
    xj_pkg::axi_write_data_t f_write;
    logic f_write_busy;
    xj_pkg::axi_write_response_t f_b;
    xj_pkg::axi_read_data_t f_r;
    logic f_read_was_fault;
    logic f_read_was_itcm;
    logic [1:0] f_read_bank;
    logic [3:0] f_read_word;
    logic [3:0] f_read_lanes;
    logic f_fetch_valid;
    logic [31:0] f_fetch_pc;
    logic f_fetch_error;
    logic [3:0] f_fetch_cause;
    logic f_data_error;
    logic f_data_write;
    logic [1:0] f_data_bank;
    logic [3:0] f_data_word;
    xj_pkg::word_resp_t f_data;
    logic f_matrix_error;
    logic f_matrix_write;
    logic [1:0] f_matrix_bank;
    xj_pkg::matrix_beat_resp_t f_matrix;
    logic d_aw_fire;
    logic d_w_fire;
    logic d_ar_fire;
    logic d_fetch_error;
    logic d_fetch_access;
    logic d_data_fire;
    logic d_data_error;
    logic d_data_access;
    logic d_matrix_fire;
    logic d_matrix_error;
    logic d_matrix_access;
    logic d_core_data_access;
    logic d_write_fault;
    logic d_write_itcm;
    logic d_write_grant;
    logic d_host_write;
    logic d_read_fault;
    logic d_read_itcm;
    logic d_read_conflict;
    logic d_read_grant;
    logic d_host_read;
    logic d_itcm_enable;
    logic d_itcm_write;
    logic [C_ITCM_ADDR_WIDTH-1:0] d_itcm_word;
    logic [3:0] d_read_valid;
    logic [3:0] d_write_valid;
    logic [3:0][C_DTCM_ADDR_WIDTH-1:0] d_read_row;
    logic [3:0][C_DTCM_ADDR_WIDTH-1:0] d_write_row;
    logic [3:0][511:0] d_write_data;
    logic [3:0][63:0] d_write_mask;
    logic [31:0] w_itcm_data;
    logic [3:0][511:0] w_dtcm_data;
    logic [31:0] d_read_data;

    function automatic logic [33:0] beat_bytes(input logic [2:0] v_size);
        return 34'd1 << v_size;
    endfunction
    function automatic logic [33:0] burst_bytes(
        input logic [7:0] v_len, input logic [2:0] v_size
    );
        return (34'(v_len) + 34'd1) << v_size;
    endfunction
    function automatic logic burst_error(
        input logic [31:0] v_addr, input logic [7:0] v_len,
        input logic [2:0] v_size, input logic [1:0] v_burst, input logic v_lock
    );
        logic [33:0] v_bytes;
        logic [33:0] v_span;
        logic [33:0] v_lower;
        logic [33:0] v_upper;
        logic v_wrap_length;
        logic v_in_itcm;
        logic v_in_dtcm;
        v_bytes = beat_bytes(v_size);
        v_span = burst_bytes(v_len, v_size);
        v_lower = v_burst == 2'd2 ? 34'(v_addr) & ~(v_span - 34'd1) : 34'(v_addr);
        v_upper = v_lower + (v_burst == 2'd0 ? v_bytes : v_span) - 34'd1;
        v_wrap_length = v_len == 8'd1 || v_len == 8'd3 || v_len == 8'd7 || v_len == 8'd15;
        v_in_itcm = v_lower < 34'(G_ITCM_BYTES) && v_upper < 34'(G_ITCM_BYTES);
        v_in_dtcm = v_lower >= 34'(C_DTCM_BASE)
            && v_upper < 34'(C_DTCM_BASE) + 34'(C_DTCM_BYTES);
        return v_size > 3'd2 || ((34'(v_addr) & (v_bytes - 34'd1)) != 34'd0)
            || v_lock || v_burst == 2'd3 || (v_burst == 2'd0 && v_len > 8'd15)
            || (v_burst == 2'd2 && !v_wrap_length) || v_lower[33:12] != v_upper[33:12]
            || !(v_in_itcm || v_in_dtcm);
    endfunction
    function automatic logic [31:0] next_address(
        input logic [31:0] v_start, input logic [7:0] v_len,
        input logic [2:0] v_size, input logic [1:0] v_burst, input logic [31:0] v_current
    );
        logic [33:0] v_next;
        logic [33:0] v_mask;
        v_next = 34'(v_current) + beat_bytes(v_size);
        v_mask = burst_bytes(v_len, v_size) - 34'd1;
        return v_burst == 2'd0 ? v_current : v_burst == 2'd2
            ? 32'((34'(v_start) & ~v_mask) | (v_next & v_mask)) : 32'(v_next);
    endfunction
    function automatic logic [3:0] lanes(input logic [2:0] v_size, input logic [1:0] v_offset);
        return v_size == 3'd0 ? 4'b0001 << v_offset
            : v_size == 3'd1 ? 4'b0011 << v_offset : 4'hf;
    endfunction

    instruction_tcm #(.G_DEPTH(G_ITCM_BYTES/4)) i_instruction_tcm (
        .clk(clk), .enable(d_itcm_enable), .write(d_itcm_write), .word(d_itcm_word),
        .data(f_write.data), .mask(f_write.strb), .read_data(w_itcm_data)
    );
    data_tcm #(.G_DEPTH(G_BANK_BYTES/64)) i_data_tcm (
        .clk(clk), .read_valid(d_read_valid), .write_valid(d_write_valid),
        .read_row(d_read_row), .write_row(d_write_row), .write_data(d_write_data),
        .write_mask(d_write_mask), .read_data(w_dtcm_data)
    );

    assign data_req_ready = f_data_state == SLOT_IDLE;
    assign matrix_req_ready = f_matrix_state == SLOT_IDLE;
    always_comb begin
        axi_aw_ready = f_write_state == WR_IDLE && !bus_poisoned;
        axi_w_ready = !f_write_valid && f_write_state != WR_RESPONSE
            && (!bus_poisoned || f_write_state == WR_BURST);
        axi_b_valid = f_write_state == WR_RESPONSE;
        axi_b = f_b;
        axi_ar_ready = f_read_state == RD_IDLE && !bus_poisoned;
        axi_r_valid = f_read_state == RD_RESPONSE;
        axi_r = f_r;
        host_idle = f_write_state == WR_IDLE && !f_write_valid && f_read_state == RD_IDLE
            && !axi_aw_valid && !axi_w_valid && !axi_ar_valid;
        d_aw_fire = axi_aw_valid && axi_aw_ready;
        d_w_fire = axi_w_valid && axi_w_ready;
        d_ar_fire = axi_ar_valid && axi_ar_ready;
        fetch_req_ready = 1'b1;
        d_fetch_error = (|fetch_req[1:0]) || fetch_req >= G_ITCM_BYTES;
        d_fetch_access = fetch_req_valid && !d_fetch_error;
        fetch_resp_valid = f_fetch_valid;
        fetch_resp = '{pc:f_fetch_pc, inst:(f_fetch_error ? 32'd0 : w_itcm_data),
            exception:f_fetch_error, cause:f_fetch_cause, bad_addr:f_fetch_pc};
        data_resp_valid = f_data_state == SLOT_FULL;
        data_resp = f_data;
        d_data_fire = data_req_valid && data_req_ready;
        d_data_error = (|data_req.addr[1:0]) || data_req.addr >= C_DTCM_BYTES;
        d_data_access = d_data_fire && !d_data_error;
        matrix_resp_valid = f_matrix_state == SLOT_FULL;
        matrix_resp = f_matrix;
        d_matrix_fire = matrix_req_valid && matrix_req_ready;
        d_matrix_error = (|matrix_req.addr[5:0]) || matrix_req.addr >= C_DTCM_BYTES;
        d_matrix_access = d_matrix_fire && !d_matrix_error;
        d_core_data_access = d_data_access || d_matrix_access;
        d_write_fault = f_write_error || f_write_busy || core_busy ||
            ((f_write.strb & ~lanes(f_aw.size, f_write_addr[1:0])) != 4'd0)
            || f_write.last != (f_write_beat == f_aw.len);
        d_write_itcm = f_write_addr < G_ITCM_BYTES;
        d_write_grant = f_write_state == WR_BURST && f_write_valid
            && (d_write_fault || (d_write_itcm ? !d_fetch_access : !d_core_data_access));
        d_host_write = d_write_grant && !d_write_fault;
        d_read_fault = f_read_error || core_busy;
        d_read_itcm = f_read_addr < G_ITCM_BYTES;
        d_read_conflict = d_read_itcm ? d_fetch_access || (d_host_write && d_write_itcm)
            : d_core_data_access || (d_host_write && !d_write_itcm
                && f_write_addr[31:6] == f_read_addr[31:6]);
        d_read_grant = f_read_state == RD_ISSUE && (d_read_fault || !d_read_conflict);
        d_host_read = d_read_grant && !d_read_fault;
        d_read_data = f_read_was_itcm ? w_itcm_data
            : w_dtcm_data[f_read_bank][32*int'(f_read_word)+:32];
        for (int v_byte = 0; v_byte < 4; v_byte++)
            d_read_data[8*v_byte+:8] = d_read_data[8*v_byte+:8] & {8{f_read_lanes[v_byte]}};
        d_itcm_enable = d_fetch_access || (d_host_write && d_write_itcm)
            || (d_host_read && d_read_itcm);
        d_itcm_write = d_host_write && d_write_itcm;
        d_itcm_word = C_ITCM_ADDR_WIDTH'((d_fetch_access ? fetch_req
            : (d_host_write && d_write_itcm) ? f_write_addr : f_read_addr) >> 2);
        d_read_valid = '0;
        d_write_valid = '0;
        d_read_row = '0;
        d_write_row = '0;
        d_write_data = '0;
        d_write_mask = '0;
        for (int v_bank = 0; v_bank < 4; v_bank++) begin
            logic v_scalar_read;
            logic v_scalar_write;
            logic v_matrix_read;
            logic v_matrix_write;
            logic [31:0] v_write_addr;
            logic [31:0] v_read_addr;
            v_scalar_read = d_data_access && !data_req.write && data_req.addr[7:6] == 2'(v_bank);
            v_scalar_write = d_data_access && data_req.write && data_req.addr[7:6] == 2'(v_bank);
            v_matrix_read = d_matrix_access && !matrix_req.write
                && matrix_req.addr[7:6] == 2'(v_bank);
            v_matrix_write = d_matrix_access && matrix_req.write
                && matrix_req.addr[7:6] == 2'(v_bank);
            v_write_addr = v_scalar_write ? data_req.addr
                : v_matrix_write ? matrix_req.addr : f_write_addr - C_DTCM_BASE;
            v_read_addr = v_scalar_read ? data_req.addr
                : v_matrix_read ? matrix_req.addr : f_read_addr - C_DTCM_BASE;
            d_read_valid[v_bank] = v_scalar_read || v_matrix_read
                || (d_host_read && !d_read_itcm && f_read_addr[7:6] == 2'(v_bank));
            d_write_valid[v_bank] = v_scalar_write || v_matrix_write
                || (d_host_write && !d_write_itcm && f_write_addr[7:6] == 2'(v_bank));
            d_read_row[v_bank] = C_DTCM_ADDR_WIDTH'(v_read_addr >> 8);
            d_write_row[v_bank] = C_DTCM_ADDR_WIDTH'(v_write_addr >> 8);
            d_write_data[v_bank] = v_matrix_write ? matrix_req.data
                : {16{v_scalar_write ? data_req.data : f_write.data}};
            d_write_mask[v_bank] = v_matrix_write ? matrix_req.mask
                : (64'(v_scalar_write ? data_req.mask : f_write.strb) <<
                    {v_write_addr[5:2], 2'd0});
        end
    end

    always_comb begin
        d_write_state = f_write_state;
        d_read_state = f_read_state;
        d_data_state = f_data_state;
        d_matrix_state = f_matrix_state;
        unique case (f_write_state)
            WR_IDLE: if (d_aw_fire) d_write_state = WR_BURST;
            WR_BURST: if (d_write_grant && f_write_beat == f_aw.len)
                d_write_state = WR_RESPONSE;
            WR_RESPONSE: if (axi_b_ready) d_write_state = WR_IDLE;
        endcase
        unique case (f_read_state)
            RD_IDLE: if (d_ar_fire) d_read_state = RD_ISSUE;
            RD_ISSUE: if (d_read_grant) d_read_state = RD_PENDING;
            RD_PENDING: d_read_state = RD_RESPONSE;
            RD_RESPONSE: if (axi_r_ready) d_read_state = f_r.last ? RD_IDLE : RD_ISSUE;
        endcase
        unique case (f_data_state)
            SLOT_IDLE: if (d_data_fire) d_data_state = SLOT_PENDING;
            SLOT_PENDING: d_data_state = SLOT_FULL;
            SLOT_FULL: if (data_resp_ready) d_data_state = SLOT_IDLE;
        endcase
        unique case (f_matrix_state)
            SLOT_IDLE: if (d_matrix_fire) d_matrix_state = SLOT_PENDING;
            SLOT_PENDING: d_matrix_state = SLOT_FULL;
            SLOT_FULL: if (matrix_resp_ready) d_matrix_state = SLOT_IDLE;
        endcase
    end

    always_ff @(posedge clk) begin
        if (reset) begin
            f_write_state <= WR_IDLE;
            f_read_state <= RD_IDLE;
            f_data_state <= SLOT_IDLE;
            f_matrix_state <= SLOT_IDLE;
        end else begin
            f_write_state <= d_write_state;
            f_read_state <= d_read_state;
            f_data_state <= d_data_state;
            f_matrix_state <= d_matrix_state;
        end
    end

    always_ff @(posedge clk) begin
        if (reset) begin
            f_write_valid <= 1'b0;
            f_write_beat <= '0;
            f_read_beat <= '0;
            f_write_error <= 1'b0;
            f_read_error <= 1'b0;
            f_fetch_valid <= 1'b0;
        end else begin
            f_fetch_valid <= fetch_req_valid;
            if (d_aw_fire) begin
                f_write_beat <= '0;
                f_write_error <= burst_error(axi_aw.addr, axi_aw.len, axi_aw.size,
                    axi_aw.burst, axi_aw.lock) || core_busy;
            end
            if (d_w_fire) f_write_valid <= 1'b1;
            if (d_write_grant) begin
                f_write_valid <= 1'b0;
                f_write_error <= d_write_fault;
                if (f_write_beat != f_aw.len) f_write_beat <= f_write_beat + 8'd1;
            end
            if (d_ar_fire) begin
                f_read_beat <= '0;
                f_read_error <= burst_error(axi_ar.addr, axi_ar.len, axi_ar.size,
                    axi_ar.burst, axi_ar.lock) || core_busy;
            end
            if (axi_r_valid && axi_r_ready && !f_r.last) f_read_beat <= f_read_beat + 8'd1;
            if (d_read_grant && core_busy) f_read_error <= 1'b1;
        end
    end

    always_ff @(posedge clk) begin
        if (d_aw_fire) begin
            f_aw <= '{id:axi_aw.id, addr:axi_aw.addr, len:axi_aw.len,
                size:axi_aw.size, burst:axi_aw.burst};
            f_write_addr <= axi_aw.addr;
        end
        if (d_w_fire) begin
            f_write <= axi_w;
            f_write_busy <= core_busy;
        end
        if (d_write_grant) begin
            if (f_write_beat == f_aw.len) begin
                f_b.id <= f_aw.id;
                f_b.resp <= d_write_fault ? 2'd2 : 2'd0;
            end else f_write_addr <= next_address(f_aw.addr, f_aw.len,
                f_aw.size, f_aw.burst, f_write_addr);
        end
        if (d_ar_fire) begin
            f_ar <= '{id:axi_ar.id, addr:axi_ar.addr, len:axi_ar.len,
                size:axi_ar.size, burst:axi_ar.burst};
            f_read_addr <= axi_ar.addr;
        end
        if (axi_r_valid && axi_r_ready && !f_r.last)
            f_read_addr <= next_address(f_ar.addr, f_ar.len, f_ar.size, f_ar.burst, f_read_addr);
        if (d_read_grant) begin
            f_read_was_fault <= d_read_fault;
            f_read_was_itcm <= d_read_itcm;
            f_read_bank <= f_read_addr[7:6];
            f_read_word <= f_read_addr[5:2];
            f_read_lanes <= lanes(f_ar.size, f_read_addr[1:0]);
        end
        if (f_read_state == RD_PENDING) begin
            f_r.id <= f_ar.id;
            f_r.last <= f_read_beat == f_ar.len;
            f_r.resp <= f_read_was_fault ? 2'd2 : 2'd0;
            f_r.data <= f_read_was_fault ? 32'd0 : d_read_data;
        end
        if (fetch_req_valid) begin
            f_fetch_pc <= fetch_req;
            f_fetch_error <= d_fetch_error;
            f_fetch_cause <= (|fetch_req[1:0]) ? 4'd2
                : fetch_req >= G_ITCM_BYTES ? 4'd3 : 4'd0;
        end
        if (d_data_fire) begin
            f_data_error <= d_data_error;
            f_data_write <= data_req.write;
            f_data_bank <= data_req.addr[7:6];
            f_data_word <= data_req.addr[5:2];
        end
        if (f_data_state == SLOT_PENDING) begin
            f_data.error <= f_data_error;
            f_data.data <= f_data_error || f_data_write ? 32'd0
                : w_dtcm_data[f_data_bank][32*int'(f_data_word)+:32];
        end
        if (d_matrix_fire) begin
            f_matrix_error <= d_matrix_error;
            f_matrix_write <= matrix_req.write;
            f_matrix_bank <= matrix_req.addr[7:6];
        end
        if (f_matrix_state == SLOT_PENDING) begin
            f_matrix.error <= f_matrix_error;
            f_matrix.data <= f_matrix_error || f_matrix_write ? '0 : w_dtcm_data[f_matrix_bank];
        end
    end
endmodule
`default_nettype wire
