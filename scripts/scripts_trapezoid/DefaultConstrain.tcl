# Timing and IO constraints for UnifiedEndToEndSystem.

reset_design

set INT_CLK_PERIOD [env_or_default CLOCK_PERIOD_NS "2.0"]
set CLK_PORT [get_ports clk]
if {[sizeof_collection $CLK_PORT] != 1} {
    error "Expected exactly one top-level clk port"
}

create_clock -period $INT_CLK_PERIOD -name clk $CLK_PORT
set_clock_uncertainty -setup [expr {0.15 * $INT_CLK_PERIOD}] \
    [get_clocks clk]
set_clock_uncertainty -hold [expr {0.05 * $INT_CLK_PERIOD}] \
    [get_clocks clk]

# Reset is asynchronous from the datapath timing perspective. The generated
# SpinalHDL top exposes reset directly, so do not use the old resetn name.
set RESET_PORT [get_ports reset]
if {[sizeof_collection $RESET_PORT] == 1} {
    set_false_path -from $RESET_PORT
}

# Model the external descriptor, DMA and MRN-programming interfaces as
# synchronous logic with a conservative fraction of the clock period at the
# boundary. Exclude clk/reset from the data collections.
set DATA_INPUTS [all_inputs]
set DATA_INPUTS [remove_from_collection $DATA_INPUTS $CLK_PORT]
if {[sizeof_collection $RESET_PORT] == 1} {
    set DATA_INPUTS [remove_from_collection $DATA_INPUTS $RESET_PORT]
}
if {[sizeof_collection $DATA_INPUTS] > 0} {
    set_input_delay -max [expr {0.30 * $INT_CLK_PERIOD}] \
        -clock clk $DATA_INPUTS
    set_input_delay -min [expr {0.05 * $INT_CLK_PERIOD}] \
        -clock clk $DATA_INPUTS
    set_drive 0 $DATA_INPUTS
}

set DATA_OUTPUTS [all_outputs]
if {[sizeof_collection $DATA_OUTPUTS] > 0} {
    set_output_delay -max [expr {0.30 * $INT_CLK_PERIOD}] \
        -clock clk $DATA_OUTPUTS
    set_output_delay -min 0 -clock clk $DATA_OUTPUTS
    set_load 0.03 $DATA_OUTPUTS
}

set_max_area 0
set_max_fanout 30 [current_design]
