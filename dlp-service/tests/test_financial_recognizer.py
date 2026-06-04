"""Tests for the custom FINANCIAL_FIGURE Presidio recognizer."""

import pytest
from presidio_analyzer import AnalyzerEngine, RecognizerRegistry

from src.custom_recognizers.financial_figures import build_financial_figure_recognizer


@pytest.fixture(scope="module")
def analyzer() -> AnalyzerEngine:
    registry = RecognizerRegistry()
    registry.add_recognizer(build_financial_figure_recognizer())
    return AnalyzerEngine(registry=registry)


def _entities(analyzer: AnalyzerEngine, text: str) -> list[str]:
    results = analyzer.analyze(text=text, entities=["FINANCIAL_FIGURE"], language="en")
    return [text[r.start:r.end] for r in results]


class TestDollarAmounts:
    def test_simple_dollar_amount(self, analyzer):
        assert "$125,000" in _entities(analyzer, "Budget is $125,000 this year.")

    def test_dollar_with_cents(self, analyzer):
        assert "$12.50" in _entities(analyzer, "The fee is $12.50.")

    def test_large_dollar_amount(self, analyzer):
        assert "$1,234,567.89" in _entities(analyzer, "Revenue: $1,234,567.89")


class TestEuroAmounts:
    def test_euro_amount(self, analyzer):
        assert "€125,000" in _entities(analyzer, "Cost is €125,000.")

    def test_euro_with_cents(self, analyzer):
        assert "€12.50" in _entities(analyzer, "Price: €12.50")


class TestBrlAmounts:
    def test_brl_with_space(self, analyzer):
        hits = _entities(analyzer, "Salário: R$ 1.234,56")
        assert any(h.startswith("R$") for h in hits)

    def test_brl_without_space(self, analyzer):
        hits = _entities(analyzer, "Valor: R$1234")
        assert any(h.startswith("R$") for h in hits)


class TestTextLargeAmounts:
    def test_million_usd(self, analyzer):
        assert "45 million USD" in _entities(analyzer, "The deal is worth 45 million USD.")

    def test_billion_eur(self, analyzer):
        assert "1.2 billion EUR" in _entities(analyzer, "Valuation: 1.2 billion EUR")

    def test_thousand_brl(self, analyzer):
        assert "500 thousand BRL" in _entities(analyzer, "Cost: 500 thousand BRL")

    def test_million_dollars(self, analyzer):
        assert "3 million dollars" in _entities(analyzer, "We raised 3 million dollars.")


class TestCurrencyCodeAmounts:
    def test_usd_code(self, analyzer):
        assert "USD 125,000" in _entities(analyzer, "Invoice total: USD 125,000")

    def test_eur_code(self, analyzer):
        assert "EUR 12,000" in _entities(analyzer, "Transfer amount: EUR 12,000")

    def test_brl_code(self, analyzer):
        assert "BRL 5,000" in _entities(analyzer, "Payment: BRL 5,000")


class TestNonFinancialText:
    def test_plain_number_not_flagged(self, analyzer):
        assert _entities(analyzer, "There are 42 employees.") == []

    def test_year_not_flagged(self, analyzer):
        assert _entities(analyzer, "Fiscal year 2024 report.") == []

    def test_percentage_not_flagged(self, analyzer):
        assert _entities(analyzer, "Growth rate was 8.5%.") == []


class TestRedactionBehavior:
    """Verify that detected entities are actually redacted by the anonymizer."""

    def test_dollar_amount_is_redacted(self):
        from presidio_anonymizer import AnonymizerEngine
        from presidio_anonymizer.entities import OperatorConfig
        from src.analyzer import init_engines, analyze_and_anonymize

        init_engines()
        cleaned, count = analyze_and_anonymize("The budget is $500,000 for Q3.")
        assert "$500,000" not in cleaned
        assert "[REDACTED]" in cleaned
        assert count >= 1

    def test_clean_text_passes_through_unchanged(self):
        from src.analyzer import init_engines, analyze_and_anonymize

        init_engines()
        text = "Our onboarding process takes two weeks."
        cleaned, count = analyze_and_anonymize(text)
        assert cleaned == text
        assert count == 0
