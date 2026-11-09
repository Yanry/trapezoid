# UnifiedEndToEndSystem Synopsys Design Compiler flow.
#
# Required input:
#   RTL_FILE=/absolute/path/to/UnifiedEndToEndSystem.v
#   RTL_SOURCE_DIR=/directory/containing/generated/dependency/RTL
#
# Optional environment variables:
#   TOP_NAME=UnifiedEndToEndSystem
#   SYNTH_PROFILE=small
#   CLOCK_PERIOD_NS=2.0
#   OUTPUT_ROOT=./dc_output_unified
#   WORK_ROOT=./WORK
#   DC_SETUP_FILE=./scripts/scripts_trapezoid/synopsys_dc.setup

set SCRIPT_DIR [file dirname [file normalize [info script]]]

proc env_or_default {name default_value} {
    if {[info exists ::env($name)] && $::env($name) ne ""} {
        return $::env($name)
    }
    return $default_value
}

# The file is intentionally named synopsys_dc.setup rather than the automatic
# .synopsys_dc.setup startup file, so load it explicitly before any library,
# analyze, or elaborate command is executed.
set DC_SETUP_FILE [file normalize [env_or_default DC_SETUP_FILE \
    [file join $SCRIPT_DIR synopsys_dc.setup]]]
if {![file exists $DC_SETUP_FILE]} {
    error "DC_SETUP_FILE does not exist: $DC_SETUP_FILE"
}
puts "INFO: sourcing DC setup: $DC_SETUP_FILE"
source -echo -verbose $DC_SETUP_FILE

set TOP_NAME [env_or_default TOP_NAME "UnifiedEndToEndSystem"]
set RTL_FILE [file normalize [env_or_default RTL_FILE \
    "./generated-unified-small/UnifiedEndToEndSystem.v"]]
set RTL_SOURCE_DIR [file normalize [env_or_default RTL_SOURCE_DIR \
    [file dirname $RTL_FILE]]]
set SYNTH_PROFILE [env_or_default SYNTH_PROFILE "small"]
set CLOCK_PERIOD_NS [env_or_default CLOCK_PERIOD_NS "2.0"]
set OUTPUT_ROOT [file normalize [env_or_default OUTPUT_ROOT \
    "./dc_output_unified"]]
set WORK_ROOT [file normalize [env_or_default WORK_ROOT "./WORK"]]
set DESIGN_LIB "${WORK_ROOT}/${SYNTH_PROFILE}_WORK"
set OUTPUT_DIR "${OUTPUT_ROOT}/${SYNTH_PROFILE}_dc_output"

if {![file exists $RTL_FILE]} {
    error "RTL_FILE does not exist: $RTL_FILE"
}
if {![file isdirectory $RTL_SOURCE_DIR]} {
    error "RTL_SOURCE_DIR does not exist: $RTL_SOURCE_DIR"
}

file mkdir $WORK_ROOT
file mkdir $DESIGN_LIB
file mkdir $OUTPUT_ROOT
file mkdir $OUTPUT_DIR

define_design_lib MY_LIB -path $DESIGN_LIB

set_svf "${OUTPUT_DIR}/${TOP_NAME}.svf"
saif_map -start

puts "INFO: top           = $TOP_NAME"
puts "INFO: RTL top file  = $RTL_FILE"
puts "INFO: RTL directory = $RTL_SOURCE_DIR"
puts "INFO: profile       = $SYNTH_PROFILE"
puts "INFO: clock period  = ${CLOCK_PERIOD_NS} ns"
puts "INFO: output        = $OUTPUT_DIR"

set verilog_files [glob -nocomplain -directory $RTL_SOURCE_DIR *.v]
set systemverilog_files [glob -nocomplain -directory $RTL_SOURCE_DIR *.sv]
if {[lsearch -exact $verilog_files $RTL_FILE] < 0 &&
    [string equal -nocase [file extension $RTL_FILE] ".v"]} {
    lappend verilog_files $RTL_FILE
}
if {[lsearch -exact $systemverilog_files $RTL_FILE] < 0 &&
    [string equal -nocase [file extension $RTL_FILE] ".sv"]} {
    lappend systemverilog_files $RTL_FILE
}
foreach file [lsort -unique $verilog_files] {
    analyze -format verilog $file -library MY_LIB
}
foreach file [lsort -unique $systemverilog_files] {
    analyze -format sverilog $file -library MY_LIB
}
elaborate $TOP_NAME -library MY_LIB
current_design $TOP_NAME
link

source -echo -verbose "[file join $SCRIPT_DIR DefaultConstrain.tcl]"
check_design > "${OUTPUT_DIR}/${TOP_NAME}_check_design.rpt"

compile_ultra

change_names -rules verilog -hierarchy
write -hierarchy -format ddc \
    -output "${OUTPUT_DIR}/${TOP_NAME}_post.ddc"
write -hierarchy -format verilog \
    -output "${OUTPUT_DIR}/${TOP_NAME}_mapped.v"
write_sdc "${OUTPUT_DIR}/${TOP_NAME}.sdc"

source -echo -verbose "[file join $SCRIPT_DIR report.tcl]"

puts "INFO: UnifiedEndToEndSystem synthesis completed successfully"
