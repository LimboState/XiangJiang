module npu_tb;
    logic clk = 0;
    logic reset = 1;
    logic control_aw_valid = 0;
    logic [31:0] control_aw_addr = 0;
    logic [2:0] control_aw_prot = 0;
    logic [2:0] control_ar_prot = 0;
    logic control_w_valid = 0;
    logic [31:0] control_w_data = 0;
    logic [3:0] control_w_strb = 15;
    logic control_b_ready = 0;
    logic control_ar_valid = 0;
    logic [31:0] control_ar_addr = 0;
    logic control_r_ready = 0;
    logic memory_aw_valid = 0;
    xj_pkg::axi_address_t memory_aw;
    logic memory_w_valid = 0;
    xj_pkg::axi_write_data_t memory_w;
    logic memory_b_ready = 0;
    logic memory_ar_valid = 0;
    xj_pkg::axi_address_t memory_ar;
    logic memory_r_ready = 0;
    logic external_aw_ready = 0;
    logic external_w_ready = 0;
    logic external_b_valid = 0;
    xj_pkg::axi_write_response_t external_b;
    logic external_ar_ready = 0;
    logic external_r_valid = 0;
    xj_pkg::axi_read_data_t external_r;
    logic control_aw_ready;
    logic control_w_ready;
    logic control_b_valid;
    logic [1:0] control_b_resp;
    logic control_ar_ready;
    logic control_r_valid;
    logic [31:0] control_r_data;
    logic [1:0] control_r_resp;
    logic memory_aw_ready;
    logic memory_w_ready;
    logic memory_b_valid;
    xj_pkg::axi_write_response_t memory_b;
    logic memory_ar_ready;
    logic memory_r_valid;
    xj_pkg::axi_read_data_t memory_r;
    logic external_aw_valid;
    xj_pkg::axi_address_t external_aw;
    logic external_w_valid;
    xj_pkg::axi_write_data_t external_w;
    logic external_b_ready;
    logic external_ar_valid;
    xj_pkg::axi_address_t external_ar;
    logic external_r_ready;
    xj_pkg::core_status_t status;
    logic retire_valid;
    xj_pkg::commit_record_t retire;
    logic [31:0] debug_regs[32];
    logic [255:0] debug_v_regs[32];
    logic [31:0] program_words[1024];
    logic [31:0] external_memory[8192];
    int cycle = 0;
    int retired = 0;
    int runs = 0;
    int ext_reads = 0;
    int ext_writes = 0;
    int read_left = 0;
    int write_left = 0;
    logic [31:0] read_address;
    logic [31:0] write_address;
    logic pending_b = 0;
    logic inject_protocol = 0;
    int external_error = 0;
    xiangjiang_npu i_dut (.*);
    /* REFERENCE */

    function automatic logic [31:0] enc_i(input int opcode, rd, funct3, rs1, immediate);
        return (32'(immediate & 4095) << 20) | (32'(rs1) << 15) | (32'(funct3) << 12)
            | (32'(rd) << 7) | 32'(opcode);
    endfunction
    function automatic logic [31:0] addi(input int rd, rs1, immediate);
        return enc_i(19, rd, 0, rs1, immediate);
    endfunction
    function automatic logic [31:0] enc_s(input int opcode, funct3, rs2, rs1, immediate);
        return (32'((immediate >> 5) & 127) << 25) | (32'(rs2) << 20) | (32'(rs1) << 15)
            | (32'(funct3) << 12) | (32'(immediate & 31) << 7) | 32'(opcode);
    endfunction
    function automatic logic [31:0] enc_b(input int funct3, rs1, rs2, immediate);
        return (32'((immediate >> 12) & 1) << 31) | (32'((immediate >> 5) & 63) << 25)
            | (32'(rs2) << 20) | (32'(rs1) << 15) | (32'(funct3) << 12)
            | (32'((immediate >> 1) & 15) << 8) | (32'((immediate >> 11) & 1) << 7) | 32'h63;
    endfunction
    function automatic logic [31:0] enc_v(input int funct6, rd, rs1, rs2, funct3,
        input logic unmasked);
        return (32'(funct6) << 26) | (32'(unmasked) << 25) | (32'(rs2) << 20)
            | (32'(rs1) << 15) | (32'(funct3) << 12) | (32'(rd) << 7) | 32'h57;
    endfunction
    function automatic logic [31:0] enc_m(input int opcode, funct7, unit_id, rs1, rs2);
        return (32'(funct7) << 25) | (32'(rs2) << 20) | (32'(rs1) << 15)
            | (32'(unit_id) << 12) | 32'(opcode);
    endfunction
    function automatic logic [31:0] enc_dma(input int funct3, rs1, rs2);
        return (32'(rs2) << 20) | (32'(rs1) << 15) | (32'(funct3) << 12) | 32'h0b;
    endfunction

    always @(clk) begin
        #1;
        /* CHECKS */
    end
    task automatic tick;
        logic take_ar, take_aw, take_r, take_w, take_b;
        xj_pkg::axi_address_t saved_ar, saved_aw;
        logic [31:0] saved_write;
        logic saved_last;
        clk = 0;
        external_ar_ready = read_left == 0 && cycle % 3 != 0;
        external_aw_ready = write_left == 0 && !pending_b && cycle % 3 != 0;
        external_w_ready = write_left > 0 && cycle % 4 != 0;
        external_r_valid = read_left > 0 || inject_protocol;
        external_r = '0;
        external_r.data = external_memory[read_address[14:2]];
        external_r.last = read_left == 1;
        external_r.resp = external_error == 1 ? 2'd2 : 2'd0;
        external_b_valid = pending_b;
        external_b = '0;
        external_b.resp = external_error == 2 ? 2'd2 : 2'd0;
        #2;
        take_ar = external_ar_valid && external_ar_ready;
        take_aw = external_aw_valid && external_aw_ready;
        take_r = external_r_valid && external_r_ready;
        take_w = external_w_valid && external_w_ready;
        take_b = external_b_valid && external_b_ready;
        saved_ar = external_ar; saved_aw = external_aw;
        saved_write = external_w.data; saved_last = external_w.last;
        if (retire_valid) retired++;
        clk = 1;
        #2;
        clk = 0;
        if (take_r) begin read_left--; read_address += 4; end
        if (take_b) pending_b = 0;
        if (take_w) begin
            assert (saved_last == (write_left == 1)) else $fatal(1, "DMA WLAST");
            external_memory[write_address[14:2]] = saved_write;
            write_address += 4; write_left--;
            if (write_left == 0) pending_b = 1;
        end
        if (take_ar) begin
            read_left = int'(saved_ar.len) + 1; read_address = saved_ar.addr; ext_reads++;
            assert (read_left <= 64 && int'(read_address[11:0])+4*read_left <= 4096)
                else $fatal(1, "DMA AR boundaries");
        end
        if (take_aw) begin write_left = int'(saved_aw.len)+1; write_address = saved_aw.addr; ext_writes++; end
        cycle++;
        if (cycle > 250000) $fatal(1, "global timeout");
    endtask

    task automatic write_memory(input logic [31:0] address, value);
        memory_aw = '0; memory_aw.addr = address; memory_aw.size = 2;
        memory_aw.burst = 1; memory_aw.id = 3;
        memory_aw_valid = 1;
        #1;
        while (!memory_aw_ready) tick();
        tick(); memory_aw_valid = 0;
        memory_w = '{data:value, strb:4'hf, last:1'b1}; memory_w_valid = 1;
        #1;
        while (!memory_w_ready) tick();
        tick(); memory_w_valid = 0;
        while (!memory_b_valid) tick();
        assert (memory_b.resp == 0 && memory_b.id == 3) else $fatal(1, "host write");
        tick(); memory_b_ready = 1; tick(); memory_b_ready = 0;
    endtask
    task automatic read_memory(input logic [31:0] address, expected);
        memory_ar = '0; memory_ar.addr = address; memory_ar.size = 2;
        memory_ar.burst = 1; memory_ar.id = 2; memory_ar_valid = 1;
        #1;
        while (!memory_ar_ready) tick();
        tick(); memory_ar_valid = 0;
        while (!memory_r_valid) tick();
        assert (memory_r.resp == 0 && memory_r.id == 2 && memory_r.last && memory_r.data == expected)
            else $fatal(1, "host read addr=%h expected=%h actual=%h", address, expected, memory_r.data);
        tick(); memory_r_ready = 1; tick(); memory_r_ready = 0;
    endtask
    task automatic control(input logic [31:0] value);
        control_w_data = value; control_w_valid = 1;
        #1; while (!control_w_ready) tick();
        tick(); control_w_valid = 0;
        control_aw_valid = 1;
        #1; while (!control_aw_ready) tick();
        tick(); control_aw_valid = 0;
        while (!control_b_valid) tick();
        assert (control_b_resp == 0) else $fatal(1, "control command rejected %h", value);
        control_b_ready = 1; tick(); control_b_ready = 0;
    endtask
    task automatic run_program(input int length, expected_fault, expected_pc);
        int timeout_count;
        if (status.done || status.fault) control(2);
        for (int word = 0; word < length; word++) write_memory(32'(word*4), program_words[word]);
        control(1);
        timeout_count = 0;
        while (status.busy || !(status.done || status.fault)) begin
            tick(); timeout_count++;
            if (timeout_count > 30000) $fatal(1, "program timeout run=%0d", runs);
        end
        if (expected_fault == 0) assert (status.done && !status.fault) else $fatal(1, "expected DONE");
        else assert (status.fault && status.fault_cause == 5'(expected_fault)
            && status.fault_pc == 32'(expected_pc)) else $fatal(1, "expected fault %0d", expected_fault);
        runs++;
    endtask

    initial begin
        memory_aw = '0; memory_w = '0; memory_ar = '0; external_b = '0; external_r = '0;
        read_address = 0; write_address = 0;
        for (int word = 0; word < 8192; word++) external_memory[word] = 32'h12340000 + 32'(word);
        tick(); tick(); reset = 0;
        program_words[0] = 32'h0000000b;
        run_program(1, 0, 0);
        for (int word = 0; word < 128; word++) write_memory(32'h10000000+32'(word*4), 32'(word+1));
        program_words[0] = addi(1, 0, 0);
        program_words[1] = addi(2, 0, 4);
        program_words[2] = addi(3, 0, 0);
        program_words[3] = enc_i(3, 4, 2, 1, 0);
        program_words[4] = 32'h004181b3;
        program_words[5] = addi(1, 1, 4);
        program_words[6] = addi(2, 2, -1);
        program_words[7] = enc_b(1, 2, 0, -16);
        program_words[8] = enc_s(35, 2, 3, 0, 512);
        program_words[9] = 32'h0ff0000f;
        program_words[10] = enc_i(3, 5, 2, 0, 512);
        program_words[11] = 32'h0000000b;
        run_program(12, 0, 0);
        assert (debug_regs[3] == 10 && debug_regs[5] == 10) else $fatal(1, "scalar sum");
        read_memory(32'h10000200, 10);
        program_words[0] = addi(10, 0, 0);
        for (int word = 1; word <= 260; word++) program_words[word] = addi(10, 10, 1);
        program_words[261] = 32'h0000000b;
        run_program(262, 0, 0);
        assert (debug_regs[10] == 260) else $fatal(1, "generation drain");
        program_words[0] = addi(6, 0, 17);
        program_words[1] = enc_i(103, 6, 0, 6, 0);
        program_words[2] = 0;
        program_words[3] = addi(7, 0, 99);
        program_words[4] = addi(7, 6, 1);
        program_words[5] = 32'h0000000b;
        run_program(6, 0, 0);
        assert (debug_regs[6] == 8 && debug_regs[7] == 9) else $fatal(1, "JALR alias");
        program_words[0] = addi(9, 0, 27);
        program_words[1] = 32'h02000033;
        program_words[2] = enc_s(35, 2, 9, 0, 512);
        program_words[3] = 32'h0000000b;
        run_program(4, 1, 4); read_memory(32'h10000200, 10);
        program_words[0] = enc_i(3, 0, 2, 0, 2);
        run_program(4, 4, 0);
        program_words[0] = enc_s(35, 2, 9, 0, 2);
        run_program(4, 6, 0);
        program_words[0] = enc_m(91, 0, 0, 0, 0);
        run_program(4, 14, 0);

        program_words[0] = enc_i(11, 1, 1, 0, 0);
        program_words[1] = enc_i(11, 2, 1, 0, 32);
        program_words[2] = enc_v(0, 3, 1, 2, 0, 1);
        program_words[3] = enc_v(2, 4, 1, 3, 0, 1);
        program_words[4] = enc_s(11, 2, 4, 0, 640);
        program_words[5] = 32'h0ff0000f;
        program_words[6] = 32'h0000000b;
        run_program(7, 0, 0);
        for (int lane = 0; lane < 8; lane++)
            read_memory(32'h10000280+32'(lane*4), 32'((lane+1)*(2*lane+10)));
        for (int word = 0; word < 8; word++) begin
            write_memory(32'h10000000+32'(word*4), 32'h00003f80);
            write_memory(32'h10000020+32'(word*4), 32'h00004000);
        end
        for (int funct6 = 0; funct6 < 36; funct6++) begin
            int funct3;
            int source1;
            funct3 = funct6 >= 18 && funct6 <= 21 ? 2
                : funct6 == 22 || funct6 == 23 ? 3
                : funct6 == 25 || funct6 == 26 ? 1 : 0;
            source1 = funct3 == 2 ? 0 : 1;
            program_words[0] = enc_i(11, 1, 1, 0, 0);
            program_words[1] = enc_i(11, 2, 1, 0, 32);
            program_words[2] = enc_v(funct6, 3, source1, 2, funct3, funct6 != 35);
            program_words[3] = enc_s(11, 2, 3, 0, 640);
            program_words[4] = 32'h0000000b;
            run_program(5, 0, 0);
        end
        for (int unit_id = 0; unit_id < 3; unit_id++) begin
            for (int trial = 0; trial < 2; trial++) begin
                for (int word = 0; word < 32; word++) begin
                    write_memory(32'h10000000+32'(word*4), unit_id == 0 ? 32'h3f803f80 : 32'h01010101);
                    write_memory(32'h10000100+32'(word*4), unit_id == 0 ? 32'h40004000 : 32'h02020202);
                end
                program_words[0] = addi(11, 0, 0);
                program_words[1] = addi(12, 0, 256);
                program_words[2] = addi(13, 0, 768);
                program_words[3] = enc_m(43, 0, unit_id, 0, unit_id == 0 ? 0 : trial);
                program_words[4] = enc_m(43, 4, unit_id, 11, 0);
                program_words[5] = enc_m(43, 5, unit_id, 12, 0);
                program_words[6] = enc_m(91, 0, unit_id, 0, 0);
                program_words[7] = enc_m(43, 8, unit_id, 13, 0);
                program_words[8] = 32'h0000000b;
                run_program(9, 0, 0);
                for (int word = 0; word < (unit_id == 0 ? 32 : 64); word++)
                    read_memory(32'h10000300+32'(word*4), unit_id == 0 ? 32'h41804180 : 32'd16);
                program_words[0] = enc_m(91, 1, unit_id, 0, 0);
                program_words[1] = enc_m(43, 8, unit_id, 13, 0);
                program_words[2] = 32'h0000000b;
                run_program(3, 0, 0);
                read_memory(32'h10000300, unit_id == 0 ? 32'h42004200 : 32'd32);
            end
        end
        program_words[0] = addi(1, 0, 272);
        program_words[1] = addi(2, 0, 2);
        program_words[2] = enc_dma(3, 1, 2);
        program_words[3] = addi(3, 0, 288);
        program_words[4] = enc_dma(4, 3, 3);
        program_words[5] = 32'h00001a37;
        program_words[6] = addi(20, 20, -16);
        program_words[7] = addi(21, 0, 1024);
        program_words[8] = enc_dma(5, 20, 21);
        program_words[9] = 32'h00002a37;
        program_words[10] = enc_dma(6, 20, 21);
        program_words[11] = 32'h0ff0000f;
        program_words[12] = 32'h0000000b;
        run_program(13, 0, 0);
        assert (status.dma.bytes_done == 544 && status.dma.rows_done == 2)
            else $fatal(1, "DMA progress");
        for (int row = 0; row < 2; row++)
            for (int word = 0; word < 68; word++)
                assert (external_memory[(8192+row*288)/4+word]
                    == external_memory[(4080+row*288)/4+word]) else $fatal(1, "DMA copy");
        external_error = 1;
        program_words[0] = enc_dma(5, 20, 21);
        program_words[1] = 32'h0000000b;
        run_program(2, 16, 0);
        external_error = 2;
        program_words[0] = enc_dma(6, 20, 21);
        run_program(2, 17, 0);
        external_error = 0;
        control(2);
        inject_protocol = 1; tick(); inject_protocol = 0;
        repeat (4) tick();
        assert (status.dma.poisoned && status.busy && !status.done && !status.fault)
            else $fatal(1, "poison lifecycle");
        assert (runs > 50 && retired > 400 && ext_reads > 2 && ext_writes > 2)
            else $fatal(1, "insufficient program coverage");
        $display("PASS NPU: %0d cycles, %0d programs, %0d retirements, AR=%0d AW=%0d",
            cycle, runs, retired, ext_reads, ext_writes);
        $finish;
    end
endmodule
