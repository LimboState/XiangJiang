module memory_tb;
    logic clk = 0;
    logic enable = 0;
    logic write = 0;
    logic [3:0] word_addr;
    logic [31:0] data;
    logic [3:0] mask;
    logic [31:0] read_data;
    logic [3:0] read_valid = 0;
    logic [3:0] write_valid = 0;
    logic [3:0][3:0] read_row;
    logic [3:0][3:0] write_row;
    logic [3:0][511:0] bank_data;
    logic [3:0][63:0] bank_mask;
    logic [3:0][511:0] bank_read;
    instruction_tcm #(.G_DEPTH(16)) itcm (
        .clk(clk), .enable(enable), .write(write), .word(word_addr),
        .data(data), .mask(mask), .read_data(read_data)
    );
    data_tcm #(.G_DEPTH(16)) dtcm (
        .clk(clk), .read_valid(read_valid), .write_valid(write_valid),
        .read_row(read_row), .write_row(write_row), .write_data(bank_data),
        .write_mask(bank_mask), .read_data(bank_read)
    );
    task automatic tick;
        clk=0; #1; clk=1; #1; clk=0;
    endtask
    initial begin
        enable=1;write=1;word_addr=3;data=32'h12345678;mask=4'hf;tick();
        data=32'hffffffff;mask=4'h5;tick();
        write=0;tick();
        assert(read_data==32'h12ff56ff) else $fatal(1,"ITCM byte mask");
        write=1;mask=0;tick();
        assert(read_data==32'h12ff56ff) else $fatal(1,"ITCM zero mask read");
        enable=0;
        write_valid=4'hf;write_row='0;bank_mask='1;
        for(int b=0;b<4;b++) bank_data[b]={16{32'(b+1)}};
        tick();write_valid=0;read_valid=4'hf;read_row='0;tick();
        for(int b=0;b<4;b++)
            assert(bank_read[b]=={16{32'(b+1)}}) else $fatal(1,"DTCM bank");
        read_valid=0;write_valid=4'hf;bank_mask='0;bank_data='1;
        for(int b=0;b<4;b++) bank_mask[b][b]=1'b1;
        tick();write_valid=0;read_valid=4'hf;tick();
        for(int b=0;b<4;b++) begin
            logic [511:0] expected;
            expected={16{32'(b+1)}};expected[b*8+:8]=8'hff;
            assert(bank_read[b]==expected) else $fatal(1,"DTCM byte mask");
        end
        $display("PASS memory byte masks and four banks");$finish;
    end
endmodule
