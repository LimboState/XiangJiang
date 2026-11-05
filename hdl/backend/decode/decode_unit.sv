// Strict XJ-ISA decode. Internal operation codes match Bundle.scala, not ISA opcodes.
`default_nettype none
module decode_unit (
    input xj_pkg::fetch_packet_t packet,
    output xj_pkg::micro_op_t uop
);
    logic [31:0] d_inst;
    logic [6:0] d_opcode;
    logic [2:0] d_funct3;
    logic [6:0] d_funct7;
    logic [5:0] d_funct6;
    logic [31:0] d_imm_i;
    logic [31:0] d_imm_s;
    logic [31:0] d_imm_b;
    logic [31:0] d_imm_u;
    logic [31:0] d_imm_j;
    logic d_legal;
    logic d_destination;
    logic d_paired;
    logic d_unary;
    logic d_slide;
    logic d_reduction;
    logic d_merge;
    logic d_vector_legal;

    always_comb begin
        d_inst = packet.inst;
        d_opcode = d_inst[6:0];
        d_funct3 = d_inst[14:12];
        d_funct7 = d_inst[31:25];
        d_funct6 = d_inst[31:26];
        d_imm_i = {{20{d_inst[31]}}, d_inst[31:20]};
        d_imm_s = {{20{d_inst[31]}}, d_inst[31:25], d_inst[11:7]};
        d_imm_b = {{19{d_inst[31]}}, d_inst[31], d_inst[7],
            d_inst[30:25], d_inst[11:8], 1'b0};
        d_imm_u = {d_inst[31:12], 12'd0};
        d_imm_j = {{11{d_inst[31]}}, d_inst[31], d_inst[19:12],
            d_inst[20], d_inst[30:21], 1'b0};
        d_legal = 1'b0;
        d_destination = 1'b0;
        d_paired = (d_funct6 <= 6'h11) || ((d_funct6 >= 6'h1b) && (d_funct6 <= 6'h22));
        d_unary = (d_funct6 >= 6'h12) && (d_funct6 <= 6'h15);
        d_slide = (d_funct6 == 6'h16) || (d_funct6 == 6'h17);
        d_reduction = (d_funct6 == 6'h19) || (d_funct6 == 6'h1a);
        d_merge = (d_funct6 == 6'h23);
        d_vector_legal = (d_paired && ((d_funct3 == 3'd0) || (d_funct3 == 3'd4)))
            || (d_unary && (d_funct3 == 3'd2) && (d_inst[19:15] == 5'd0))
            || (d_slide && ((d_funct3 == 3'd4) || (d_funct3 == 3'd3)))
            || ((d_funct6 == 6'h18) && (d_funct3 == 3'd0))
            || (d_reduction && (d_funct3 == 3'd1))
            || (d_merge && (d_funct3 == 3'd0) && !d_inst[25]);
        uop = '0;
        uop.inst = d_inst;
        uop.pc = packet.pc;
        uop.rs1 = d_inst[19:15];
        uop.rs2 = d_inst[24:20];
        uop.rd = d_inst[11:7];
        uop.op = xj_pkg::OP_ADD;
        uop.fu = xj_pkg::FU_ALU;
        case (d_opcode)
            7'h33: begin
                case ({d_funct7, d_funct3})
                    10'h000: begin d_legal = 1'b1; uop.op = xj_pkg::OP_ADD; end
                    10'h100: begin d_legal = 1'b1; uop.op = xj_pkg::OP_SUB; end
                    10'h007: begin d_legal = 1'b1; uop.op = xj_pkg::OP_AND; end
                    10'h006: begin d_legal = 1'b1; uop.op = xj_pkg::OP_OR; end
                    10'h004: begin d_legal = 1'b1; uop.op = xj_pkg::OP_XOR; end
                    10'h001: begin d_legal = 1'b1; uop.op = xj_pkg::OP_SLL; end
                    10'h005: begin d_legal = 1'b1; uop.op = xj_pkg::OP_SRL; end
                    10'h105: begin d_legal = 1'b1; uop.op = xj_pkg::OP_SRA; end
                    10'h002: begin d_legal = 1'b1; uop.op = xj_pkg::OP_SLT; end
                    10'h003: begin d_legal = 1'b1; uop.op = xj_pkg::OP_SLTU; end
                    default: begin end
                endcase
                if (d_legal) begin
                    uop.uses_rs1 = 1'b1; uop.uses_rs2 = 1'b1; d_destination = 1'b1;
                end
            end
            7'h13: begin
                case (d_funct3)
                    3'd0: begin d_legal = 1'b1; uop.op = xj_pkg::OP_ADDI; end
                    3'd7: begin d_legal = 1'b1; uop.op = xj_pkg::OP_ANDI; end
                    3'd6: begin d_legal = 1'b1; uop.op = xj_pkg::OP_ORI; end
                    3'd4: begin d_legal = 1'b1; uop.op = xj_pkg::OP_XORI; end
                    3'd2: begin d_legal = 1'b1; uop.op = xj_pkg::OP_SLTI; end
                    3'd3: begin d_legal = 1'b1; uop.op = xj_pkg::OP_SLTIU; end
                    3'd1: if (d_funct7 == 7'h00) begin
                        d_legal = 1'b1; uop.op = xj_pkg::OP_SLLI;
                    end
                    3'd5: begin
                        if (d_funct7 == 7'h00) begin
                            d_legal = 1'b1; uop.op = xj_pkg::OP_SRLI;
                        end else if (d_funct7 == 7'h20) begin
                            d_legal = 1'b1; uop.op = xj_pkg::OP_SRAI;
                        end
                    end
                    default: begin end
                endcase
                if (d_legal) begin
                    uop.uses_rs1 = 1'b1; d_destination = 1'b1; uop.use_imm = 1'b1;
                    uop.imm = ((d_funct3 == 3'd1) || (d_funct3 == 3'd5))
                        ? {27'd0, d_inst[24:20]} : d_imm_i;
                end
            end
            7'h37, 7'h17: begin
                d_legal = 1'b1; d_destination = 1'b1; uop.use_imm = 1'b1;
                uop.op = (d_opcode == 7'h37) ? xj_pkg::OP_LUI : xj_pkg::OP_AUIPC;
                uop.imm = d_imm_u;
            end
            7'h03, 7'h23: if (d_funct3 == 3'd2) begin
                d_legal = 1'b1; uop.fu = xj_pkg::FU_MEM; uop.uses_rs1 = 1'b1;
                uop.use_imm = 1'b1;
                uop.op = (d_opcode == 7'h03) ? xj_pkg::OP_LW : xj_pkg::OP_SW;
                d_destination = (d_opcode == 7'h03);
                uop.uses_rs2 = (d_opcode == 7'h23);
                uop.imm = (d_opcode == 7'h03) ? d_imm_i : d_imm_s;
            end
            7'h63: begin
                case (d_funct3)
                    3'd0: begin d_legal = 1'b1; uop.op = xj_pkg::OP_BEQ; end
                    3'd1: begin d_legal = 1'b1; uop.op = xj_pkg::OP_BNE; end
                    3'd4: begin d_legal = 1'b1; uop.op = xj_pkg::OP_BLT; end
                    3'd5: begin d_legal = 1'b1; uop.op = xj_pkg::OP_BGE; end
                    3'd6: begin d_legal = 1'b1; uop.op = xj_pkg::OP_BLTU; end
                    3'd7: begin d_legal = 1'b1; uop.op = xj_pkg::OP_BGEU; end
                    default: begin end
                endcase
                if (d_legal) begin
                    uop.fu = xj_pkg::FU_BRU; uop.uses_rs1 = 1'b1;
                    uop.uses_rs2 = 1'b1; uop.imm = d_imm_b;
                end
            end
            7'h6f, 7'h67: if ((d_opcode == 7'h6f) || (d_funct3 == 3'd0)) begin
                d_legal = 1'b1; d_destination = 1'b1; uop.fu = xj_pkg::FU_BRU;
                uop.op = (d_opcode == 7'h6f) ? xj_pkg::OP_JAL : xj_pkg::OP_JALR;
                uop.uses_rs1 = (d_opcode == 7'h67); uop.use_imm = 1'b1;
                uop.imm = (d_opcode == 7'h6f) ? d_imm_j : d_imm_i;
            end
            7'h0f: if (d_funct3 == 3'd0) begin
                d_legal = 1'b1; uop.op = xj_pkg::OP_FENCE; uop.fu = xj_pkg::FU_SYSTEM;
            end
            7'h2b: if (d_funct3 <= 3'd2) begin
                if ((d_funct7 == 7'd0) && (d_inst[24:21] == 4'd0)
                    && (uop.rs1 == 5'd0) && (uop.rd == 5'd0)) begin
                    d_legal = !((d_funct3 == 3'd0) && d_inst[20]);
                    uop.op = xj_pkg::OP_MATRIX; uop.fu = xj_pkg::FU_SYSTEM;
                    uop.mat_mode = d_inst[20]; uop.mat_unit = d_funct3[1:0];
                end else if ((d_inst[31:27] == 5'd1) && (d_inst[26:25] != 2'd3)
                    && (uop.rs2 == 5'd0) && (uop.rd == 5'd0)) begin
                    d_legal = 1'b1; uop.op = xj_pkg::OP_MATRIX; uop.fu = xj_pkg::FU_MEM;
                    uop.uses_rs1 = 1'b1; uop.mat_op = xj_pkg::MAT_LOAD;
                    uop.mat_unit = d_funct3[1:0]; uop.mat_buffer = d_inst[26:25];
                end else if ((d_funct7 == 7'd8) && (uop.rs2 == 5'd0) && (uop.rd == 5'd0)) begin
                    d_legal = 1'b1; uop.op = xj_pkg::OP_MATRIX; uop.fu = xj_pkg::FU_MEM;
                    uop.uses_rs1 = 1'b1; uop.mat_op = xj_pkg::MAT_STORE;
                    uop.mat_unit = d_funct3[1:0];
                end
            end
            7'h5b: if ((d_funct3 <= 3'd2) && (uop.rs1 == 5'd0) && (uop.rs2 == 5'd0)
                && (uop.rd == 5'd0) && (d_funct7 <= 7'd3)) begin
                d_legal = 1'b1; uop.op = xj_pkg::OP_MATRIX; uop.fu = xj_pkg::FU_MATRIX;
                uop.mat_unit = d_funct3[1:0];
                uop.mat_op = xj_pkg::mat_op_t'(4'd3 + {2'd0, d_funct7[1:0]});
            end
            7'h7b: begin
                if ((d_funct3 == 3'd0) && (d_inst[31:30] != 2'd3)
                    && (d_inst[29:15] == 15'd0) && (uop.rd == 5'd0)) begin
                    d_legal = 1'b1; uop.op = xj_pkg::OP_MATRIX; uop.fu = xj_pkg::FU_MATRIX;
                    uop.mat_op = xj_pkg::MAT_WAIT; uop.mat_unit = d_inst[31:30];
                end else if ((d_funct3 == 3'd1) && (d_inst[31:15] == 17'd0)
                    && (uop.rd == 5'd0)) begin
                    d_legal = 1'b1; uop.op = xj_pkg::OP_MATRIX; uop.fu = xj_pkg::FU_SYSTEM;
                    uop.mat_op = xj_pkg::MAT_FENCE; uop.mat_unit = d_funct3[1:0];
                end else if ((d_funct3 == 3'd2) && (d_inst[26:15] == 12'd0)
                    && (uop.rd == 5'd0)) begin
                    d_legal = 1'b1; uop.op = xj_pkg::OP_MATRIX; uop.fu = xj_pkg::FU_SYSTEM;
                    uop.mat_op = xj_pkg::MAT_BARRIER; uop.mat_unit = d_funct3[1:0];
                    uop.mat_id = d_inst[31:27];
                end
            end
            7'h57: if (d_vector_legal) begin
                d_legal = 1'b1; uop.op = xj_pkg::OP_VECTOR; uop.fu = xj_pkg::FU_VECTOR;
                uop.vec_op = 6'd58;
                if (d_funct6 <= 6'h11) uop.vec_op = (d_funct6 << 1) + 6'(d_funct3 == 3'd4);
                else if ((d_funct6 >= 6'h1b) && (d_funct6 <= 6'h22))
                    uop.vec_op = 6'd36 + ((d_funct6 - 6'h1b) << 1) + 6'(d_funct3 == 3'd4);
                else begin
                    case (d_funct6)
                        6'h16: uop.vec_op = (d_funct3 == 3'd3) ? 6'd53 : 6'd52;
                        6'h17: uop.vec_op = (d_funct3 == 3'd3) ? 6'd55 : 6'd54;
                        6'h18: uop.vec_op = 6'd57;
                        6'h19: uop.vec_op = 6'd61;
                        6'h1a: uop.vec_op = 6'd62;
                        6'h23: uop.vec_op = 6'd56;
                        default: begin end
                    endcase
                end
                uop.vec_unary = 2'(d_funct6 - 6'h12);
                uop.unmasked = d_inst[25]; uop.uses_rs1 = (d_funct3 == 3'd4);
                uop.uses_v1 = (d_funct3 == 3'd0) || d_reduction; uop.uses_v2 = 1'b1;
                uop.uses_v_mask = !d_inst[25];
                uop.uses_v_old = d_reduction || (!d_merge && (!d_inst[25] || (d_funct6 == 6'h0f)));
                uop.writes_v = 1'b1; uop.imm = {27'd0, d_inst[19:15]};
                uop.use_imm = d_slide && (d_funct3 == 3'd3);
            end
            7'h0b: begin
                if (d_inst == 32'h0000000b) begin
                    d_legal = 1'b1; uop.op = xj_pkg::OP_HALT; uop.fu = xj_pkg::FU_SYSTEM;
                end
                if ((d_funct3 == 3'd1) || (d_funct3 == 3'd2)) begin
                    d_legal = 1'b1; uop.op = xj_pkg::OP_VECTOR; uop.fu = xj_pkg::FU_MEM;
                    uop.vec_op = (d_funct3 == 3'd2) ? 6'd60 : 6'd59;
                    uop.uses_rs1 = 1'b1; uop.uses_v_old = (d_funct3 == 3'd2);
                    uop.writes_v = (d_funct3 == 3'd1); uop.unmasked = 1'b1;
                    uop.imm = (d_funct3 == 3'd2) ? d_imm_s : d_imm_i; uop.use_imm = 1'b1;
                end
                if ((d_funct7 == 7'd0) && (uop.rd == 5'd0)
                    && (d_funct3 >= 3'd3) && (d_funct3 <= 3'd6)) begin
                    d_legal = 1'b1;
                    uop.op = xj_pkg::opcode_t'(6'd35 + 6'(d_funct3 - 3'd3));
                    uop.fu = (d_funct3 <= 3'd4) ? xj_pkg::FU_DMA_CONFIG : xj_pkg::FU_MEM;
                    uop.uses_rs1 = 1'b1; uop.uses_rs2 = 1'b1;
                end
            end
            default: begin end
        endcase
        uop.exception = packet.exception || !d_legal;
        uop.cause = packet.exception ? {1'b0, packet.cause} : (!d_legal ? 5'd1 : 5'd0);
        uop.bad_addr = packet.exception ? packet.bad_addr : (!d_legal ? d_inst : 32'd0);
        uop.writes_rd = d_destination && (uop.rd != 5'd0) && !uop.exception;
        uop.barrier = (uop.fu == xj_pkg::FU_BRU) || (uop.fu == xj_pkg::FU_SYSTEM)
            || (uop.fu == xj_pkg::FU_DMA_CONFIG)
            || ((uop.op == xj_pkg::OP_MATRIX) && (uop.mat_op == xj_pkg::MAT_WAIT))
            || uop.exception;
        if (uop.exception) begin
            uop.uses_rs1 = 1'b0; uop.uses_rs2 = 1'b0; uop.writes_v = 1'b0;
            uop.uses_v1 = 1'b0; uop.uses_v2 = 1'b0;
            uop.uses_v_old = 1'b0; uop.uses_v_mask = 1'b0;
        end
    end
endmodule
`default_nettype wire
