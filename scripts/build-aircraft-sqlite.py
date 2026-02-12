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
import json
import re
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
  typecode TEXT,
  military_hint INTEGER,
  year_built INTEGER,
  owner_operator TEXT
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
  p.add_argument(
    "--merge-basic-json",
    default="",
    help=(
      "Optional path to basic-ac-db NDJSON file "
      "(one JSON object per line) used to enrich and extend the SQLite DB."
    ),
  )
  return p.parse_args()


def _norm(s: str | None) -> str | None:
  if s is None:
    return None
  s = s.strip()
  return s if s else None


ICAO24_RE = re.compile(r"^[0-9a-f]{6}$")


def _norm_icao24(s: str | None) -> str | None:
  v = _norm(s)
  if v is None:
    return None
  v = v.lower()
  return v if ICAO24_RE.match(v) else None


def _to_bool_int(v: object) -> int | None:
  if isinstance(v, bool):
    return 1 if v else 0
  if isinstance(v, int):
    return 1 if v != 0 else 0
  if isinstance(v, str):
    s = v.strip().lower()
    if s in ("1", "true", "yes", "y"):
      return 1
    if s in ("0", "false", "no", "n"):
      return 0
  return None


def _to_year_int(v: object) -> int | None:
  if v is None:
    return None
  if isinstance(v, int):
    year = v
  elif isinstance(v, str):
    s = v.strip()
    if not s:
      return None
    if not s.isdigit():
      return None
    year = int(s)
  else:
    return None
  if year < 1900 or year > 2100:
    return None
  return year


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


def merge_basic_json(conn: sqlite3.Connection, path: Path, batch_size: int) -> dict[str, int]:
  if not path.exists():
    raise RuntimeError(f"basic json not found: {path}")

  conn.execute("DROP TABLE IF EXISTS basic_aircraft;")
  conn.execute(
    """
    CREATE TEMP TABLE basic_aircraft (
      icao TEXT PRIMARY KEY,
      reg TEXT,
      icaotype TEXT,
      short_type TEXT,
      manufacturer TEXT,
      model TEXT,
      mil INTEGER,
      year INTEGER,
      ownop TEXT
    );
    """
  )

  insert_basic_sql = """
    INSERT INTO basic_aircraft (
      icao, reg, icaotype, short_type, manufacturer, model, mil, year, ownop
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(icao) DO UPDATE SET
      reg=excluded.reg,
      icaotype=excluded.icaotype,
      short_type=excluded.short_type,
      manufacturer=excluded.manufacturer,
      model=excluded.model,
      mil=excluded.mil,
      year=excluded.year,
      ownop=excluded.ownop;
  """

  imported = 0
  skipped_json = 0
  skipped_icao = 0
  with path.open("r", encoding="utf-8", errors="replace") as f:
    conn.execute("BEGIN;")
    for i, line in enumerate(f, start=1):
      line = line.strip()
      if not line:
        continue
      try:
        row = json.loads(line)
      except json.JSONDecodeError:
        skipped_json += 1
        continue

      if not isinstance(row, dict):
        skipped_json += 1
        continue

      icao = _norm_icao24(row.get("icao"))  # type: ignore[arg-type]
      if not icao:
        skipped_icao += 1
        continue

      values = (
        icao,
        _norm(row.get("reg")),  # type: ignore[arg-type]
        _norm(row.get("icaotype")),  # type: ignore[arg-type]
        _norm(row.get("short_type")),  # type: ignore[arg-type]
        _norm(row.get("manufacturer")),  # type: ignore[arg-type]
        _norm(row.get("model")),  # type: ignore[arg-type]
        _to_bool_int(row.get("mil")),
        _to_year_int(row.get("year")),
        _norm(row.get("ownop")),  # type: ignore[arg-type]
      )
      conn.execute(insert_basic_sql, values)
      imported += 1

      if batch_size > 0 and (i % batch_size) == 0:
        conn.commit()
        conn.execute("BEGIN;")
    conn.commit()

  total_before = conn.execute("SELECT COUNT(*) FROM aircraft;").fetchone()[0]
  overlap_before = conn.execute(
    "SELECT COUNT(*) FROM aircraft a JOIN basic_aircraft b ON b.icao = a.icao24;"
  ).fetchone()[0]

  # Source priority:
  # - Existing CSV dataset stays authoritative.
  # - basic-ac-db fills blanks and adds rows that do not exist yet.
  update_existing_sql = """
    UPDATE aircraft
    SET
      registration = CASE
        WHEN registration IS NULL OR TRIM(registration) = ''
          THEN COALESCE(
            (SELECT NULLIF(TRIM(b.reg), '') FROM basic_aircraft b WHERE b.icao = aircraft.icao24),
            registration
          )
        ELSE registration
      END,
      manufacturer_name = CASE
        WHEN manufacturer_name IS NULL OR TRIM(manufacturer_name) = ''
          THEN COALESCE(
            (SELECT NULLIF(TRIM(b.manufacturer), '') FROM basic_aircraft b WHERE b.icao = aircraft.icao24),
            manufacturer_name
          )
        ELSE manufacturer_name
      END,
      model = CASE
        WHEN model IS NULL OR TRIM(model) = ''
          THEN COALESCE(
            (SELECT NULLIF(TRIM(b.model), '') FROM basic_aircraft b WHERE b.icao = aircraft.icao24),
            model
          )
        ELSE model
      END,
      typecode = CASE
        WHEN typecode IS NULL OR TRIM(typecode) = ''
          THEN COALESCE(
            (SELECT NULLIF(TRIM(b.icaotype), '') FROM basic_aircraft b WHERE b.icao = aircraft.icao24),
            typecode
          )
        ELSE typecode
      END,
      icao_aircraft_class = CASE
        WHEN icao_aircraft_class IS NULL OR TRIM(icao_aircraft_class) = ''
          THEN COALESCE(
            (SELECT NULLIF(TRIM(b.short_type), '') FROM basic_aircraft b WHERE b.icao = aircraft.icao24),
            icao_aircraft_class
          )
        ELSE icao_aircraft_class
      END,
      military_hint = COALESCE(
        (SELECT b.mil FROM basic_aircraft b WHERE b.icao = aircraft.icao24),
        military_hint
      ),
      year_built = COALESCE(
        (SELECT b.year FROM basic_aircraft b WHERE b.icao = aircraft.icao24),
        year_built
      ),
      owner_operator = COALESCE(
        (SELECT NULLIF(TRIM(b.ownop), '') FROM basic_aircraft b WHERE b.icao = aircraft.icao24),
        owner_operator
      )
    WHERE EXISTS (SELECT 1 FROM basic_aircraft b WHERE b.icao = aircraft.icao24);
  """

  insert_missing_sql = """
    INSERT INTO aircraft (
      icao24,
      icao_aircraft_class,
      manufacturer_name,
      model,
      registration,
      typecode,
      military_hint,
      year_built,
      owner_operator
    )
    SELECT
      b.icao,
      NULLIF(TRIM(b.short_type), ''),
      NULLIF(TRIM(b.manufacturer), ''),
      NULLIF(TRIM(b.model), ''),
      NULLIF(TRIM(b.reg), ''),
      NULLIF(TRIM(b.icaotype), ''),
      b.mil,
      b.year,
      NULLIF(TRIM(b.ownop), '')
    FROM basic_aircraft b
    WHERE NOT EXISTS (
      SELECT 1 FROM aircraft a WHERE a.icao24 = b.icao
    );
  """
  conn.execute("BEGIN;")
  conn.execute(update_existing_sql)
  conn.execute(insert_missing_sql)
  conn.commit()

  total_after = conn.execute("SELECT COUNT(*) FROM aircraft;").fetchone()[0]
  military_true = conn.execute(
    "SELECT COUNT(*) FROM aircraft WHERE military_hint = 1;"
  ).fetchone()[0]

  return {
    "basic_imported": imported,
    "basic_skipped_json": skipped_json,
    "basic_skipped_icao": skipped_icao,
    "overlap_before": overlap_before,
    "rows_before": total_before,
    "rows_after": total_after,
    "rows_added": total_after - total_before,
    "military_true_total": military_true,
  }


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

    merge_stats: dict[str, int] | None = None
    if args.merge_basic_json:
      merge_stats = merge_basic_json(conn, Path(args.merge_basic_json), args.batch_size)

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
    if merge_stats:
      print(
        "Merge summary: "
        f"basic_imported={merge_stats['basic_imported']} "
        f"skipped_json={merge_stats['basic_skipped_json']} "
        f"skipped_icao={merge_stats['basic_skipped_icao']} "
        f"overlap_before={merge_stats['overlap_before']} "
        f"rows_before={merge_stats['rows_before']} "
        f"rows_after={merge_stats['rows_after']} "
        f"rows_added={merge_stats['rows_added']} "
        f"military_true_total={merge_stats['military_true_total']}",
        file=sys.stderr,
      )
  finally:
    conn.close()

  return 0


if __name__ == "__main__":
  raise SystemExit(main())
