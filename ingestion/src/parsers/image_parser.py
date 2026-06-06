from PIL import Image
import pytesseract

# Portuguese + English so that mixed-language ANP regulatory docs are handled
# correctly. The 'por' pack must be installed in the Docker image.
_TESSERACT_LANG = "por+eng"


def parse_image(file_path: str) -> list[dict]:
    """OCR an image and return its full text as a single chunk.

    Returns an empty list if the image produces no recognisable text.
    """
    img = Image.open(file_path)
    text = pytesseract.image_to_string(img, lang=_TESSERACT_LANG).strip()
    if not text:
        return []
    return [{"text": text, "page_number": None, "sheet_name": None}]
