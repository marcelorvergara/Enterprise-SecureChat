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
    )
