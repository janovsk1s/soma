#!/usr/bin/env python3
"""Build Soma's deterministic compact Fineli + CIQUAL offline catalog."""

from __future__ import annotations

import argparse
import csv
import gzip
import io
import re
import zipfile
from pathlib import Path
from xml.etree import ElementTree


SHEET_NAMESPACE = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
CELL_COLUMN = re.compile(r"[A-Z]+")


def clean(value: str | None) -> str:
    return (
        (value or "")
        .replace("\t", " ")
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("|", "/")
        .strip()
    )


def decimal(value: str | None) -> float | None:
    text = clean(value).replace(",", ".")
    if not text or text == "-" or text.lower() == "traces" or text.startswith("<"):
        return None
    try:
        result = float(text)
    except ValueError:
        return None
    return result if result >= 0 else None


def compact_number(value: float | None) -> str:
    return "" if value is None else format(value, ".7g")


def read_semicolon(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="iso-8859-1", newline="") as source:
        return list(csv.DictReader(source, delimiter=";"))


def fineli_rows(directory: Path):
    names: dict[str, dict[str, str]] = {}
    for language in ("EN", "FI", "SV"):
        for row in read_semicolon(directory / f"foodname_{language}.csv"):
            name = clean(row.get("FOODNAME"))
            if name:
                names.setdefault(row["FOODID"], {})[language] = name

    nutrients: dict[str, dict[str, float]] = {}
    wanted = {"ENERC", "PROT", "CHOAVL", "FAT"}
    for row in read_semicolon(directory / "component_value.csv"):
        component = row.get("EUFDNAME")
        if component in wanted:
            value = decimal(row.get("BESTLOC"))
            if value is not None:
                nutrients.setdefault(row["FOODID"], {})[component] = value

    portions: dict[str, dict[str, float]] = {}
    for row in read_semicolon(directory / "foodaddunit.csv"):
        mass = decimal(row.get("MASS"))
        if mass is not None:
            portions.setdefault(row["FOODID"], {})[row["FOODUNIT"]] = mass

    for food_id in sorted(names, key=int):
        localized = names[food_id]
        fallback = next(iter(localized.values()))
        # Keep stable EN/FI/SV positions for the tiny Android loader. Missing
        # translations repeat the first available name instead of shifting the
        # language columns and showing Finnish as English, or Swedish as Finnish.
        aliases = [localized.get(language, fallback) for language in ("EN", "FI", "SV")]
        values = nutrients.get(food_id, {})
        energy_kj = values.get("ENERC")
        energy_kcal = energy_kj / 4.184 if energy_kj is not None else None
        portion = portions.get(food_id, {})
        piece = first(portion, "KPL_M", "KPL_VALM", "KPL_S", "KPL_L")
        serving = first(portion, "PORTM", "PORTTBL", "PORTS", "PORTL")
        yield (
            "FINELI",
            food_id,
            "|".join(aliases),
            compact_number(energy_kcal),
            compact_number(values.get("PROT")),
            compact_number(values.get("CHOAVL")),
            compact_number(values.get("FAT")),
            compact_number(piece),
            compact_number(serving),
        )


def first(values: dict[str, float], *keys: str) -> float | None:
    return next((values[key] for key in keys if key in values), None)


def ciqual_rows(workbook: Path):
    with zipfile.ZipFile(workbook) as archive:
        shared_root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
        shared = [
            "".join(node.text or "" for node in item.findall(".//m:t", SHEET_NAMESPACE))
            for item in shared_root.findall("m:si", SHEET_NAMESPACE)
        ]
        sheet = ElementTree.fromstring(archive.read("xl/worksheets/sheet1.xml"))
        rows = sheet.findall(".//m:sheetData/m:row", SHEET_NAMESPACE)
        for row in rows[1:]:
            values: dict[str, str] = {}
            for cell in row.findall("m:c", SHEET_NAMESPACE):
                reference = cell.attrib.get("r", "")
                column_match = CELL_COLUMN.match(reference)
                value_node = cell.find("m:v", SHEET_NAMESPACE)
                if column_match is None or value_node is None:
                    continue
                raw = value_node.text or ""
                values[column_match.group()] = (
                    shared[int(raw)] if cell.attrib.get("t") == "s" else raw
                )
            food_id = clean(values.get("G"))
            name = clean(values.get("H"))
            if not food_id or not name:
                continue
            scientific = clean(values.get("I"))
            aliases = name if not scientific else f"{name}|{scientific}"
            protein = decimal(values.get("O"))
            if protein is None:
                protein = decimal(values.get("P"))
            yield (
                "CIQUAL",
                food_id,
                aliases,
                compact_number(decimal(values.get("K"))),
                compact_number(protein),
                compact_number(decimal(values.get("Q"))),
                compact_number(decimal(values.get("R"))),
                "",
                "",
            )


def write_catalog(rows, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            with io.TextIOWrapper(compressed, encoding="utf-8", newline="") as text:
                writer = csv.writer(text, delimiter="\t", lineterminator="\n")
                writer.writerow(("# Soma EU food catalog v1",))
                writer.writerow(("# Fineli release 20.0 / THL / CC-BY 4.0 / fineli.fi",))
                writer.writerow(
                    ("# Ciqual 2025 / Anses / Open Licence 2.0 / doi:10.57745/RDMHWY",),
                )
                writer.writerow(
                    (
                        "source",
                        "id",
                        "names",
                        "kcal100g",
                        "protein100g",
                        "carb100g",
                        "fat100g",
                        "piece_g",
                        "serving_g",
                    ),
                )
                writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fineli-dir", type=Path, required=True)
    parser.add_argument("--ciqual-xlsx", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = [*fineli_rows(args.fineli_dir), *ciqual_rows(args.ciqual_xlsx)]
    write_catalog(rows, args.output)
    print(f"wrote {len(rows)} foods to {args.output}")


if __name__ == "__main__":
    main()
