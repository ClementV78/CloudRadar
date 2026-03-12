#!/usr/bin/env python3
"""Render static-analysis report markdown from quality artifacts."""

import argparse
import json
import os
from typing import Optional

TOOLS = ("pmd", "checkstyle", "archunit")
TOOL_LABELS = {"pmd": "PMD", "checkstyle": "Checkstyle", "archunit": "ArchUnit"}


def _load_json(path: str) -> Optional[dict]:
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8") as file_handle:
        return json.load(file_handle)


def _services(root: str) -> list[str]:
    if not os.path.isdir(root):
        return []
    return sorted(
        entry for entry in os.listdir(root)
        if os.path.isdir(os.path.join(root, entry))
    )


def _count_for(root: str, service: str, tool: str) -> Optional[int]:
    payload = _load_json(os.path.join(root, service, f"{tool}-count.json"))
    if payload is None:
        return None
    return int(payload.get("findings", 0))


def _result_path(root: str, service: str, tool: str) -> str:
    return os.path.join(root, service, f"{tool}-results.sarif")


def _first_findings(root: str, service: str, tool: str, limit: int) -> list[str]:
    payload = _load_json(_result_path(root, service, tool))
    if payload is None:
        return []

    runs = payload.get("runs", [])
    if not runs:
        return []

    findings = []
    for run in runs:
        for result in run.get("results", []):
            message = result.get("message", {}).get("text", "").replace("\n", " ").strip()
            if not message:
                message = "No message"
            location = "unknown"
            locations = result.get("locations", [])
            if locations:
                physical = locations[0].get("physicalLocation", {})
                artifact = physical.get("artifactLocation", {}).get("uri", "unknown")
                line = physical.get("region", {}).get("startLine")
                location = f"{artifact}:{line}" if line else artifact
            findings.append(f"- `{service}` / `{TOOL_LABELS[tool]}` / `{location}`: {message}")
            if len(findings) >= limit:
                return findings
    return findings


def render_markdown(root: str, title: str, finding_limit: int) -> str:
    services = _services(root)
    lines = [title, "", "| Service | PMD | Checkstyle | ArchUnit |", "| --- | ---: | ---: | ---: |"]

    if not services:
        lines.append("| _none_ | n/a | n/a | n/a |")
        lines.append("")
        lines.append("_No static-analysis artifacts were available for this run._")
        return "\n".join(lines)

    totals = {tool: 0 for tool in TOOLS}
    missing = {tool: 0 for tool in TOOLS}
    for service in services:
        row = []
        for tool in TOOLS:
            count = _count_for(root, service, tool)
            if count is None:
                missing[tool] += 1
                row.append("n/a")
                continue
            totals[tool] += count
            row.append(str(count))
        lines.append(f"| {service} | {row[0]} | {row[1]} | {row[2]} |")

    total_findings = sum(totals.values())
    lines.append("")
    lines.append(
        f"- Totals: PMD `{totals['pmd']}` · Checkstyle `{totals['checkstyle']}` · "
        f"ArchUnit `{totals['archunit']}` · All tools `{total_findings}`"
    )

    if any(value > 0 for value in missing.values()):
        lines.append(
            f"- Missing report files: PMD `{missing['pmd']}`, Checkstyle `{missing['checkstyle']}`, "
            f"ArchUnit `{missing['archunit']}` service(s)."
        )

    if total_findings == 0:
        lines.append("- No static-analysis findings detected in this run.")
        return "\n".join(lines)

    details = []
    for service in services:
        for tool in TOOLS:
            details.extend(_first_findings(root, service, tool, finding_limit - len(details)))
            if len(details) >= finding_limit:
                break
        if len(details) >= finding_limit:
            break

    lines.append("")
    lines.append("<details>")
    lines.append("<summary>Top findings</summary>")
    lines.append("")
    if details:
        lines.extend(details)
    else:
        lines.append("- Findings were reported but details could not be parsed from SARIF.")
    lines.append("</details>")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Render static-analysis report as markdown")
    parser.add_argument("--root", default="quality-reports", help="Directory containing per-service report files")
    parser.add_argument("--title", default="### Static Analysis Snapshot", help="Markdown title")
    parser.add_argument("--output", help="Optional markdown output file path")
    parser.add_argument(
        "--finding-limit",
        type=int,
        default=20,
        help="Maximum number of findings to include in details section",
    )
    args = parser.parse_args()

    markdown = render_markdown(args.root, args.title, args.finding_limit)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as file_handle:
            file_handle.write(markdown)
            file_handle.write("\n")
        return

    print(markdown)


if __name__ == "__main__":
    main()
