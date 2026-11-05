// Single-port synchronous byte-masked SRAM inference boundary.
// No reset/initialization. Replace with a characterized SRAM wrapper for ASIC.
`default_nettype none
module instruction_tcm #(
    parameter int G_DEPTH = 65536,
    parameter int G_ADDR_WIDTH = $clog2(G_DEPTH)
) (
    input logic clk,
    input logic enable,
    input logic write,
    input logic [G_ADDR_WIDTH-1:0] word,
    input logic [31:0] data,
    input logic [3:0] mask,
    output logic [31:0] read_data
);
    logic [31:0] f_memory [G_DEPTH];
    always_ff @(posedge clk) begin
        if (enable) begin
            if (write && (|mask)) begin
                for (int v_byte = 0; v_byte < 4; v_byte++) begin
                    if (mask[v_byte]) f_memory[word][8*v_byte +: 8] <= data[8*v_byte +: 8];
                end
            end else read_data <= f_memory[word];
        end
    end
endmodule
`default_nettype wire
