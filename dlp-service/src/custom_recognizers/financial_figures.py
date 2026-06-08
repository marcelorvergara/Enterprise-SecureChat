from presidio_analyzer import Pattern, PatternRecognizer

_PATTERNS = [
    # $125,000  $12.50  $1,234,567.89
    Pattern("DOLLAR_AMOUNT", r"\$[\d,]+(?:\.\d{1,2})?", 0.9),
    # €125,000  €12.50
    Pattern("EURO_AMOUNT", r"€[\d,]+(?:\.\d{1,2})?", 0.9),
    # R$ 1.234,56  R$1234
    Pattern("BRL_AMOUNT", r"R\$\s?[\d.,]+", 0.9),
    # 45 million USD  1.2 billion EUR  500 thousand BRL
    Pattern(
        "TEXT_LARGE_AMOUNT",
        r"\b\d+(?:\.\d+)?\s+(?:million|billion|thousand)\s+(?:USD|EUR|BRL|dollars?|euros?|reais?)\b",
        0.85,
    ),
    # USD 125,000  EUR 12,000  BRL 5,000
    Pattern("CURRENCY_CODE_AMOUNT", r"\b(?:USD|EUR|BRL)\s+[\d,]+(?:\.\d{1,2})?\b", 0.85),
    # 450,000  1,234,567  10,000.00  (comma-grouped numbers — no currency marker needed)
    # Score 0.75: lower confidence than symbol-anchored patterns to limit false positives.
    # Requires at least one comma-separated triple so plain years (2024) are never matched.
    Pattern("BARE_AMOUNT", r"\b\d+(?:,\d{3})+(?:\.\d{1,2})?\b", 0.75),
]


def build_financial_figure_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="FINANCIAL_FIGURE",
        patterns=_PATTERNS,
        name="FinancialFigureRecognizer",
        supported_language="pt",
    )
