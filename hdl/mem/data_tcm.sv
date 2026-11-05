// Four 512-bit 1R1W banks. The arbiter must exclude same-row read/write.
// Memory and read output are unreset. An ASIC SRAM macro mapping is required.
`default_nettype none
module data_tcm #(
    parameter int G_DEPTH = 4096,
    parameter int G_ADDR_WIDTH = $clog2(G_DEPTH)
) (
    input logic clk,
    input logic [3:0] read_valid,
    input logic [3:0] write_valid,
    input logic [3:0][G_ADDR_WIDTH-1:0] read_row,
    input logic [3:0][G_ADDR_WIDTH-1:0] write_row,
    input logic [3:0][511:0] write_data,
    input logic [3:0][63:0] write_mask,
    output logic [3:0][511:0] read_data
);
    for (genvar g = 0; g < 4; g++) begin : gen_bank
        logic [511:0] f_memory [G_DEPTH];
        always_ff @(posedge clk) begin
            if (write_valid[g]) begin
                for (int v_byte = 0; v_byte < 64; v_byte++) begin
                    if (write_mask[g][v_byte])
                        f_memory[write_row[g]][8*v_byte +: 8] <= write_data[g][8*v_byte +: 8];
                end
            end
        end
        always_ff @(posedge clk) begin
            if (read_valid[g]) read_data[g] <= f_memory[read_row[g]];
        end
        `ifndef SYNTHESIS
        assert property (@(posedge clk)
            !(read_valid[g] && write_valid[g] && (read_row[g] == write_row[g])))
            else $error("Same-row SRAM collision must be resolved by the arbiter");
        `endif
    end
endmodule
`default_nettype wire
