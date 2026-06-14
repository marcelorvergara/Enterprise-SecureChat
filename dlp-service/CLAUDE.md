# DLP Service — Python 3.11 / FastAPI / Microsoft Presidio

## Dev Commands

```bash
pip install -r requirements.txt
python -m spacy download pt_core_news_lg

uvicorn src.main:app --host 0.0.0.0 --port 8000

pytest tests/
```

## Source Layout

```
src/
├── main.py         FastAPI: POST /dlp/analyze, GET /health
├── analyzer.py     Presidio AnalyzerEngine + AnonymizerEngine (module-level singletons,
│                   loaded once at startup to avoid per-request model load)
└── custom_recognizers/
    ├── financial_figures.py   FINANCIAL_FIGURE entity
    └── og_rules.py            OG_VOLUMES, ANP_PROCESS, RESERVES_VARIATION,
                               INVESTMENT_YEAR, OG_CONTRACT, COMMODITY_PRICE
```

## Active Entity Types

| Entity | Source | Covers |
|--------|--------|--------|
| `PERSON` | SpacyRecognizer (`pt_core_news_lg`) | Personal names — always redacted for every role |
| `EMAIL_ADDRESS` | EmailRecognizer (built-in) | Email addresses |
| `PHONE_NUMBER` | PhoneRecognizer (built-in) | Phone numbers |
| `CREDIT_CARD` | CreditCardRecognizer (built-in) | Card numbers |
| `DATE_TIME` | SpacyRecognizer (PT NER) | Document dates, year ranges |
| `FINANCIAL_FIGURE` | `financial_figures.py` | Currency symbols, currency-code amounts, magnitude phrases, bare comma-grouped numbers (score 0.75) |
| `OG_VOLUMES` | `og_rules.py` | Reserve/production volumes: boe, MMboe, Mboe, bbl, bbl/d, m³/d |
| `ANP_PROCESS` | `og_rules.py` | Ofício Nº, Carta Nº, Processo SEI, bare SEI number |
| `RESERVES_VARIATION` | `og_rules.py` | Signed % + `variação/variações`; `fator de recuperação / recovery factor` |
| `INVESTMENT_YEAR` | `og_rules.py` | Year after investment keywords; year ranges near context words |
| `OG_CONTRACT` | `og_rules.py` | Contract end date/year (`prazo do contrato`, `término`); Cessão Onerosa percentages |
| `COMMODITY_PRICE` | `og_rules.py` | `/bbl` and `/MMBtu` prices; labeled barrel/gas prices |

## Critical Constraints

- NLP model is **`pt_core_news_lg` (Portuguese)**, not `en_core_web_lg`. Brazilian basin/field names (Pré-Sal, Campos, Santos) are classified as LOC/GPE. The English model misclassifies them as PERSON, producing false positives that redact core O&G terminology.
- `OG_VOLUMES` **must remain in `_DEFAULT_ENTITIES`** in `analyzer.py`. It was previously accidentally omitted — removing it silently stops redacting reserve figures.
- Industry acronyms (FPSO, LNG, PETROBRAS, etc.) are in `_PERSON_ALLOWLIST` to prevent false PERSON classifications.
- The DLP service has **no public port** — it is on the `internal` Docker network only (`docker-compose.yml` has no `ports:` mapping). The backend calls `http://dlp-service:8000`. The Angular app never calls it directly.
- Each AI-suggested follow-up (M16) is independently DLP-scanned before reaching the client — suggestions never bypass the pipeline.
- Presidio requires **complete sentence context** to detect entity boundaries. This is why `/api/chat` is blocking (non-streaming) — see constraint #2 in root CLAUDE.md.

## `/dlp/analyze` Contract

```json
// Request
{ "text": "John earns R$125.000 per year.", "language": "pt" }

// Response
{ "cleaned_text": "[REDACTED] earns [REDACTED] per year.", "entities_found": [...] }
```

Anonymization uses replacement with `[REDACTED]` (not fake values). The `entities_found` list drives the `dlp_entities_redacted` count returned to the Angular client.
