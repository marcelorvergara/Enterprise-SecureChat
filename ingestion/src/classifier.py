"""Document classification metadata extractor.

Reads embedded sensitivity labels from document properties and maps them to
the three-tier hierarchy used by the FGA clearance filter:

    Public (0) < Internal (1) < Confidential (2)

When no recognisable label is found the module defaults to "Internal" — the
safest non-restrictive fallback that avoids over-exposure without hiding
legitimately public content behind a Confidential barrier.
"""

from pathlib import Path

# Ordered highest-to-lowest so the first match wins when multiple labels
# are embedded in the same property value (e.g. "Confidential / Internal").
_LABEL_ORDER = ["confidential", "internal", "public"]

_LABEL_MAP: dict[str, str] = {
    "confidential": "Confidential",
    "internal":     "Internal",
    "public":       "Public",
}

_DEFAULT = "Internal"


def _scan_fields(*values: str | None) -> str:
    """Scan arbitrary metadata field values for the highest sensitivity label.

    Uses casefold() for locale-neutral case normalisation so variants like
    "CONFIDENTIAL", "Confidential", and "confidential" are all matched.
    """
    combined = " ".join(v for v in values if v).casefold()
    for label in _LABEL_ORDER:
        if label in combined:
            return _LABEL_MAP[label]
    return _DEFAULT


def _classify_pdf(file_path: str) -> str:
    import fitz  # PyMuPDF — already a dependency
    doc = fitz.open(file_path)
    meta = doc.metadata or {}
    doc.close()
    return _scan_fields(
        meta.get("keywords"),
        meta.get("subject"),
        meta.get("title"),
        meta.get("producer"),
        meta.get("creator"),
    )


def _classify_xlsx(file_path: str) -> str:
    import openpyxl  # already a dependency
    wb = openpyxl.load_workbook(file_path, read_only=True, data_only=True)
    props = wb.properties
    candidates = [
        props.keywords,
        props.subject,
        props.title,
        props.category,
        getattr(props, "description", None),
    ]
    # openpyxl 3.1+ exposes custom document properties
    custom = getattr(wb, "custom_document_properties", None)
    if custom:
        for prop in custom:
            name = getattr(prop, "name", "").casefold()
            if "classif" in name or "sensitiv" in name:
                candidates.append(str(getattr(prop, "value", "")))
    wb.close()
    return _scan_fields(*candidates)


_CLASSIFIER_DISPATCH: dict[str, object] = {
    ".pdf":  _classify_pdf,
    ".xlsx": _classify_xlsx,
    # xlrd exposes no document properties — safe default
    ".xls":  lambda _: _DEFAULT,
}


def extract_classification(file_path: str) -> str:
    """Return the sensitivity classification label embedded in *file_path*.

    Dispatches to a format-specific extractor based on file extension.
    Falls back to ``"Internal"`` for formats with no embeddable metadata
    (images, plain text, CSV, .xls) or when the document carries no label.
    """
    ext = Path(file_path).suffix.lower()
    fn = _CLASSIFIER_DISPATCH.get(ext)
    if fn is None:
        return _DEFAULT
    return fn(file_path)  # type: ignore[operator]


