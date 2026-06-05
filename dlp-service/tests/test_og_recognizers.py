"""Tests for O&G custom Presidio recognizers and redaction pipeline."""

import pytest
from presidio_analyzer import AnalyzerEngine, RecognizerRegistry

from src.custom_recognizers.og_rules import (
    build_og_volumes_recognizer,
    build_anp_process_recognizer,
    build_reserves_variation_recognizer,
)
from src.analyzer import init_engines, analyze_and_anonymize


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture(scope="module")
def volumes_analyzer() -> AnalyzerEngine:
    registry = RecognizerRegistry()
    registry.add_recognizer(build_og_volumes_recognizer())
    return AnalyzerEngine(registry=registry)


@pytest.fixture(scope="module")
def anp_analyzer() -> AnalyzerEngine:
    registry = RecognizerRegistry()
    registry.add_recognizer(build_anp_process_recognizer())
    return AnalyzerEngine(registry=registry)


@pytest.fixture(scope="module")
def variation_analyzer() -> AnalyzerEngine:
    registry = RecognizerRegistry()
    registry.add_recognizer(build_reserves_variation_recognizer())
    return AnalyzerEngine(registry=registry)


def _hits(analyzer: AnalyzerEngine, text: str, entity: str) -> list[str]:
    results = analyzer.analyze(text=text, entities=[entity], language="en")
    return [text[r.start:r.end] for r in results]


# ── OG_VOLUMES ────────────────────────────────────────────────────────────────

class TestOgVolumes:
    def test_mmboe(self, volumes_analyzer):
        assert any("MMboe" in h for h in _hits(volumes_analyzer, "Reserves: 3.2 MMboe.", "OG_VOLUMES"))

    def test_boe_without_mm(self, volumes_analyzer):
        assert any("boe" in h for h in _hits(volumes_analyzer, "Production: 450boe.", "OG_VOLUMES"))

    def test_bbl_per_day(self, volumes_analyzer):
        assert any("bbl/d" in h for h in _hits(volumes_analyzer, "Flow rate: 1,200 bbl/d.", "OG_VOLUMES"))

    def test_bbl_standalone(self, volumes_analyzer):
        assert any("bbl" in h for h in _hits(volumes_analyzer, "Lifted 500,000 bbl last month.", "OG_VOLUMES"))

    def test_m3_per_day(self, volumes_analyzer):
        assert any("m³/d" in h or "m3/d" in h
                   for h in _hits(volumes_analyzer, "Gas rate: 120.5 m³/d.", "OG_VOLUMES"))

    def test_mboe(self, volumes_analyzer):
        assert any("Mboe" in h for h in _hits(volumes_analyzer, "Contingent: 45Mboe.", "OG_VOLUMES"))

    def test_plain_number_not_matched(self, volumes_analyzer):
        assert _hits(volumes_analyzer, "Section 3 paragraph 2.", "OG_VOLUMES") == []


# ── ANP_PROCESS ───────────────────────────────────────────────────────────────

class TestAnpProcess:
    def test_oficio_with_accented_i(self, anp_analyzer):
        hits = _hits(anp_analyzer, "Ref.: Ofício Nº 402/2026.", "ANP_PROCESS")
        assert any("Ofício" in h for h in hits)

    def test_oficio_without_accent(self, anp_analyzer):
        hits = _hits(anp_analyzer, "See Oficio No 10/2025.", "ANP_PROCESS")
        assert any("Oficio" in h for h in hits)

    def test_processo(self, anp_analyzer):
        hits = _hits(anp_analyzer, "Processo 48500.0012/2025-31 is pending.", "ANP_PROCESS")
        assert any("Processo" in h for h in hits)

    def test_plain_text_not_matched(self, anp_analyzer):
        assert _hits(anp_analyzer, "The meeting is on Monday.", "ANP_PROCESS") == []


# ── RESERVES_VARIATION ────────────────────────────────────────────────────────

class TestReservesVariation:
    def test_positive_variation_pt(self, variation_analyzer):
        hits = _hits(variation_analyzer, "Houve +4.2% variações nas reservas.", "RESERVES_VARIATION")
        assert any("+4.2%" in h for h in hits)

    def test_negative_variation_en(self, variation_analyzer):
        hits = _hits(variation_analyzer, "Reserves showed -10.5% variation.", "RESERVES_VARIATION")
        assert any("-10.5%" in h for h in hits)

    def test_recovery_factor(self, variation_analyzer):
        hits = _hits(variation_analyzer, "Recovery factor: 32.5%", "RESERVES_VARIATION")
        assert len(hits) > 0

    def test_plain_percentage_not_matched(self, variation_analyzer):
        # A standalone % with no reserves context should not be caught
        assert _hits(variation_analyzer, "Efficiency improved by 5%.", "RESERVES_VARIATION") == []


# ── End-to-end redaction pipeline ────────────────────────────────────────────

class TestOgRedactionPipeline:
    def setup_method(self):
        init_engines()

    def test_volume_is_redacted_by_default(self):
        cleaned, count = analyze_and_anonymize("Total reserves: 3.2 MMboe.")
        assert "3.2 MMboe" not in cleaned
        assert "[REDACTED]" in cleaned
        assert count >= 1

    def test_volume_passes_through_when_allowed(self):
        text = "Total reserves: 3.2 MMboe."
        cleaned, count = analyze_and_anonymize(text, allow_entities=["OG_VOLUMES"])
        assert "3.2 MMboe" in cleaned
        assert count == 0

    def test_anp_process_is_redacted(self):
        cleaned, count = analyze_and_anonymize("See Ofício Nº 402/2026 for details.")
        assert "402/2026" not in cleaned
        assert "[REDACTED]" in cleaned
        assert count >= 1

    def test_reserves_variation_is_redacted(self):
        cleaned, count = analyze_and_anonymize("Reserves dropped -10.5% variation this quarter.")
        assert "-10.5%" not in cleaned
        assert "[REDACTED]" in cleaned
        assert count >= 1

    def test_privileged_allow_list_passes_volumes_and_financials(self):
        text = "Reserves: 3.2 MMboe at USD 45.00/bbl."
        cleaned, count = analyze_and_anonymize(
            text, allow_entities=["OG_VOLUMES", "RESERVES_VARIATION", "FINANCIAL_FIGURE"]
        )
        assert "3.2 MMboe" in cleaned
        assert count == 0
