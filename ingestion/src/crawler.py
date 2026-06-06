"""
ANP E&P regulatory crawler.

Discovers PDF/XLSX links on the ANP Exploração e Produção portal, downloads new or
changed files, and indexes them via the /ingest API endpoint.

Run (dev):
    INGEST_URL=http://localhost:8001/ingest python -m src.crawler

Run (Docker):
    docker compose run --rm ingestion python -m src.crawler
"""

import hashlib
import json
import os
import time
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

ANP_URL = "https://www.gov.br/anp/pt-br/assuntos/exploracao-e-producao-de-oleo-e-gas"
INGEST_URL = os.getenv("INGEST_URL", "http://ingestion:8001/ingest")
STATE_FILE = Path(__file__).parent.parent / "data" / ".crawler_state.json"
MAX_BYTES = 50 * 1_024 * 1_024  # 50 MB
DOWNLOAD_TIMEOUT = 30
RATE_LIMIT_SECONDS = 3
HEADERS = {"User-Agent": "Enterprise-SecureChat-Crawler/1.0"}
SUPPORTED_EXTENSIONS = {".pdf", ".xlsx", ".xls"}

# URL keywords that route to the restricted BAR/reserves subject path
_BAR_KEYWORDS = ("reserva", "recursos", "bar")


def subject_path_for(url: str) -> str:
    lower = url.lower()
    if any(k in lower for k in _BAR_KEYWORDS):
        return "bar-questions"
    return "corporate-answers"


def _load_state() -> dict:
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            pass
    return {}


def _save_state(state: dict) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2), encoding="utf-8")


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _fetch_soup(url: str) -> BeautifulSoup | None:
    """GET a page and return a BeautifulSoup, or None on error."""
    try:
        resp = requests.get(url, headers=HEADERS, timeout=DOWNLOAD_TIMEOUT)
        resp.raise_for_status()
        return BeautifulSoup(resp.text, "lxml")
    except requests.RequestException as exc:
        print(f"[crawler] ERROR fetching {url}: {exc}")
        return None


def discover_subpages(root_url: str) -> list[str]:
    """Return subpage URLs that are one level deeper than root_url.

    Only follows links whose path starts with root_url's path, so the crawler
    stays inside the ANP E&P section and never drifts to unrelated pages.
    """
    soup = _fetch_soup(root_url)
    if soup is None:
        return []

    root_path = urlparse(root_url).path.rstrip("/")
    subpages: list[str] = []
    seen: set[str] = set()

    for tag in soup.find_all("a", href=True):
        absolute = urljoin(root_url, tag["href"].strip())
        parsed = urlparse(absolute)
        # Must be same host, deeper than root, and contain no file extension
        if (
            parsed.netloc == urlparse(root_url).netloc
            and parsed.path.rstrip("/").startswith(root_path + "/")
            and not Path(parsed.path).suffix
            and absolute not in seen
            and absolute != root_url
        ):
            seen.add(absolute)
            subpages.append(absolute)

    print(f"[crawler] Found {len(subpages)} subpage(s) under {root_url}")
    return subpages


def discover_links(page_url: str) -> list[tuple[str, str]]:
    """Return list of (absolute_url, filename) for supported file links on the page."""
    soup = _fetch_soup(page_url)
    if soup is None:
        return []

    results: list[tuple[str, str]] = []
    seen: set[str] = set()

    for tag in soup.find_all("a", href=True):
        href: str = tag["href"].strip()
        ext = Path(urlparse(href).path).suffix.lower()
        if ext not in SUPPORTED_EXTENSIONS:
            continue
        absolute = urljoin(page_url, href)
        filename = Path(urlparse(absolute).path).name
        if not filename or absolute in seen:
            continue
        seen.add(absolute)
        results.append((absolute, filename))

    if results:
        print(f"[crawler] Discovered {len(results)} document link(s) on {page_url}")
    return results


def download(url: str) -> bytes | None:
    """Download a file, enforcing the size cap. Returns None on error."""
    try:
        resp = requests.get(url, headers=HEADERS, timeout=DOWNLOAD_TIMEOUT, stream=True)
        resp.raise_for_status()
    except requests.RequestException as exc:
        print(f"[crawler] SKIP  download error for {url}: {exc}")
        return None

    chunks: list[bytes] = []
    total = 0
    for chunk in resp.iter_content(chunk_size=65_536):
        total += len(chunk)
        if total > MAX_BYTES:
            print(f"[crawler] SKIP  {url} exceeds {MAX_BYTES // 1_048_576} MB limit")
            return None
        chunks.append(chunk)
    return b"".join(chunks)


def post_to_ingest(filename: str, content: bytes, bu_path: str) -> bool:
    """POST file bytes to the /ingest endpoint. Returns True on success."""
    ext = Path(filename).suffix.lower()
    mime = "application/pdf" if ext == ".pdf" else "application/octet-stream"
    try:
        resp = requests.post(
            INGEST_URL,
            files={"file": (filename, content, mime)},
            data={"bu_path": bu_path},
            timeout=300,  # scanned PDFs need Tesseract OCR per page — allow 5 min
        )
        resp.raise_for_status()
        result = resp.json()
        print(
            f"[crawler] OK    {filename} → {bu_path} "
            f"({result.get('chunks', '?')} chunks)"
        )
        return True
    except requests.RequestException as exc:
        print(f"[crawler] ERROR ingesting {filename}: {exc}")
        return False


def collect_pages(root_url: str, max_depth: int = 2) -> list[str]:
    """BFS from root_url up to max_depth levels, staying inside the ANP E&P section."""
    visited: set[str] = {root_url}
    queue: list[tuple[str, int]] = [(root_url, 0)]
    ordered: list[str] = [root_url]

    while queue:
        page_url, depth = queue.pop(0)
        if depth >= max_depth:
            continue
        for subpage in discover_subpages(page_url):
            if subpage not in visited:
                visited.add(subpage)
                ordered.append(subpage)
                queue.append((subpage, depth + 1))
        time.sleep(RATE_LIMIT_SECONDS)

    return ordered


def run() -> None:
    print(f"[crawler] Starting ANP crawl — target: {ANP_URL}")
    state = _load_state()

    pages_to_crawl = collect_pages(ANP_URL, max_depth=2)
    print(f"[crawler] Crawling {len(pages_to_crawl)} page(s) total (depth ≤ 2)")

    # Deduplicate file URLs across all pages so the same file linked on
    # multiple pages is only downloaded once.
    seen_urls: set[str] = set()
    all_links: list[tuple[str, str]] = []
    for page_url in pages_to_crawl:
        for url, filename in discover_links(page_url):
            if url not in seen_urls:
                seen_urls.add(url)
                all_links.append((url, filename))
        time.sleep(RATE_LIMIT_SECONDS)

    print(f"[crawler] {len(all_links)} unique document(s) found across all pages")
    new_count = updated_count = skipped_count = 0

    for url, filename in all_links:
        content = download(url)
        if content is None:
            skipped_count += 1
            time.sleep(RATE_LIMIT_SECONDS)
            continue

        sha = _sha256(content)
        if state.get(filename) == sha:
            print(f"[crawler] SKIP  {filename} unchanged")
            skipped_count += 1
            time.sleep(RATE_LIMIT_SECONDS)
            continue

        is_new = filename not in state
        bu_path = subject_path_for(url)

        if post_to_ingest(filename, content, bu_path):
            if is_new:
                new_count += 1
            else:
                updated_count += 1
            state[filename] = sha

        time.sleep(RATE_LIMIT_SECONDS)

    _save_state(state)
    print(
        f"[crawler] Done — {new_count} new, {updated_count} updated, "
        f"{skipped_count} skipped. State saved to {STATE_FILE}"
    )


if __name__ == "__main__":
    run()
