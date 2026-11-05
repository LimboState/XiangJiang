// Default elaboration of XJ-NPU-1.0; source: Parameters.scala / Bundle.scala.
// Changing these constants requires regenerating the Chisel comparison baseline.
`default_nettype none
package xj_pkg;
    localparam int C_TAG_SLOT_WIDTH = 3;
    localparam int C_GENERATION_WIDTH = 4;
    localparam int C_SEQUENCE_WIDTH = 16;
    localparam int C_VECTOR_WIDTH = 256;

    typedef enum logic [5:0] {
        OP_ADD = 6'd0, OP_SUB = 6'd1, OP_AND = 6'd2, OP_OR = 6'd3,
        OP_XOR = 6'd4, OP_SLL = 6'd5, OP_SRL = 6'd6, OP_SRA = 6'd7,
        OP_SLT = 6'd8, OP_SLTU = 6'd9, OP_ADDI = 6'd10, OP_ANDI = 6'd11,
        OP_ORI = 6'd12, OP_XORI = 6'd13, OP_SLLI = 6'd14, OP_SRLI = 6'd15,
        OP_SRAI = 6'd16, OP_SLTI = 6'd17, OP_SLTIU = 6'd18, OP_LUI = 6'd19,
        OP_AUIPC = 6'd20, OP_LW = 6'd21, OP_SW = 6'd22, OP_BEQ = 6'd23,
        OP_BNE = 6'd24, OP_BLT = 6'd25, OP_BGE = 6'd26, OP_BLTU = 6'd27,
        OP_BGEU = 6'd28, OP_JAL = 6'd29, OP_JALR = 6'd30, OP_FENCE = 6'd31,
        OP_HALT = 6'd32, OP_VECTOR = 6'd33, OP_MATRIX = 6'd34,
        OP_DSETSIZE = 6'd35, OP_DSETSTRIDE = 6'd36, OP_MVIN = 6'd37, OP_MVOUT = 6'd38
    } opcode_t;
    typedef enum logic [2:0] {
        FU_ALU = 3'd0, FU_MEM = 3'd1, FU_BRU = 3'd2, FU_SYSTEM = 3'd3,
        FU_VECTOR = 3'd4, FU_MATRIX = 3'd5, FU_DMA_CONFIG = 3'd6
    } fu_t;
    typedef enum logic [3:0] {
        MAT_SETMODE = 4'd0, MAT_LOAD = 4'd1, MAT_STORE = 4'd2, MAT_MUL = 4'd3,
        MAT_MACC = 4'd4, MAT_EW = 4'd5, MAT_TRANS = 4'd6, MAT_WAIT = 4'd7,
        MAT_BARRIER = 4'd8, MAT_FENCE = 4'd9
    } mat_op_t;
    typedef struct packed {
        logic [C_TAG_SLOT_WIDTH-1:0] slot;
        logic [C_GENERATION_WIDTH-1:0] generation;
    } rob_tag_t;
    typedef struct packed {
        logic [31:0] inst;
        logic [31:0] pc;
        opcode_t op;
        fu_t fu;
        logic [4:0] rs1;
        logic [4:0] rs2;
        logic [4:0] rd;
        logic uses_rs1;
        logic uses_rs2;
        logic writes_rd;
        logic [5:0] vec_op;
        logic [1:0] vec_unary;
        logic writes_v;
        logic uses_v1;
        logic uses_v2;
        logic uses_v_old;
        logic uses_v_mask;
        logic [4:0] mask_reg;
        logic unmasked;
        logic [31:0] imm;
        logic use_imm;
        mat_op_t mat_op;
        logic [1:0] mat_unit;
        logic [1:0] mat_buffer;
        logic mat_mode;
        logic [4:0] mat_id;
        logic barrier;
        logic exception;
        logic [4:0] cause;
        logic [31:0] bad_addr;
        rob_tag_t tag;
        logic [C_SEQUENCE_WIDTH-1:0] seq;
    } micro_op_t;
    typedef struct packed {
        logic ready;
        logic [31:0] value;
        rob_tag_t tag;
    } operand_t;
    typedef struct packed {
        logic ready;
        logic [C_VECTOR_WIDTH-1:0] value;
        rob_tag_t tag;
    } vector_operand_t;
    typedef struct packed {
        micro_op_t uop;
        operand_t src1;
        operand_t src2;
        vector_operand_t vsrc1;
        vector_operand_t vsrc2;
        vector_operand_t vold;
        vector_operand_t vmask;
    } issue_entry_t;
    typedef struct packed {
        rob_tag_t tag;
        logic [C_SEQUENCE_WIDTH-1:0] seq;
        logic [C_VECTOR_WIDTH-1:0] data;
        logic exception;
        logic [4:0] cause;
        logic [31:0] bad_addr;
        logic [31:0] detail;
        logic [31:0] next_pc;
    } exe_result_t;
    typedef struct packed {
        logic [31:0] pc;
        logic [31:0] inst;
        logic exception;
        logic [3:0] cause;
        logic [31:0] bad_addr;
    } fetch_packet_t;
    typedef struct packed {
        logic [31:0] row_bytes;
        logic [31:0] rows;
        logic [31:0] external_stride;
        logic [31:0] local_stride;
        logic [31:0] bytes_done;
        logic [31:0] rows_done;
        logic active;
        logic outbound;
        logic error;
        logic poisoned;
    } dma_status_t;
    typedef struct packed {
        logic busy;
        logic done;
        logic fault;
        logic [31:0] fault_pc;
        logic [31:0] fault_inst;
        logic [4:0] fault_cause;
        logic [31:0] fault_addr;
        logic [31:0] fault_detail;
        dma_status_t dma;
    } core_status_t;
endpackage
`default_nettype wire
