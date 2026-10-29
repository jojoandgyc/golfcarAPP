#!/usr/bin/env python3
"""Independent address/width audit against PDF sections 3.1–3.9 (reserved rows omitted)."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
catalog = json.loads((ROOT / "docs/motor-register-catalog.json").read_text(encoding="utf-8"))

# Logical addresses transcribed from the PDF, independent of production catalog generation.
config = {0x58: 1, 0x59: 2, 0x5B: 2, 0x5D: 2, 0x5F: 1, 0x60: 1}
for start, end in ((0x64, 0x66), (0x68, 0x79), (0x84, 0x87), (0x89, 0x8C),
                   (0xB0, 0xB3), (0xC8, 0xCB), (0xCD, 0xCF), (0xD2, 0xD5)):
    config.update({a: 1 for a in range(start, end + 1)})
config.update({a: 2 for a in range(0xE0, 0x106, 2)})
readonly = {0: 1, 1: 1, 2: 1}
for base in (8, 0x12):
    readonly.update({base: 1, base+1: 2, base+3: 1, base+4: 1, base+5: 1, base+7: 2, base+9: 1})
readonly.update({a: 1 for a in range(0x1C, 0x21)})
commands = {0x40: 1, 0x41: 2, 0x43: 1, 0x44: 1, 0x45: 2, 0x47: 1, 0x48: 1, 0x49: 1}


def compare(key, expected):
    rows = catalog[key]
    assert len(rows) == len(expected), f"{key}: expected {len(expected)}, got {len(rows)}"
    actual = {row["address"]: row["words"] for row in rows}
    assert actual == expected, f"{key}: missing/extra/wrong widths: {set(actual.items()) ^ set(expected.items())}"
    assert len({row["id"] for row in rows}) == len(rows), f"{key}: duplicate ID"
    occupied = [a for row in rows for a in range(row["address"], row["address"] + row["words"])]
    assert len(set(occupied)) == len(occupied), f"{key}: overlapping registers"


compare("fields", config)
compare("readOnlyFields", readonly)
compare("actions", commands)
for row in catalog["fields"]:
    bits = row["words"] * 16
    lower = -(1 << (bits-1)) if row["signed"] else 0
    upper = (1 << (bits-1)) - 1 if row["signed"] else (1 << bits) - 1
    assert lower <= row["minRaw"] <= row["maxRaw"] <= upper, row["id"]
    if row["address"] >= 0xE0:
        assert row["divisor"] == (1 if row["address"] == 0x104 else 32768), row["id"]
        assert not row["supportsTemporary"], row["id"]
    else:
        assert row["supportsTemporary"], row["id"]
    if row["address"] in (0x6E, 0x6F, 0x77, 0x78):
        assert row["signed"] and row["divisor"] == 100
    if row["address"] in (0x69, 0x6A, 0x6B, 0x72, 0x73, 0x74):
        assert row["packedPhase"]

print(json.dumps({"configurationFields": len(config), "readOnlyFields": len(readonly),
                  "commandInterfaces": len(commands), "reservedRegistersExposed": False,
                  "logicalFieldsTotal": len(config) + len(readonly) + len(commands)}, indent=2))
