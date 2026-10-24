#!/usr/bin/env python3
"""Run Windows Vivado from WSL against a separate snapshot of this repository."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


def windows_path(path):
    return subprocess.check_output(["wslpath", "-w", str(path)], text=True).strip()


def powershell_quote(value):
    return "'" + value.replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vivado", type=Path,
                        default=Path("/mnt/d/Xilinx/Vivado/2021.2/bin/vivado.bat"))
    parser.add_argument("--stage-dir", type=Path, default=Path("/mnt/d/xj-synthesis"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    filelist = root / "generated/filelist.f"
    if not filelist.is_file() or not args.vivado.is_file():
        parser.error("Generate RTL first and check the --vivado path")
    args.stage_dir.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix="limbostate2-", dir=args.stage_dir))
    relative_sources = ["generated/" + line.strip() for line in filelist.read_text().splitlines()
                        if line.strip()]
    relative_sources += ["generated/filelist.f", "generated/xiangjiang-config.json",
                         "scripts/vivado-synth.tcl", "scripts/vivado-synth.xdc"]
    hashes = {}
    for relative in relative_sources:
        source, target = root / relative, stage / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        hashes[relative] = hashlib.sha256(target.read_bytes()).hexdigest()
    output = stage / "out/vivado-synth"
    output.mkdir(parents=True)
    local_output = root / "out/vivado-synth" / stage.name
    local_output.mkdir(parents=True)
    (output / "inputs.json").write_text(json.dumps({"repository": str(root),
        "snapshot": str(stage), "sha256": hashes}, indent=2) + "\n")
    command = "Set-Location " + powershell_quote(windows_path(output))
    command += "; & " + powershell_quote(windows_path(args.vivado.resolve()))
    command += " -mode batch -source " + powershell_quote(windows_path(stage / "scripts/vivado-synth.tcl"))
    command += "; exit $LASTEXITCODE"
    print(f"Snapshot: {stage}\nReports: {local_output}", flush=True)
    powershell = "/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"
    try:
        with (local_output / "launcher.log").open("wb") as log:
            result = subprocess.run([powershell, "-NoProfile", "-NonInteractive", "-Command", command],
                                    stdout=log, stderr=subprocess.STDOUT)
    finally:
        for artifact in output.iterdir():
            if artifact.is_file():
                shutil.copy2(artifact, local_output / artifact.name)
    complete = (local_output / "XiangJiangNPU_synth.dcp").is_file()
    print(f"Vivado exit: {result.returncode}; checkpoint present: {complete}", flush=True)
    return result.returncode or (0 if complete else 1)


if __name__ == "__main__":
    raise SystemExit(main())
