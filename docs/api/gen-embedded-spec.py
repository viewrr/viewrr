#!/usr/bin/env python3
"""Generate the server's embedded OpenAPI spec from the single canonical one.

Single source of truth: docs/api/openapi.yaml (OpenAPI 3.1, hand-edited).
The Ktor server serves an embedded copy from the classpath
(server/src/main/resources/openapi/documentation.yaml) at /swagger and
/openapi.yaml. Swagger-UI is happiest on 3.0.3, so this downconverts the one
3.1-only feature we use — nullable type-unions `type: [T, "null"]` — to the
3.0.3 `type: T` + `nullable: true` form. Run it whenever the canonical changes
(also invoked by docs/deploy.sh).

    python3 docs/api/gen-embedded-spec.py
"""
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "docs/api/openapi.yaml"
DST = ROOT / "server/src/main/resources/openapi/documentation.yaml"


def downconvert(node):
    """Recursively rewrite 3.1 nullable unions to 3.0.3 nullable:true."""
    if isinstance(node, dict):
        t = node.get("type")
        if isinstance(t, list) and "null" in t:
            rest = [x for x in t if x != "null"]
            # only the single-type + null case appears in this spec
            node["type"] = rest[0] if len(rest) == 1 else rest
            node["nullable"] = True
        for v in node.values():
            downconvert(v)
    elif isinstance(node, list):
        for v in node:
            downconvert(v)
    return node


def main():
    spec = yaml.safe_load(SRC.read_text())
    spec["openapi"] = "3.0.3"
    downconvert(spec)
    header = ("# AUTO-GENERATED from docs/api/openapi.yaml by docs/api/gen-embedded-spec.py.\n"
              "# Do NOT hand-edit — edit the canonical spec and re-run the generator.\n")
    DST.write_text(header + yaml.safe_dump(spec, sort_keys=False, allow_unicode=True, width=100))
    print(f"wrote {DST.relative_to(ROOT)} from {SRC.relative_to(ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
