# UnifiedEndToEndSystem DC synthesis

These scripts synthesize the generated `UnifiedEndToEndSystem.v` top, not the
old standalone multiplier design.

`pre_dc.tcl` explicitly sources `synopsys_dc.setup` before `analyze` and
`elaborate`. Override it when using another technology setup:

```bash
DC_SETUP_FILE=/path/to/.synopsys_dc.setup \
  scripts/scripts_trapezoid/run_dc.sh
```

## Generate one shared four-mode RTL top

Small:

```bash
sbt -batch 'Compile/runMain trapezoid.GenerateUnifiedEndToEndSystem --scale=small'
```

Scaled paper or full paper:

```bash
sbt -batch 'Compile/runMain trapezoid.GenerateUnifiedEndToEndSystem --scale=scaled-paper'
sbt -batch 'Compile/runMain trapezoid.GenerateUnifiedEndToEndSystem --scale=paper'
```

These commands generate one `UnifiedEndToEndSystem` containing DenseIp,
TrIp, TrGT, and TrGS. `ModeSharedTileController` arbitrates the modes onto one
physical PE-row array and shared local storage. The output directories are
`generated-unified-small`, `generated-unified-scaled-paper`, and
`generated-unified-paper`.

## Generate RTL through the RTL simulation flow

Small end-to-end RTL:

```bash
sbt -batch 'Test/runMain trapezoid.EndToEndCycleSimulation --scale=small --mode=TrIp --category=HSxHS --samples=1'
```

Paper end-to-end RTL for one specialized mode:

```bash
sbt -batch 'Test/runMain trapezoid.EndToEndCycleSimulation --scale=paper --mode=TrIp --category=HSxHS --samples=1'
```

The paper command is expensive. It creates the generated top under a
`simWorkspace/unified-profile-*` directory.

## Run synthesis

Small:

```bash
SYNTH_PROFILE=small scripts/scripts_trapezoid/run_dc.sh
```

Paper or scaled-paper:

```bash
SYNTH_PROFILE=paper CLOCK_PERIOD_NS=2.0 \
  scripts/scripts_trapezoid/run_dc.sh
```

Reports are written to:

```text
dc_output_unified/<profile>_dc_output/
```

The main reports are `<top>_timing.rpt`, `<top>_area.rpt`,
`<top>_power.rpt`, `<top>_qor.rpt`, and `<top>_constraints.rpt`.
