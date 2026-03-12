#!/usr/bin/env python3
"""Print a markdown summary table for static-analysis count artifacts."""

import argparse
import json
import os


def main():
    parser = argparse.ArgumentParser(description="Render static-analysis count summary as markdown")
    parser.add_argument("--root", default="quality-reports", help="Directory containing per-service count files")
    args = parser.parse_args()

    tools = ("pmd", "checkstyle", "archunit")
    services = []
    if os.path.isdir(args.root):
        services = sorted(
            entry for entry in os.listdir(args.root)
            if os.path.isdir(os.path.join(args.root, entry))
        )

    print("| Service | PMD | Checkstyle | ArchUnit |")
    print("| --- | ---: | ---: | ---: |")

    if not services:
        print("| _none_ | n/a | n/a | n/a |")
        return

    for service in services:
        row = []
        for tool in tools:
            count_path = os.path.join(args.root, service, f"{tool}-count.json")
            if not os.path.isfile(count_path):
                row.append("n/a")
                continue
            with open(count_path, "r", encoding="utf-8") as file_handle:
                payload = json.load(file_handle)
            row.append(str(payload.get("findings", 0)))
        print(f"| {service} | {row[0]} | {row[1]} | {row[2]} |")


if __name__ == "__main__":
    main()
