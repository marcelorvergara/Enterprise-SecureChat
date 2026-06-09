from presidio_analyzer import AnalyzerEngine, RecognizerRegistry
from presidio_analyzer.nlp_engine import NlpEngineProvider
from presidio_analyzer.predefined_recognizers import (
    SpacyRecognizer,
    EmailRecognizer,
    PhoneRecognizer,
    CreditCardRecognizer,
)
from presidio_anonymizer import AnonymizerEngine
from presidio_anonymizer.entities import OperatorConfig

from .custom_recognizers.financial_figures import build_financial_figure_recognizer
from .custom_recognizers.og_rules import (
    build_og_volumes_recognizer,
    build_anp_process_recognizer,
    build_reserves_variation_recognizer,
    build_investment_year_recognizer,
    build_og_contract_recognizer,
    build_commodity_price_recognizer,
)

_DEFAULT_ENTITIES = [
    "PERSON",
    "EMAIL_ADDRESS",
    "PHONE_NUMBER",
    "CREDIT_CARD",
    "DATE_TIME",
    "FINANCIAL_FIGURE",
    "OG_VOLUMES",
    "ANP_PROCESS",
    "RESERVES_VARIATION",
    "INVESTMENT_YEAR",
    "OG_CONTRACT",
    "COMMODITY_PRICE",
]

# Belt-and-suspenders allowlist for the PT model.
# pt_core_news_lg correctly classifies Brazilian geological/basin names as LOC/GPE,
# but multi-word foreign acronyms (FPSO, LNG) can still score as PERSON.
_PERSON_ALLOWLIST = frozenset({
    "ANP", "PPSA", "IBAMA", "INPE", "BNDES", "CNPE", "MME",
    "PETROBRAS", "TOTAL", "SHELL", "BP", "REPSOL", "EQUINOR",
    "FPSO", "LNG", "LPG",
})

# Minimum confidence for a PERSON detection to be kept.
_MIN_PERSON_SCORE = 0.75

_analyzer: AnalyzerEngine | None = None
_anonymizer: AnonymizerEngine | None = None


def init_engines() -> None:
    global _analyzer, _anonymizer

    # Use the Portuguese spaCy model so Brazilian basin/field/formation names are
    # classified as LOC/GPE by NER, not misidentified as PERSON by en_core_web_lg.
    nlp_config = {
        "nlp_engine_name": "spacy",
        "models": [{"lang_code": "pt", "model_name": "pt_core_news_lg"}],
    }
    nlp_engine = NlpEngineProvider(nlp_configuration=nlp_config).create_engine()

    # Build the registry explicitly — load_predefined_recognizers() registers
    # English-only recognizers that are filtered out when language="pt" is used.
    registry = RecognizerRegistry()
    registry.add_recognizer(SpacyRecognizer(supported_language="pt"))
    registry.add_recognizer(EmailRecognizer(supported_language="pt"))
    registry.add_recognizer(PhoneRecognizer(supported_language="pt"))
    registry.add_recognizer(CreditCardRecognizer(supported_language="pt"))
    registry.add_recognizer(build_financial_figure_recognizer())
    registry.add_recognizer(build_og_volumes_recognizer())
    registry.add_recognizer(build_anp_process_recognizer())
    registry.add_recognizer(build_reserves_variation_recognizer())
    registry.add_recognizer(build_investment_year_recognizer())
    registry.add_recognizer(build_og_contract_recognizer())
    registry.add_recognizer(build_commodity_price_recognizer())

    _analyzer = AnalyzerEngine(
        nlp_engine=nlp_engine,
        registry=registry,
        supported_languages=["pt"],
    )
    _anonymizer = AnonymizerEngine()


def analyze_and_anonymize(
    text: str, allow_entities: list[str] | None = None
) -> tuple[str, int]:
    if _analyzer is None or _anonymizer is None:
        raise RuntimeError("Engines not initialized — call init_engines() first")

    entities = [e for e in _DEFAULT_ENTITIES if e not in (allow_entities or [])]
    if not entities:
        return text, 0

    results = _analyzer.analyze(text=text, entities=entities, language="pt")
    results = [
        r for r in results
        if not (
            r.entity_type == "PERSON" and (
                text[r.start:r.end].upper() in _PERSON_ALLOWLIST
                or r.score < _MIN_PERSON_SCORE
            )
        )
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
