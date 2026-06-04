from presidio_analyzer import AnalyzerEngine, RecognizerRegistry
from presidio_anonymizer import AnonymizerEngine
from presidio_anonymizer.entities import OperatorConfig

from .custom_recognizers.financial_figures import build_financial_figure_recognizer

_DEFAULT_ENTITIES = [
    "PERSON",
    "EMAIL_ADDRESS",
    "PHONE_NUMBER",
    "CREDIT_CARD",
    "FINANCIAL_FIGURE",
]

# Engines are module-level singletons — initialized once at startup, never per request.
_analyzer: AnalyzerEngine | None = None
_anonymizer: AnonymizerEngine | None = None


def init_engines() -> None:
    global _analyzer, _anonymizer
    registry = RecognizerRegistry()
    registry.load_predefined_recognizers()
    registry.add_recognizer(build_financial_figure_recognizer())
    _analyzer = AnalyzerEngine(registry=registry)
    _anonymizer = AnonymizerEngine()


def analyze_and_anonymize(text: str) -> tuple[str, int]:
    if _analyzer is None or _anonymizer is None:
        raise RuntimeError("Engines not initialized — call init_engines() first")

    results = _analyzer.analyze(text=text, entities=_DEFAULT_ENTITIES, language="en")

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
