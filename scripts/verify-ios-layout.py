"""Verify actual simulator image bounds across live status/editor changes."""
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text())
states = data["states"]
assert len(states) == 6, f"Missing native UI states: {len(states)}"
reference = states[0]["imageRect"]
assert reference[2] > 80 and reference[3] > 100, reference
for row in states:
    delta = max(abs(a - b) for a, b in zip(reference, row["imageRect"]))
    assert delta < 0.5, f"Camera jumped in state {row['state']}: {row['imageRect']}"
print("Six native simulator UI states: image position and size unchanged (less than 0.5 pt).")
