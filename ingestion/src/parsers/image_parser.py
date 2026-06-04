from PIL import Image
import pytesseract


def parse_image(file_path: str) -> list[dict]:
    """OCR an image and return its full text as a single chunk.

    Returns an empty list if the image produces no recognisable text.
    """
    img = Image.open(file_path)
    text = pytesseract.image_to_string(img).strip()
    if not text:
        return []
    return [{"text": text, "page_number": None, "sheet_name": None}]
