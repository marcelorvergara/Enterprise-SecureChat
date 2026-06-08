"""
ANP E&P regulatory crawler.

Discovers PDF/XLSX links and/or HTML page text on the ANP Exploração e Produção
portal, downloads new or changed content, and indexes it via the /ingest API.

Run (dev):
    INGEST_URL=http://localhost:8001/ingest python -m src.crawler
    INGEST_URL=http://localhost:8001/ingest python -m src.crawler --mode html
    INGEST_URL=http://localhost:8001/ingest python -m src.crawler --mode all

Run (Docker):
    docker compose run --rm ingestion python -m src.crawler --mode html
"""

import argparse
import hashlib
import json
import os
import re
import time
import urllib.request
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup, NavigableString

ANP_URL = "https://www.gov.br/anp/pt-br/assuntos/exploracao-e-producao-de-oleo-e-gas"
_ANP_BASE_PATH = urlparse(ANP_URL).path.rstrip("/")
INGEST_URL = os.getenv("INGEST_URL", "http://ingestion:8001/ingest")
STATE_FILE = Path(__file__).parent.parent / "data" / ".crawler_state.json"

# When set, state is persisted in GCS instead of the local filesystem.
# Required on Cloud Run Jobs (ephemeral containers discard local files on exit).
# The Cloud Run service account needs roles/storage.objectAdmin on this bucket.
GCS_STATE_BUCKET = os.getenv("GCS_STATE_BUCKET")
GCS_STATE_KEY = os.getenv("GCS_STATE_KEY", ".crawler_state.json")
MAX_BYTES = 50 * 1_024 * 1_024  # 50 MB
DOWNLOAD_TIMEOUT = 30
RATE_LIMIT_SECONDS = 3
HEADERS = {"User-Agent": "Enterprise-SecureChat-Crawler/1.0"}
SUPPORTED_EXTENSIONS = {".pdf", ".xlsx", ".xls"}

# URL keywords that route to the restricted BAR/reserves subject path
_BAR_KEYWORDS = ("reserva", "recursos", "bar")

# Audience for OIDC token — the ingestion service base URL (no path).
_INGEST_AUDIENCE = "/".join(INGEST_URL.split("/")[:3])

# ── HTML extraction constants ──────────────────────────────────────────────────

# Breadcrumb items to strip — generic gov.br navigation levels with no domain value
_BREADCRUMB_SKIP = {"página inicial", "assuntos", "você está aqui", ""}

# Tags always stripped from the content element before text extraction
_BOILERPLATE_TAGS = ["nav", "header", "footer", "script", "style"]

# CSS class pattern covering share buttons, social links, and site navigation portlets
_BOILERPLATE_CLASSES = re.compile(
    r"portlet-navigation|portletNavigationTree|portal-footer|portal-header"
    r"|share|social|compartilh|documentActions",
    re.I,
)

# Minimum characters in the extracted body text (excluding breadcrumb and h1)
# below which the page is considered a thin pointer and skipped.
MIN_BODY_CHARS = 80


# ── Auth / identity ────────────────────────────────────────────────────────────

def _fetch_identity_token() -> str | None:
    """Fetch a short-lived OIDC token from the GCP metadata server.

    Only reachable on Cloud Run / GCE. Returns None in local dev so plain
    unauthenticated HTTP is used instead (Docker Compose still works).
    """
    url = (
        "http://metadata.google.internal/computeMetadata/v1/instance"
        f"/service-accounts/default/identity?audience={_INGEST_AUDIENCE}"
    )
    try:
        req = urllib.request.Request(url, headers={"Metadata-Flavor": "Google"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.read().decode()
    except Exception:
        return None


# ── Routing ────────────────────────────────────────────────────────────────────

def subject_path_for(url: str) -> str:
    """Route a file URL to its subject_path based on URL keywords."""
    lower = url.lower()
    if any(k in lower for k in _BAR_KEYWORDS):
        return "bar-questions"
    return "corporate-answers"


# ── State persistence ──────────────────────────────────────────────────────────

def _state_location() -> str:
    if GCS_STATE_BUCKET:
        return f"gs://{GCS_STATE_BUCKET}/{GCS_STATE_KEY}"
    return str(STATE_FILE)


def _load_state() -> dict:
    if GCS_STATE_BUCKET:
        from google.cloud import storage  # noqa: PLC0415
        try:
            blob = storage.Client().bucket(GCS_STATE_BUCKET).blob(GCS_STATE_KEY)
            if blob.exists():
                return json.loads(blob.download_as_text(encoding="utf-8"))
        except Exception as exc:
            print(f"[crawler] WARN  could not load state from GCS: {exc}")
        return {}

    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            pass
    return {}


def _save_state(state: dict) -> None:
    if GCS_STATE_BUCKET:
        from google.cloud import storage  # noqa: PLC0415
        try:
            blob = storage.Client().bucket(GCS_STATE_BUCKET).blob(GCS_STATE_KEY)
            blob.upload_from_string(
                json.dumps(state, indent=2),
                content_type="application/json",
            )
        except Exception as exc:
            print(f"[crawler] ERROR could not save state to GCS: {exc}")
        return

    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2), encoding="utf-8")


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


# ── HTTP helpers ───────────────────────────────────────────────────────────────

def _fetch_soup(url: str) -> BeautifulSoup | None:
    """GET a page and return a BeautifulSoup, or None on error."""
    try:
        resp = requests.get(url, headers=HEADERS, timeout=DOWNLOAD_TIMEOUT)
        resp.raise_for_status()
        return BeautifulSoup(resp.content, "lxml")
    except requests.RequestException as exc:
        print(f"[crawler] ERROR fetching {url}: {exc}")
        return None
    except Exception as exc:
        print(f"[crawler] ERROR parsing {url}: {type(exc).__name__}: {exc}")
        return None


# ── Link / page discovery ──────────────────────────────────────────────────────

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


# ── File download / ingest ─────────────────────────────────────────────────────

def download(url: str) -> bytes | None:
    """Download a file, enforcing the size cap. Returns None on error."""
    try:
        resp = requests.get(url, headers=HEADERS, timeout=DOWNLOAD_TIMEOUT, stream=True)
        resp.raise_for_status()
        chunks: list[bytes] = []
        total = 0
        for chunk in resp.iter_content(chunk_size=65_536):
            total += len(chunk)
            if total > MAX_BYTES:
                print(f"[crawler] SKIP  {url} exceeds {MAX_BYTES // 1_048_576} MB limit")
                return None
            chunks.append(chunk)
        return b"".join(chunks)
    except requests.RequestException as exc:
        print(f"[crawler] SKIP  download error for {url}: {exc}")
        return None


def post_to_ingest(filename: str, content: bytes, bu_path: str) -> bool:
    """POST file bytes to the /ingest endpoint. Returns True on success."""
    ext = Path(filename).suffix.lower()
    if ext == ".pdf":
        mime = "application/pdf"
    elif ext == ".txt":
        mime = "text/plain; charset=utf-8"
    else:
        mime = "application/octet-stream"
    token = _fetch_identity_token()
    auth_headers = {"Authorization": f"Bearer {token}"} if token else {}
    try:
        resp = requests.post(
            INGEST_URL,
            files={"file": (filename, content, mime)},
            data={"bu_path": bu_path},
            headers=auth_headers,
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


# ── HTML text extraction ───────────────────────────────────────────────────────

def url_to_slug(url: str) -> str:
    """Convert an ANP page URL to a short filesystem-safe slug.

    Strips the common ANP base path so depth-1 and depth-2 pages produce
    compact, readable names: 'dados-de-e-p', 'seguranca-operacional_auditorias'.
    """
    path = urlparse(url).path.rstrip("/")
    suffix = (
        path[len(_ANP_BASE_PATH):].lstrip("/")
        if path.startswith(_ANP_BASE_PATH)
        else path.lstrip("/")
    )
    slug = re.sub(r"[^a-z0-9\-]", "_", suffix.replace("/", "_").lower())
    return slug.strip("_") or "root"


def extract_breadcrumb(soup: BeautifulSoup) -> str:
    """Return the page breadcrumb as 'Categoria: X > Y > Z', or '' if not found.

    Strips the generic gov.br prefix ('Página Inicial > Assuntos') so only the
    domain-relevant hierarchy levels are kept. The result is prepended to each
    page's extracted text so every Qdrant chunk carries its site-hierarchy context.
    """
    el = soup.find(id="portal-breadcrumbs") or soup.find(class_="breadcrumbs")
    if not el:
        return ""

    # Clickable breadcrumb levels are all inside <a> tags
    items = [a.get_text(strip=True) for a in el.find_all("a")]

    # The current page (last level) is plain text — not wrapped in a link
    for node in reversed(list(el.descendants)):
        if isinstance(node, NavigableString):
            text = str(node).strip(" >\n\t")
            if text and text.lower() not in _BREADCRUMB_SKIP and text not in items:
                items.append(text)
                break

    parts = [i for i in items if i.lower() not in _BREADCRUMB_SKIP]
    return ("Categoria: " + " > ".join(parts)) if parts else ""


def extract_page_text(soup: BeautifulSoup) -> str | None:
    """Extract editorial text from a parsed page.

    Returns a string in the form:
        Categoria: Exploração e Produção > Segurança Operacional > Auditorias
        Fiscalização da Segurança Operacional - Auditorias

        <body text...>

    Returns None when the page body is below MIN_BODY_CHARS (thin pointer pages
    or JS-rendered accordions with no static text content).
    """
    breadcrumb = extract_breadcrumb(soup)

    h1_el = soup.find(class_="documentFirstHeading") or soup.find("h1")
    h1_text = h1_el.get_text(strip=True) if h1_el else ""

    # Try Plone-specific selectors before falling back to generic semantic elements
    content_el = (
        soup.find(id="content-core")
        or soup.find(class_="documentContent")
        or soup.find(id="region-content")
        or soup.find("main")
        or soup.find(id="wrapper")
    )
    if content_el is None:
        return None

    # Strip boilerplate in-place before text extraction
    for tag in content_el.find_all(_BOILERPLATE_TAGS):
        tag.decompose()
    for el in content_el.find_all(class_=_BOILERPLATE_CLASSES):
        el.decompose()

    body = "\n".join(
        line
        for line in content_el.get_text(separator="\n", strip=True).splitlines()
        if line.strip()
    )

    if len(body) < MIN_BODY_CHARS:
        return None

    return "\n".join(filter(None, [breadcrumb, h1_text, "", body]))


# ── Main crawl loop ────────────────────────────────────────────────────────────

def run(mode: str = "files") -> None:
    try:
        _run(mode)
    except KeyboardInterrupt:
        print("[crawler] Interrupted — state was saved up to last completed file")
    except Exception as exc:
        import traceback
        print(f"[crawler] FATAL {type(exc).__name__}: {exc}")
        traceback.print_exc()


def _run(mode: str = "files") -> None:
    print(f"[crawler] Starting ANP crawl — target: {ANP_URL} — mode: {mode}")
    state = _load_state()
    new_count = updated_count = skipped_count = 0

    try:
        pages_to_crawl = collect_pages(ANP_URL, max_depth=2)
        print(f"[crawler] Crawling {len(pages_to_crawl)} page(s) total (depth ≤ 2)")

        # ── HTML pass ─────────────────────────────────────────────────────────
        if mode in ("html", "all"):
            print(f"[crawler] HTML pass — {len(pages_to_crawl)} page(s)")
            for page_url in pages_to_crawl:
                slug = url_to_slug(page_url)
                state_key = f"html::{slug}"

                soup = _fetch_soup(page_url)
                if soup is None:
                    skipped_count += 1
                    time.sleep(RATE_LIMIT_SECONDS)
                    continue

                text = extract_page_text(soup)
                if text is None:
                    print(f"[crawler] SKIP  {page_url} — no extractable content")
                    skipped_count += 1
                    time.sleep(RATE_LIMIT_SECONDS)
                    continue

                sha = _sha256(text.encode())
                if state.get(state_key) == sha:
                    print(f"[crawler] SKIP  {slug}.txt unchanged")
                    skipped_count += 1
                    time.sleep(RATE_LIMIT_SECONDS)
                    continue

                is_new = state_key not in state
                filename = f"{slug}.txt"

                if post_to_ingest(filename, text.encode(), "corporate-answers"):
                    new_count += 1 if is_new else 0
                    updated_count += 0 if is_new else 1
                    state[state_key] = sha
                    _save_state(state)

                time.sleep(RATE_LIMIT_SECONDS)

        # ── Files pass ────────────────────────────────────────────────────────
        if mode in ("files", "all"):
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
                    new_count += 1 if is_new else 0
                    updated_count += 0 if is_new else 1
                    state[filename] = sha
                    _save_state(state)

                time.sleep(RATE_LIMIT_SECONDS)

    finally:
        _save_state(state)
        print(
            f"[crawler] Done — {new_count} new, {updated_count} updated, "
            f"{skipped_count} skipped. State saved to {_state_location()}"
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ANP E&P regulatory crawler")
    parser.add_argument(
        "--mode",
        choices=["files", "html", "all"],
        default="files",
        help=(
            "files: download PDFs/XLSX only (default, backwards-compatible); "
            "html: extract and index HTML page text only; "
            "all: both HTML pages and file downloads"
        ),
    )
    args = parser.parse_args()
    run(args.mode)
