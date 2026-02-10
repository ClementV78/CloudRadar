#!/usr/bin/env python3
"""
Build a local SQLite reference database from a large aircraft CSV dataset.

This is intentionally a local/offline utility:
- Do NOT commit the raw source CSV to the repo.
- The output SQLite file is meant to be uploaded as a versioned artifact (e.g., to S3).

Expected input format:
- Comma-separated values
- Single quote (') is used as the quote character in the dataset
"""

from __future__ import annotations

import argparse
import csv
import sqlite3
import sys
import time
from pathlib import Path
from typing import Iterable


SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS aircraft (
  icao24 TEXT PRIMARY KEY,
  country TEXT,
  category_description TEXT,
  icao_aircraft_class TEXT,
  manufacturer_icao TEXT,
  manufacturer_name TEXT,
  model TEXT,
  registration TEXT,
  typecode TEXT
);
"""

INDEXES_SQL = """
CREATE INDEX IF NOT EXISTS idx_aircraft_typecode ON aircraft(typecode);
"""


def parse_args() -> argparse.Namespace:
  p = argparse.ArgumentParser(
    description="Transform aircraft CSV into a lightweight SQLite reference DB."
  )
  p.add_argument("--input", required=True, help="Path to input CSV file")
  p.add_argument("--output", required=True, help="Path to output SQLite file")
  p.add_argument(
    "--limit",
    type=int,
    default=0,
    help="Optional max rows to import (0 = no limit)",
  )
  p.add_argument(
    "--batch-size",
    type=int,
    default=5000,
    help="Commit every N rows (trade-off speed vs memory).",
  )
  p.add_argument(
    "--drop-existing",
    action="store_true",
    help="Drop and recreate the table before import.",
  )
  return p.parse_args()


def _norm(s: str | None) -> str | None:
  if s is None:
    return None
  s = s.strip()
  return s if s else None


def _require_columns(fieldnames: list[str] | None, required: Iterable[str]) -> None:
  if not fieldnames:
    raise RuntimeError("CSV has no header row / fieldnames")
  missing = [c for c in required if c not in fieldnames]
  if missing:
    raise RuntimeError(f"CSV is missing required columns: {missing}. Found: {fieldnames}")


def open_csv(path: Path) -> csv.DictReader:
  # The dataset uses single quotes as quote char and does not escape with backslashes.
  # Using errors='replace' avoids crashing on non-utf8 characters (e.g. odd dashes).
  f = path.open("r", newline="", encoding="utf-8", errors="replace")
  return csv.DictReader(f, delimiter=",", quotechar="'")


def configure_sqlite(conn: sqlite3.Connection) -> None:
  # Speed up bulk load; this is a local artifact build.
  conn.execute("PRAGMA journal_mode=WAL;")
  conn.execute("PRAGMA synchronous=NORMAL;")
  conn.execute("PRAGMA temp_store=MEMORY;")

def finalize_sqlite(conn: sqlite3.Connection) -> None:
  # Produce a single-file artifact (avoid shipping a -wal sidecar).
  conn.execute("PRAGMA wal_checkpoint(TRUNCATE);")
  conn.execute("PRAGMA journal_mode=DELETE;")
  conn.execute("PRAGMA optimize;")


def main() -> int:
  args = parse_args()

  src = Path(args.input)
  dst = Path(args.output)

  if not src.exists():
    print(f"ERROR: input not found: {src}", file=sys.stderr)
    return 2

  dst.parent.mkdir(parents=True, exist_ok=True)

  if args.drop_existing and dst.exists():
    dst.unlink()

  t0 = time.time()
  conn = sqlite3.connect(str(dst))
  try:
    configure_sqlite(conn)
    conn.executescript(SCHEMA_SQL)
    if args.drop_existing:
      conn.execute("DROP TABLE IF EXISTS aircraft;")
      conn.executescript(SCHEMA_SQL)

    reader = open_csv(src)
    required_cols = [
      "icao24",
      "country",
      "categoryDescription",
      "icaoAircraftClass",
      "manufacturerIcao",
      "manufacturerName",
      "model",
      "registration",
      "typecode",
    ]
    _require_columns(reader.fieldnames, required_cols)

    insert_sql = """
      INSERT INTO aircraft (
        icao24,
        country,
        category_description,
        icao_aircraft_class,
        manufacturer_icao,
        manufacturer_name,
        model,
        registration,
        typecode
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(icao24) DO UPDATE SET
        country=excluded.country,
        category_description=excluded.category_description,
        icao_aircraft_class=excluded.icao_aircraft_class,
        manufacturer_icao=excluded.manufacturer_icao,
        manufacturer_name=excluded.manufacturer_name,
        model=excluded.model,
        registration=excluded.registration,
        typecode=excluded.typecode;
    """

    cur = conn.cursor()
    conn.execute("BEGIN;")

    n = 0
    n_skipped = 0
    for row in reader:
      icao24 = _norm(row.get("icao24"))
      if not icao24:
        n_skipped += 1
        continue

      values = (
        icao24.lower(),
        _norm(row.get("country")),
        _norm(row.get("categoryDescription")),
        _norm(row.get("icaoAircraftClass")),
        _norm(row.get("manufacturerIcao")),
        _norm(row.get("manufacturerName")),
        _norm(row.get("model")),
        _norm(row.get("registration")),
        _norm(row.get("typecode")),
      )
      cur.execute(insert_sql, values)
      n += 1

      if args.limit and n >= args.limit:
        break

      if args.batch_size > 0 and (n % args.batch_size) == 0:
        conn.commit()
        conn.execute("BEGIN;")
        elapsed = time.time() - t0
        rate = n / elapsed if elapsed > 0 else 0
        print(f"Imported {n} rows (skipped {n_skipped}) [{rate:.0f} rows/s]", file=sys.stderr)

    conn.commit()

    # Build indexes after bulk import (faster than maintaining them during inserts).
    conn.executescript(INDEXES_SQL)
    conn.commit()
    finalize_sqlite(conn)

    elapsed = time.time() - t0
    size = dst.stat().st_size if dst.exists() else 0
    print(
      f"Done. imported={n} skipped={n_skipped} output={dst} size={size/1024/1024:.1f}MB elapsed={elapsed:.1f}s",
      file=sys.stderr,
    )
  finally:
    conn.close()

  return 0


if __name__ == "__main__":
  raise SystemExit(main())
