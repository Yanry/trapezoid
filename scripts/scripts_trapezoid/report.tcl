set REPORT_PREFIX "${OUTPUT_DIR}/${TOP_NAME}"

report_qor > "${REPORT_PREFIX}_qor.rpt"
report_timing -max_paths 20 -path full \
    > "${REPORT_PREFIX}_timing.rpt"
report_area -hierarchy > "${REPORT_PREFIX}_area.rpt"
report_power -hierarchy > "${REPORT_PREFIX}_power.rpt"
report_constraint -all_violators \
    > "${REPORT_PREFIX}_constraints.rpt"

puts "INFO: reports written under ${OUTPUT_DIR}"
