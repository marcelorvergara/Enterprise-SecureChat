from presidio_analyzer import AnalyzerEngine, RecognizerRegistry
from presidio_anonymizer import AnonymizerEngine
from presidio_anonymizer.entities import OperatorConfig

from .custom_recognizers.financial_figures import build_financial_figure_recognizer
from .custom_recognizers.og_rules import (
    build_og_volumes_recognizer,
    build_anp_process_recognizer,
    build_reserves_variation_recognizer,
)

_DEFAULT_ENTITIES = [
    "PERSON",
    "EMAIL_ADDRESS",
    "PHONE_NUMBER",
    "CREDIT_CARD",
    "FINANCIAL_FIGURE",
    "ANP_PROCESS",
    "RESERVES_VARIATION",
]

# Acronyms and names that spaCy en_core_web_lg misclassifies as PERSON.
# FGA handles access control at retrieval time, so these are safe to surface.
_PERSON_ALLOWLIST = frozenset({
    "ANP", "PPSA", "IBAMA", "INPE", "BNDES", "CNPE", "MME",
    "PETROBRAS", "TOTAL", "SHELL", "BP", "REPSOL", "EQUINOR",
    "FPSO", "LNG", "LPG",
})

# Engines are module-level singletons — initialized once at startup, never per request.
_analyzer: AnalyzerEngine | None = None
_anonymizer: AnonymizerEngine | None = None


def init_engines() -> None:
    global _analyzer, _anonymizer
    registry = RecognizerRegistry()
    registry.load_predefined_recognizers()
    registry.add_recognizer(build_financial_figure_recognizer())
    registry.add_recognizer(build_og_volumes_recognizer())
    registry.add_recognizer(build_anp_process_recognizer())
    registry.add_recognizer(build_reserves_variation_recognizer())
    _analyzer = AnalyzerEngine(registry=registry)
    _anonymizer = AnonymizerEngine()


def analyze_and_anonymize(
    text: str, allow_entities: list[str] | None = None
) -> tuple[str, int]:
    if _analyzer is None or _anonymizer is None:
        raise RuntimeError("Engines not initialized — call init_engines() first")

    entities = [e for e in _DEFAULT_ENTITIES if e not in (allow_entities or [])]
    if not entities:
        return text, 0

    results = _analyzer.analyze(text=text, entities=entities, language="en")
    results = [
        r for r in results
        if not (r.entity_type == "PERSON" and text[r.start:r.end].upper() in _PERSON_ALLOWLIST)
    ]

    if not results:
        return text, 0

    operators = {
        entity: OperatorConfig("replace", {"new_value": "[REDACTED]"})
        for entity in _DEFAULT_ENTITIES
    }
    anonymized = _anonymizer.anonymize(
        text=text,
        analyzer_results=results,
        operators=operators,
    )
    return anonymized.text, len(results)
