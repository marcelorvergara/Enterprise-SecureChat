import openpyxl


def parse_excel(file_path: str) -> list[dict]:
    """Parse every sheet in a workbook into row-level text chunks.

    Column headers from row 1 are prepended to each data row so that chunks
    retain semantic context — e.g. "Department: Finance | Q3_Revenue: 1200000".
    Rows where every cell is empty are skipped.
    """
    wb = openpyxl.load_workbook(file_path, read_only=True, data_only=True)
    chunks = []
    for sheet_name in wb.sheetnames:
        ws = wb[sheet_name]
        rows = list(ws.iter_rows(values_only=True))
        if len(rows) < 2:
            continue

        headers = [str(h).strip() if h is not None else f"col_{i}"
                   for i, h in enumerate(rows[0])]

        for row in rows[1:]:
            cells = [str(v).strip() if v is not None else "" for v in row]
            if not any(cells):
                continue
            # "Header: value" pairs joined by pipe — preserves column semantics
            text = " | ".join(
                f"{h}: {v}" for h, v in zip(headers, cells) if v
            )
            if text:
                chunks.append({
                    "text": text,
                    "page_number": None,
                    "sheet_name": sheet_name,
                })
    wb.close()
    return chunks
