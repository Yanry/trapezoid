#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

top_name="${TOP_NAME:-UnifiedEndToEndSystem}"
profile="${SYNTH_PROFILE:-small}"
clock_period="${CLOCK_PERIOD_NS:-2.0}"
output_root="${OUTPUT_ROOT:-${repo_root}/dc_output_unified}"
work_root="${WORK_ROOT:-${repo_root}/WORK}"
dc_setup_file="${DC_SETUP_FILE:-${repo_root}/scripts/scripts_trapezoid/synopsys_dc.setup}"

case "${profile}" in
  small)
    default_rtl="${repo_root}/generated-unified-small/UnifiedEndToEndSystem.v"
    ;;
  scaled-paper)
    default_rtl="${repo_root}/generated-unified-scaled-paper/UnifiedEndToEndSystem.v"
    ;;
  paper)
    default_rtl="${repo_root}/generated-unified-paper/UnifiedEndToEndSystem.v"
    ;;
  *)
    echo "Unsupported SYNTH_PROFILE: ${profile}" >&2
    echo "Use small, scaled-paper, or paper." >&2
    exit 2
    ;;
esac

rtl_file="${RTL_FILE:-${default_rtl}}"
if [[ ! -f "${rtl_file}" ]]; then
  echo "RTL file not found: ${rtl_file}" >&2
  echo "Generate it first with GenerateUnifiedEndToEndSystem." >&2
  exit 1
fi

if [[ ! -f "${dc_setup_file}" ]]; then
  echo "Synopsys DC setup file not found: ${dc_setup_file}" >&2
  exit 1
fi

if ! command -v dc_shell >/dev/null 2>&1; then
  echo "dc_shell was not found in PATH" >&2
  exit 127
fi

export TOP_NAME="${top_name}"
export SYNTH_PROFILE="${profile}"
export CLOCK_PERIOD_NS="${clock_period}"
export RTL_FILE="${rtl_file}"
export RTL_SOURCE_DIR="${RTL_SOURCE_DIR:-$(dirname "${rtl_file}")}"
export OUTPUT_ROOT="${output_root}"
export WORK_ROOT="${work_root}"
export DC_SETUP_FILE="${dc_setup_file}"

exec dc_shell -64bit -f scripts/scripts_trapezoid/pre_dc.tcl
