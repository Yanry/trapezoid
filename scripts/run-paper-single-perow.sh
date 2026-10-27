#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compat_root="${TMPDIR:-/tmp}/trapezoid-verilator-root"

mkdir -p "${compat_root}"
ln -sfn /usr/bin/verilator_bin "${compat_root}/verilator_bin"
ln -sfn /usr/share/verilator/include "${compat_root}/include"

export VERILATOR_ROOT="${compat_root}"
cd "${repo_root}"

if [[ $# -eq 0 ]]; then
  set -- --mode=all --samples=1
fi

run_command="Test/runMain trapezoid.PaperSinglePERowCycleSimulation"
for argument in "$@"; do
  escaped="${argument//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  run_command+=" \"${escaped}\""
done

exec sbt -batch "${run_command}"
