"""Tests for the document classification extractor (src/classifier.py)."""

import fitz  # PyMuPDF
import openpyxl
import pytest

from src.classifier import extract_classification
from src.crawlers.anp import ANPCrawler


# ── helpers ──────────────────────────────────────────────────────────────────


def _make_pdf(tmp_path, keywords="", subject="", title="") -> str:
    path = str(tmp_path / "test.pdf")
    doc = fitz.open()
    page = doc.new_page()
    page.insert_text((72, 100), "body text")
    doc.set_metadata({"keywords": keywords, "subject": subject, "title": title})
    doc.save(path)
    doc.close()
    return path


def _make_xlsx(tmp_path, keywords="", subject="", title="") -> str:
    path = str(tmp_path / "test.xlsx")
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.append(["col"])
    ws.append(["value"])
    wb.properties.keywords = keywords
    wb.properties.subject = subject
    wb.properties.title = title
    wb.save(path)
    return path


def _make_xls(tmp_path) -> str:
    # xlrd-only format: create a minimal .xls file via openpyxl then rename;
    # since xlrd cannot be written with openpyxl we just write a stub file with
    # the .xls extension — classifier must return "Internal" without crashing.
    path = str(tmp_path / "stub.xls")
    with open(path, "wb") as f:
        f.write(b"\xd0\xcf\x11\xe0")  # OLE2 magic bytes — xlrd stub
    return path


# ── TestExtractClassification ─────────────────────────────────────────────────


class TestExtractClassification:
    def test_pdf_confidential_keyword_returns_confidential(self, tmp_path):
        path = _make_pdf(tmp_path, keywords="Confidential")
        assert extract_classification(path) == "Confidential"

    def test_pdf_confidential_uppercase_is_normalised(self, tmp_path):
        path = _make_pdf(tmp_path, keywords="CONFIDENTIAL")
        assert extract_classification(path) == "Confidential"

    def test_pdf_confidential_mixedcase_is_normalised(self, tmp_path):
        path = _make_pdf(tmp_path, keywords="confidential")
        assert extract_classification(path) == "Confidential"

    def test_pdf_public_keyword_returns_public(self, tmp_path):
        path = _make_pdf(tmp_path, keywords="Public")
        assert extract_classification(path) == "Public"

    def test_pdf_no_metadata_returns_internal(self, tmp_path):
        path = _make_pdf(tmp_path)
        assert extract_classification(path) == "Internal"

    def test_pdf_confidential_in_subject_field_detected(self, tmp_path):
        path = _make_pdf(tmp_path, subject="Confidential Reserves Report")
        assert extract_classification(path) == "Confidential"

    def test_pdf_confidential_wins_over_public_same_doc(self, tmp_path):
        # If somehow both appear, highest tier (Confidential) wins.
        path = _make_pdf(tmp_path, keywords="Public", subject="Confidential")
        assert extract_classification(path) == "Confidential"

    def test_xlsx_keywords_property_returns_confidential(self, tmp_path):
        path = _make_xlsx(tmp_path, keywords="Confidential")
        assert extract_classification(path) == "Confidential"

    def test_xlsx_subject_property_returns_internal(self, tmp_path):
        path = _make_xlsx(tmp_path, subject="Internal use only")
        assert extract_classification(path) == "Internal"

    def test_xlsx_no_metadata_returns_internal(self, tmp_path):
        path = _make_xlsx(tmp_path)
        assert extract_classification(path) == "Internal"

    def test_xls_always_returns_internal(self, tmp_path):
        # xlrd has no property API — must default safely.
        path = tmp_path / "stub.xls"
        path.write_bytes(b"")
        assert extract_classification(str(path)) == "Internal"

    def test_image_ext_returns_internal(self, tmp_path):
        path = str(tmp_path / "photo.png")
        open(path, "wb").close()
        assert extract_classification(path) == "Internal"

    def test_txt_ext_returns_internal(self, tmp_path):
        path = str(tmp_path / "doc.txt")
        open(path, "w").close()
        assert extract_classification(path) == "Internal"

    def test_csv_ext_returns_internal(self, tmp_path):
        path = str(tmp_path / "data.csv")
        open(path, "w").close()
        assert extract_classification(path) == "Internal"

    def test_unknown_ext_returns_internal(self, tmp_path):
        path = str(tmp_path / "file.xyz")
        open(path, "w").close()
        assert extract_classification(path) == "Internal"


# ── TestANPCrawlerRouting ─────────────────────────────────────────────────────


class TestANPCrawlerRouting:
    """Verify ANPCrawler subject_path routing and classification logic."""

    def setup_method(self):
        self.crawler = ANPCrawler()

    def test_bar_keyword_routes_to_bar_path(self):
        url = "https://www.gov.br/anp/pt-br/.../reserva-de-oleo"
        assert self.crawler.subject_path_for(url) == "regulatory/anp/bar"

    def test_recursos_keyword_routes_to_bar_path(self):
        url = "https://www.gov.br/anp/pt-br/.../recursos-exploratorios"
        assert self.crawler.subject_path_for(url) == "regulatory/anp/bar"

    def test_bar_keyword_routes_to_bar_path_explicit(self):
        url = "https://www.gov.br/anp/pt-br/.../bar-2024.pdf"
        assert self.crawler.subject_path_for(url) == "regulatory/anp/bar"

    def test_general_anp_url_routes_to_anp_path(self):
        url = "https://www.gov.br/anp/pt-br/assuntos/exploracao-e-producao-de-oleo-e-gas"
        assert self.crawler.subject_path_for(url) == "regulatory/anp"

    def test_bar_path_classifies_confidential(self):
        assert self.crawler.classification_for("regulatory/anp/bar", "") == "Confidential"

    def test_anp_path_classifies_public(self):
        assert self.crawler.classification_for("regulatory/anp", "") == "Public"
