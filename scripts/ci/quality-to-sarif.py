#!/usr/bin/env python3
"""Convert PMD / Checkstyle XML reports to SARIF 2.1.0 for GitHub Code Scanning.

Usage:
  python3 quality-to-sarif.py pmd -o pmd.sarif src/*/target/pmd.xml
  python3 quality-to-sarif.py checkstyle -o cs.sarif src/*/target/checkstyle-result.xml
"""

import argparse
import glob
import json
import os
import xml.etree.ElementTree as ET

SARIF_SCHEMA = (
    "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/"
    "main/sarif-2.1/schema/sarif-schema-2.1.0.json"
)

PMD_LEVEL = {"1": "error", "2": "error", "3": "warning", "4": "note", "5": "note"}
CS_LEVEL = {"error": "error", "warning": "warning", "info": "note"}


def _rel(path, workspace):
    return os.path.relpath(path, workspace) if os.path.isabs(path) else path


def _loc(uri, start, end=None):
    region = {"startLine": start}
    if end and end != start:
        region["endLine"] = end
    return {"physicalLocation": {"artifactLocation": {"uri": uri}, "region": region}}


def parse_pmd(xml_files, workspace):
    results, rules = [], {}
    for path in xml_files:
        root = ET.parse(path).getroot()
        for f in root.findall("file"):
            uri = _rel(f.get("name", ""), workspace)
            for v in f.findall("violation"):
                rid = v.get("rule", "unknown")
                rules[rid] = {
                    "id": rid,
                    "shortDescription": {"text": rid},
                    "helpUri": v.get("externalInfoUrl", ""),
                }
                results.append({
                    "ruleId": rid,
                    "level": PMD_LEVEL.get(v.get("priority", "3"), "warning"),
                    "message": {"text": (v.text or "").strip()},
                    "locations": [
                        _loc(uri, int(v.get("beginline", 1)),
                             int(v.get("endline", v.get("beginline", 1))))
                    ],
                })
    return results, list(rules.values())


def parse_checkstyle(xml_files, workspace):
    results, rules = [], {}
    for path in xml_files:
        root = ET.parse(path).getroot()
        for f in root.findall("file"):
            uri = _rel(f.get("name", ""), workspace)
            for e in f.findall("error"):
                src = e.get("source", "unknown")
                rid = src.rsplit(".", 1)[-1] if "." in src else src
                rules[rid] = {"id": rid, "shortDescription": {"text": rid}}
                results.append({
                    "ruleId": rid,
                    "level": CS_LEVEL.get(e.get("severity", "warning"), "warning"),
                    "message": {"text": e.get("message", "")},
                    "locations": [_loc(uri, int(e.get("line", 1)))],
                })
    return results, list(rules.values())


def build_sarif(name, version, results, rules):
    return {
        "$schema": SARIF_SCHEMA,
        "version": "2.1.0",
        "runs": [{
            "tool": {"driver": {"name": name, "version": version, "rules": rules}},
            "results": results,
        }],
    }


def main():
    p = argparse.ArgumentParser(description="Convert quality XML to SARIF")
    p.add_argument("tool", choices=["pmd", "checkstyle"])
    p.add_argument("-o", "--output", required=True)
    p.add_argument(
        "-w", "--workspace",
        default=os.environ.get("GITHUB_WORKSPACE", os.getcwd()),
    )
    p.add_argument("files", nargs="+")
    args = p.parse_args()

    xml_files = [
        f for pattern in args.files
        for f in glob.glob(pattern)
        if os.path.isfile(f)
    ]

    if args.tool == "pmd":
        results, rules = parse_pmd(xml_files, args.workspace) if xml_files else ([], [])
        sarif = build_sarif("PMD", "7.7.0", results, rules)
    else:
        results, rules = parse_checkstyle(xml_files, args.workspace) if xml_files else ([], [])
        sarif = build_sarif("Checkstyle", "9.3", results, rules)

    with open(args.output, "w") as f:
        json.dump(sarif, f, indent=2)
    print(f"{args.output}: {len(results)} finding(s) from {len(xml_files)} report(s)")


if __name__ == "__main__":
    main()
