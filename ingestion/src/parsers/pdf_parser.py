import fitz  # PyMuPDF


def parse_pdf(file_path: str) -> list[dict]:
    """Extract text from each page of a PDF.

    Returns a list of page dicts: {text, page_number}.
    Empty pages are skipped.
    """
    doc = fitz.open(file_path)
    pages = []
    for page_num, page in enumerate(doc, start=1):
        text = page.get_text("text").strip()
        if text:
            pages.append({"text": text, "page_number": page_num, "sheet_name": None})
    doc.close()
    return pages
