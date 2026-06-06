import fitz  # PyMuPDF
from PIL import Image
import pytesseract

# Match the language setting used by image_parser so scanned PDF pages get the
# same Portuguese + English treatment.
_TESSERACT_LANG = "por+eng"
# Resolution for rendering scanned pages before OCR. 2× (144 dpi) gives
# Tesseract enough detail without ballooning memory on long documents.
_OCR_MATRIX = fitz.Matrix(2, 2)


def _ocr_page(page: fitz.Page) -> str:
    """Render a page to a bitmap and run Tesseract OCR on it."""
    pix = page.get_pixmap(matrix=_OCR_MATRIX)
    img = Image.frombytes("RGB", [pix.width, pix.height], pix.samples)
    return pytesseract.image_to_string(img, lang=_TESSERACT_LANG).strip()


def parse_pdf(file_path: str) -> list[dict]:
    """Extract text from each page of a PDF.

    For pages with embedded text (digital PDFs) PyMuPDF's native extractor is
    used. For pages that return no text (scanned / image-only PDFs) the page is
    rendered at 2× resolution and passed through Tesseract OCR with Portuguese
    and English language packs. Empty pages are skipped either way.

    Returns a list of page dicts: {text, page_number}.
    """
    doc = fitz.open(file_path)
    pages = []
    for page_num, page in enumerate(doc, start=1):
        text = page.get_text("text").strip()
        if not text:
            text = _ocr_page(page)
        if text:
            pages.append({"text": text, "page_number": page_num, "sheet_name": None})
    doc.close()
    return pages
