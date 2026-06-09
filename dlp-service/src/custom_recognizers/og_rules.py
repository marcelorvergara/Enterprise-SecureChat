from presidio_analyzer import Pattern, PatternRecognizer


# ── OG_VOLUMES ──────────────────────────────────────────────────────────────
# Matches reserve / production volume figures with explicit unit markers.
# Examples: 450 MMboe, 3.2 bbl/d, 120.5 m³/d, 1,200 bbl, 45Mboe
_VOLUME_PATTERNS = [
    Pattern(
        "VOLUME_MMBOE",
        r"\b\d+(?:[.,]\d+)?\s*(?:MM)?boe\b",
        0.9,
    ),
    Pattern(
        "VOLUME_BBL",
        r"\b\d+(?:[.,]\d{3})*(?:\.\d+)?\s*bbl(?:/d)?\b",
        0.9,
    ),
    Pattern(
        "VOLUME_M3",
        r"\b\d+(?:[.,]\d+)?\s*m[³3]/d\b",
        0.9,
    ),
    Pattern(
        "VOLUME_MBOE",
        r"\b\d+(?:[.,]\d+)?\s*Mboe\b",
        0.85,
    ),
]


def build_og_volumes_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="OG_VOLUMES",
        patterns=_VOLUME_PATTERNS,
        name="OgVolumesRecognizer",
        supported_language="pt",
    )


# ── ANP_PROCESS ─────────────────────────────────────────────────────────────
# Matches official ANP correspondence identifiers.
# Examples: Ofício Nº 402/2026, Ofício nº 10/2025, Processo 48500.0012/2025-31
_ANP_PATTERNS = [
    Pattern(
        "OFICIO",
        r"\bOf[íi]cio\s+N[oº°]?\s*\d+/\d{4}\b",
        0.95,
    ),
    Pattern(
        "PROCESSO",
        r"\bProcesso\s+\d{5}\.\d{4,6}/\d{4}-\d{2}\b",
        0.95,
    ),
    # Standalone SEI number without the "Processo" keyword — catches cases where
    # Claude reproduces the number inline without the prefix (e.g. "number 48500.0012/2025-31")
    Pattern(
        "PROCESSO_NUMBER_BARE",
        r"\b\d{5}\.\d{4}/\d{4}-\d{2}\b",
        0.90,
    ),
    Pattern(
        "LETTER_NUMBER",
        r"\b(?:Carta|Letter)\s+N[oº°]?\s*\d+/\d{4}\b",
        0.85,
    ),
]


def build_anp_process_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="ANP_PROCESS",
        patterns=_ANP_PATTERNS,
        name="AnpProcessRecognizer",
        supported_language="pt",
    )


# ── RESERVES_VARIATION ──────────────────────────────────────────────────────
# Matches percentage changes in reserves figures.
# Examples: +4.2% variações, -10.5% variation, +2% reserves
_VARIATION_PATTERNS = [
    Pattern(
        "VARIATION_PT",
        r"[+\-]\s*\d+(?:\.\d+)?\s*%\s*(?:varia[çc][ãa]o|varia[çc][oõ]es|reservas)?",
        0.9,
    ),
    Pattern(
        "VARIATION_EN",
        r"[+\-]\s*\d+(?:\.\d+)?\s*%\s*(?:variation|reserves|recovery\s+factor)?",
        0.9,
    ),
    # Bare percentage that follows reserve-related keywords
    Pattern(
        "RECOVERY_FACTOR",
        r"\b(?:recovery\s+factor|fator\s+de\s+recupera[çc][ãa]o)\s*[:=]?\s*\d+(?:\.\d+)?\s*%",
        0.9,
    ),
]


def build_reserves_variation_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="RESERVES_VARIATION",
        patterns=_VARIATION_PATTERNS,
        name="ReservesVariationRecognizer",
        supported_language="pt",
    )


# ── INVESTMENT_YEAR ──────────────────────────────────────────────────────────
# Matches year numbers (20xx) that appear in investment-planning contexts.
# High-confidence patterns use fixed-length lookbehinds so only the year is
# redacted, not the surrounding keyword.  Year-range patterns (e.g. "2025 a
# 2031") start below the 0.35 threshold and rely on the context-word boost.
_INVESTMENT_YEAR_PATTERNS = [
    Pattern("INV_YEAR_PT_EM",      r"(?<=investimento em )20\d{2}",   0.95),
    Pattern("INV_YEAR_PT_PARA",    r"(?<=investimento para )20\d{2}", 0.95),
    Pattern("INV_YEAR_PT_DE",      r"(?<=investimento de )20\d{2}",   0.95),
    Pattern("INV_YEAR_EN_IN_LC",   r"(?<=investment in )20\d{2}",     0.95),
    Pattern("INV_YEAR_EN_IN_UC",   r"(?<=Investment in )20\d{2}",     0.95),
    Pattern("INV_YEAR_EN_FOR_LC",  r"(?<=investment for )20\d{2}",    0.95),
    Pattern("INV_YEAR_EN_FOR_UC",  r"(?<=Investment for )20\d{2}",    0.95),
    Pattern("INV_YEAR_CAPEX_LC",   r"(?<=capex )20\d{2}",             0.95),
    Pattern("INV_YEAR_CAPEX_UC",   r"(?<=CAPEX )20\d{2}",             0.95),
    Pattern("INV_YEAR_OPEX_LC",    r"(?<=opex )20\d{2}",              0.95),
    Pattern("INV_YEAR_OPEX_UC",    r"(?<=OPEX )20\d{2}",              0.95),
    # "anos de investimento: 2025-2030" / "investment years: 2025"
    Pattern(
        "INV_YEAR_LABEL",
        r"\b(?:anos?\s+de\s+investimento|investment\s+years?)\s*:?\s*20\d{2}(?:\s*[-–a]\s*20\d{2})?\b",
        0.95,
    ),
    # Year ranges — only fire near context words (base < 0.35 threshold)
    Pattern("INV_YEAR_RANGE_PT",   r"\b20\d{2}\s+a\s+20\d{2}\b",    0.30),
    Pattern("INV_YEAR_RANGE_EN",   r"\b20\d{2}\s+to\s+20\d{2}\b",   0.30),
    Pattern("INV_YEAR_RANGE_DASH", r"\b20\d{2}\s*[-–]\s*20\d{2}\b", 0.30),
]


def build_investment_year_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="INVESTMENT_YEAR",
        patterns=_INVESTMENT_YEAR_PATTERNS,
        context=["investimento", "investment", "capex", "opex", "desembolso", "investir"],
        name="InvestmentYearRecognizer",
        supported_language="pt",
    )


# ── OG_CONTRACT ──────────────────────────────────────────────────────────────
# Matches contract-end dates/years, economic-limit values, and values
# explicitly associated with Cessão Onerosa / Transfer of Rights clauses.
_CONTRACT_PATTERNS = [
    # Contract end — full date (DD/MM/YYYY)
    Pattern(
        "CONTRACT_DATE_PT",
        r"\b(?:prazo\s+do\s+contrato|t[eé]rmino\s+do\s+contrato|vencimento\s+do\s+contrato"
        r"|data\s+de\s+t[eé]rmino|data\s+de\s+encerramento)\s*[:=]?\s*\d{1,2}/\d{1,2}/\d{4}\b",
        0.95,
    ),
    Pattern(
        "CONTRACT_DATE_EN",
        r"\b(?:contract\s+end\s+date?|contract\s+expiry|contract\s+termination)\s*[:=]?\s*\d{1,2}/\d{1,2}/\d{4}\b",
        0.95,
    ),
    # Contract end — year only
    Pattern(
        "CONTRACT_YEAR_PT",
        r"\b(?:prazo\s+do\s+contrato|t[eé]rmino\s+do\s+contrato|vencimento\s+do\s+contrato)\s*[:=]?\s*20\d{2}\b",
        0.90,
    ),
    Pattern(
        "CONTRACT_YEAR_EN",
        r"\b(?:contract\s+end|contract\s+expiry)\s*(?:year|date)?\s*[:=]?\s*20\d{2}\b",
        0.90,
    ),
    # Economic limits
    Pattern(
        "ECON_LIMIT_PT",
        r"\blimite\s+econ[oô]mico\s*[:=]?\s*\d+(?:[.,]\d+)?\s*(?:bbl/d|m[³3]/d|boe/d|%)?\b",
        0.90,
    ),
    Pattern(
        "ECON_LIMIT_EN",
        r"\beconomic\s+limit\s*[:=]?\s*\d+(?:[.,]\d+)?\s*(?:bbl/d|m[³3]/d|boe/d|%)?\b",
        0.90,
    ),
    # Cessão Onerosa / Transfer of Rights — associated percentage within the same sentence
    Pattern(
        "CESSAO_PERCENT",
        r"\b[Cc]ess[ãa]o\s+[Oo]nerosa[^.]{0,80}\d+(?:[.,]\d+)?\s*%",
        0.85,
    ),
    Pattern(
        "TOR_PERCENT",
        r"\b[Tt]ransfer\s+of\s+[Rr]ights?[^.]{0,80}\d+(?:[.,]\d+)?\s*%",
        0.85,
    ),
]


def build_og_contract_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="OG_CONTRACT",
        patterns=_CONTRACT_PATTERNS,
        name="OgContractRecognizer",
        supported_language="pt",
    )


# ── COMMODITY_PRICE ──────────────────────────────────────────────────────────
# Matches barrel and natural-gas sales prices, with or without a leading
# currency symbol (the /bbl or /MMBtu unit anchors the match).
_COMMODITY_PRICE_PATTERNS = [
    # "70/bbl", "70 USD/bbl", "USD 70/bbl", "R$350/bbl", "$70.50/bbl"
    Pattern(
        "PRICE_BBL",
        r"\b(?:(?:USD|EUR|BRL|R\$|\$|€)\s*)?\d+(?:[.,]\d+)?\s*(?:USD|EUR|BRL)?\s*/\s*bbl\b",
        0.90,
    ),
    # "2.50 USD/MMBtu", "USD 3.50/MMBtu", "3/mmbtu"
    Pattern(
        "PRICE_MMBTU",
        r"\b(?:(?:USD|EUR|BRL|R\$|\$|€)\s*)?\d+(?:[.,]\d+)?\s*(?:USD|EUR|BRL)?\s*/\s*(?:MMBtu|mmbtu|MMBTU)\b",
        0.90,
    ),
    # "preço do barril: 65" / "preço do gás: R$ 12"
    Pattern(
        "BARREL_PRICE_PT",
        r"\bpre[çc]o\s+(?:do\s+barril|do\s+g[áa]s\s+natural|do\s+g[áa]s|do\s+petr[oó]leo)\s*[:=]?\s*(?:USD|EUR|BRL|R\$|\$|€)?\s*\d+(?:[.,]\d+)?",
        0.90,
    ),
    # "barrel price: 70" / "crude price: USD 75" / "gas price: $2.80"
    Pattern(
        "BARREL_PRICE_EN",
        r"\b(?:barrel|crude|gas|oil)\s+(?:price|sales?\s+price)\s*[:=]?\s*(?:USD|EUR|BRL|R\$|\$|€)?\s*\d+(?:[.,]\d+)?",
        0.90,
    ),
    # "preço de venda do gás: R$ 9.80"
    Pattern(
        "GAS_SALES_PRICE_PT",
        r"\bpre[çc]o\s+de\s+venda\s+d[oa]\s+g[áa]s\s*[:=]?\s*(?:USD|BRL|R\$|\$)?\s*\d+(?:[.,]\d+)?",
        0.90,
    ),
]


def build_commodity_price_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="COMMODITY_PRICE",
        patterns=_COMMODITY_PRICE_PATTERNS,
        name="CommodityPriceRecognizer",
        supported_language="pt",
    )
