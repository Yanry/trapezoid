module TrapezoidSyncSram #(
  parameter integer WIDTH = 32,
  parameter integer DEPTH = 256,
  parameter integer ADDR_WIDTH = 8
) (
  input  wire                  clk,
  input  wire                  write_enable,
  input  wire [ADDR_WIDTH-1:0] write_address,
  input  wire [WIDTH-1:0]      write_data,
  input  wire                  read_enable,
  input  wire [ADDR_WIDTH-1:0] read_address,
  output reg  [WIDTH-1:0]      read_data
);
  reg [WIDTH-1:0] memory [0:DEPTH-1];

  always @(posedge clk) begin
    if (write_enable)
      memory[write_address] <= write_data;
    if (read_enable)
      read_data <= memory[read_address];
  end
endmodule

module TrapezoidMaskedSyncSram #(
  parameter integer WIDTH = 32,
  parameter integer DEPTH = 256,
  parameter integer ADDR_WIDTH = 8,
  parameter integer MASK_WIDTH = WIDTH / 8
) (
  input  wire                  clk,
  input  wire                  write_enable,
  input  wire [ADDR_WIDTH-1:0] write_address,
  input  wire [WIDTH-1:0]      write_data,
  input  wire [MASK_WIDTH-1:0] write_mask,
  input  wire                  read_enable,
  input  wire [ADDR_WIDTH-1:0] read_address,
  output reg  [WIDTH-1:0]      read_data
);
  reg [WIDTH-1:0] memory [0:DEPTH-1];
  integer byte_index;

  always @(posedge clk) begin
    if (write_enable) begin
      for (byte_index = 0; byte_index < MASK_WIDTH; byte_index = byte_index + 1)
        if (write_mask[byte_index])
          memory[write_address][byte_index * 8 +: 8] <=
            write_data[byte_index * 8 +: 8];
    end
    if (read_enable)
      read_data <= memory[read_address];
  end
endmodule
