"""Generates PDF and XLSX test files stamped with classification metadata.

Usage:
    python ingestion/tests/generate_test_fixtures.py

Outputs (ingestion/tests/fixtures/):
    classification_public.pdf
    classification_internal.pdf
    classification_confidential.pdf
    classification_public.xlsx
    classification_internal.xlsx
    classification_confidential.xlsx

Uses only dependencies already in requirements.txt (PyMuPDF, openpyxl).

Note: .docx (Word) is excluded — python-docx is not in requirements.txt.
      If Word support is added in a future sprint, extend this script.
"""

import fitz  # PyMuPDF
import openpyxl
from pathlib import Path

FIXTURES_DIR = Path(__file__).parent / "fixtures"
LEVELS = ["Public", "Internal", "Confidential"]
BODY = "Synthetic test document. Campo: Pré-Sal. Produção: 450,000 bbl/d."


def create_pdf(path: Path, level: str) -> None:
    doc = fitz.open()
    page = doc.new_page()
    page.insert_text((72, 100), f"[{level}] {BODY}")
    doc.set_metadata({
        "keywords": level,
        "subject":  f"{level} document",
        "title":    f"Test {level} PDF",
    })
    doc.save(str(path))
    doc.close()


def create_xlsx(path: Path, level: str) -> None:
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Data"
    ws.append(["Field", "Value"])
    ws.append(["Content", BODY])
    ws.append(["Level", level])
    wb.properties.keywords = level
    wb.properties.subject = f"{level} document"
    wb.properties.title = f"Test {level} XLSX"
    wb.save(str(path))


def main() -> None:
    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    for level in LEVELS:
        slug = level.lower()
        create_pdf(FIXTURES_DIR / f"classification_{slug}.pdf", level)
        create_xlsx(FIXTURES_DIR / f"classification_{slug}.xlsx", level)
        print(f"Created {slug} PDF + XLSX")
    print(f"\nFixtures written to {FIXTURES_DIR}")


if __name__ == "__main__":
    main()
