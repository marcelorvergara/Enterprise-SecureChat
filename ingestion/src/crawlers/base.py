"""
Base crawler framework for Enterprise-SecureChat regulatory ingestion.

BaseCrawler provides shared HTTP, state persistence, and /ingest posting.
Concrete subclasses implement site-specific routing and HTML extraction by
overriding three abstract methods: extract_page_text, subject_path_for,
classification_for.

PloneMixin provides Plone CMS HTML extraction for gov.br portals (ANP, MME).
Both ANPCrawler and MMECrawler inherit it; future non-Plone crawlers override
extract_page_text directly instead.
"""

import hashlib
import json
import os
import re
import time
import urllib.request
from abc import ABC, abstractmethod
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup, NavigableString


MAX_BYTES = 50 * 1_024 * 1_024  # 50 MB
DOWNLOAD_TIMEOUT = 30
RATE_LIMIT_SECONDS = 3
HEAD_RATE_LIMIT_SECONDS = 0.5
SUPPORTED_EXTENSIONS = {".pdf", ".xlsx", ".xls"}

_BREADCRUMB_SKIP = {"página inicial", "assuntos", "você está aqui", ""}
_BOILERPLATE_TAGS = ["nav", "header", "footer", "script", "style"]
_BOILERPLATE_CLASSES = re.compile(
    r"portlet-navigation|portletNavigationTree|portal-footer|portal-header"
    r"|share|social|compartilh|documentActions",
    re.I,
)
MIN_BODY_CHARS = 80


class PloneMixin:
    """Plone CMS HTML extraction for gov.br portals.

    Provides extract_page_text, extract_breadcrumb, and url_to_slug.
    Compatible with any BaseCrawler subclass — ROOT_URL is resolved at
    runtime from the concrete class.
    """

    ROOT_URL: str  # declared on BaseCrawler; resolved at runtime

    def url_to_slug(self, url: str) -> str:
        root_path = urlparse(self.ROOT_URL).path.rstrip("/")
        path = urlparse(url).path.rstrip("/")
        suffix = (
            path[len(root_path):].lstrip("/")
            if path.startswith(root_path)
            else path.lstrip("/")
        )
        slug = re.sub(r"[^a-z0-9\-]", "_", suffix.replace("/", "_").lower())
        return slug.strip("_") or "root"

    def extract_breadcrumb(self, soup: BeautifulSoup) -> str:
        el = soup.find(id="portal-breadcrumbs") or soup.find(class_="breadcrumbs")
        if not el:
            return ""
        items = [a.get_text(strip=True) for a in el.find_all("a")]
        for node in reversed(list(el.descendants)):
            if isinstance(node, NavigableString):
                text = str(node).strip(" >\n\t")
                if text and text.lower() not in _BREADCRUMB_SKIP and text not in items:
                    items.append(text)
                    break
        parts = [i for i in items if i.lower() not in _BREADCRUMB_SKIP]
        return ("Categoria: " + " > ".join(parts)) if parts else ""

    def extract_page_text(self, soup: BeautifulSoup) -> str | None:
        breadcrumb = self.extract_breadcrumb(soup)
        h1_el = soup.find(class_="documentFirstHeading") or soup.find("h1")
        h1_text = h1_el.get_text(strip=True) if h1_el else ""
        content_el = (
            soup.find(id="content-core")
            or soup.find(class_="documentContent")
            or soup.find(id="region-content")
            or soup.find("main")
            or soup.find(id="wrapper")
        )
        if content_el is None:
            return None
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


class BaseCrawler(ABC):
    """Abstract base for all regulatory source crawlers.

    Subclasses must declare ROOT_URL, ORIGIN_SOURCE, JURISDICTION as class
    attributes and implement extract_page_text, subject_path_for, and
    classification_for. The shared BFS page/link discovery and run loop
    are provided here and work for any standard hyperlink-based site.
    """

    ROOT_URL: str
    ORIGIN_SOURCE: str
    JURISDICTION: str
    SUPPORTED_EXTENSIONS: set[str] = SUPPORTED_EXTENSIONS

    def __init__(self) -> None:
        self.ingest_url = os.getenv("INGEST_URL", "http://ingestion:8001/ingest")
        self.gcs_bucket = os.getenv("GCS_STATE_BUCKET")
        self.gcs_key = os.getenv(
            "GCS_STATE_KEY", f".crawler_state_{self.ORIGIN_SOURCE}.json"
        )
        self.state_file = (
            Path(__file__).parent.parent.parent / "data"
            / f".crawler_state_{self.ORIGIN_SOURCE}.json"
        )
        self.headers = {"User-Agent": "Enterprise-SecureChat-Crawler/1.0"}
        self._ingest_audience = "/".join(self.ingest_url.split("/")[:3])

    # ── Auth ────────────────────────────────────────────────────────────────

    def _fetch_identity_token(self) -> str | None:
        """Fetch a short-lived OIDC token from the GCP metadata server.

        Only reachable on Cloud Run / GCE. Returns None in local dev so plain
        unauthenticated HTTP is used instead (Docker Compose still works).
        """
        url = (
            "http://metadata.google.internal/computeMetadata/v1/instance"
            f"/service-accounts/default/identity?audience={self._ingest_audience}"
        )
        try:
            req = urllib.request.Request(url, headers={"Metadata-Flavor": "Google"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.read().decode()
        except Exception:
            return None

    # ── State persistence ────────────────────────────────────────────────────

    def _state_location(self) -> str:
        if self.gcs_bucket:
            return f"gs://{self.gcs_bucket}/{self.gcs_key}"
        return str(self.state_file)

    def _load_state(self) -> dict:
        if self.gcs_bucket:
            from google.cloud import storage  # noqa: PLC0415
            try:
                blob = storage.Client().bucket(self.gcs_bucket).blob(self.gcs_key)
                if blob.exists():
                    return json.loads(blob.download_as_text(encoding="utf-8"))
            except Exception as exc:
                print(f"[{self.ORIGIN_SOURCE}] WARN  could not load state from GCS: {exc}")
            return {}
        if self.state_file.exists():
            try:
                return json.loads(self.state_file.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError):
                pass
        return {}

    def _save_state(self, state: dict) -> None:
        if self.gcs_bucket:
            from google.cloud import storage  # noqa: PLC0415
            try:
                blob = storage.Client().bucket(self.gcs_bucket).blob(self.gcs_key)
                blob.upload_from_string(
                    json.dumps(state, indent=2),
                    content_type="application/json",
                )
            except Exception as exc:
                print(f"[{self.ORIGIN_SOURCE}] ERROR could not save state to GCS: {exc}")
            return
        self.state_file.parent.mkdir(parents=True, exist_ok=True)
        self.state_file.write_text(json.dumps(state, indent=2), encoding="utf-8")

    @staticmethod
    def _sha256(content: bytes) -> str:
        return hashlib.sha256(content).hexdigest()

    # ── HTTP helpers ─────────────────────────────────────────────────────────

    def _fetch_soup(self, url: str) -> BeautifulSoup | None:
        try:
            resp = requests.get(url, headers=self.headers, timeout=DOWNLOAD_TIMEOUT)
            resp.raise_for_status()
            return BeautifulSoup(resp.content, "lxml")
        except requests.RequestException as exc:
            print(f"[{self.ORIGIN_SOURCE}] ERROR fetching {url}: {exc}")
            return None
        except Exception as exc:
            print(f"[{self.ORIGIN_SOURCE}] ERROR parsing {url}: {type(exc).__name__}: {exc}")
            return None

    def _head_info(self, url: str) -> tuple[int | None, str | None, str | None]:
        """Return (Content-Length, ETag, Last-Modified) from a HEAD request."""
        try:
            resp = requests.head(url, headers=self.headers, timeout=10, allow_redirects=True)
            resp.raise_for_status()
            cl = resp.headers.get("Content-Length")
            return int(cl) if cl else None, resp.headers.get("ETag"), resp.headers.get("Last-Modified")
        except Exception:
            return None, None, None

    @staticmethod
    def _file_state(state: dict, key: str) -> tuple[str | None, int | None, str | None, str | None]:
        """Return (sha, size, etag, last_modified) for a state entry.

        Handles both old format {key: sha_str} and new format
        {key: {"sha": ..., "size": N, "etag": ..., "last_modified": ...}}.
        """
        entry = state.get(key)
        if entry is None:
            return None, None, None, None
        if isinstance(entry, str):
            return entry, None, None, None
        return entry.get("sha"), entry.get("size"), entry.get("etag"), entry.get("last_modified")

    def download(self, url: str) -> tuple[bytes | None, str | None, str | None]:
        """Download a file, enforcing the size cap.

        Returns (content, etag, last_modified).
        """
        try:
            resp = requests.get(url, headers=self.headers, timeout=DOWNLOAD_TIMEOUT, stream=True)
            resp.raise_for_status()
            etag = resp.headers.get("ETag")
            last_mod = resp.headers.get("Last-Modified")
            chunks: list[bytes] = []
            total = 0
            for chunk in resp.iter_content(chunk_size=65_536):
                total += len(chunk)
                if total > MAX_BYTES:
                    print(f"[{self.ORIGIN_SOURCE}] SKIP  {url} exceeds {MAX_BYTES // 1_048_576} MB limit")
                    return None, None, None
                chunks.append(chunk)
            return b"".join(chunks), etag, last_mod
        except requests.RequestException as exc:
            print(f"[{self.ORIGIN_SOURCE}] SKIP  download error for {url}: {exc}")
            return None, None, None

    def post_to_ingest(
        self,
        filename: str,
        content: bytes,
        subject_path: str,
        classification_level: str = "Internal",
    ) -> bool:
        """POST file bytes to the /ingest endpoint. Returns True on success."""
        ext = Path(filename).suffix.lower()
        if ext == ".pdf":
            mime = "application/pdf"
        elif ext == ".txt":
            mime = "text/plain; charset=utf-8"
        else:
            mime = "application/octet-stream"
        token = self._fetch_identity_token()
        auth_headers = {"Authorization": f"Bearer {token}"} if token else {}
        try:
            resp = requests.post(
                self.ingest_url,
                files={"file": (filename, content, mime)},
                data={
                    "bu_path": subject_path,
                    "classification_level": classification_level,
                    "origin_source": self.ORIGIN_SOURCE,
                    "jurisdiction": self.JURISDICTION,
                },
                headers=auth_headers,
                timeout=300,
            )
            resp.raise_for_status()
            result = resp.json()
            print(
                f"[{self.ORIGIN_SOURCE}] OK    {filename} → {subject_path} "
                f"({result.get('chunks', '?')} chunks)"
            )
            return True
        except requests.RequestException as exc:
            print(f"[{self.ORIGIN_SOURCE}] ERROR ingesting {filename}: {exc}")
            return False

    # ── Page / link discovery (default BFS — override for non-standard sites) ──

    def url_to_slug(self, url: str) -> str:
        """Convert a page URL to a filesystem-safe slug for state tracking."""
        path = urlparse(url).path.rstrip("/")
        slug = re.sub(r"[^a-z0-9\-]", "_", path.replace("/", "_").lower())
        return slug.strip("_") or "root"

    def discover_subpages(self, root_url: str) -> list[str]:
        """Return subpage URLs one level deeper than root_url, staying within the site section."""
        soup = self._fetch_soup(root_url)
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
        print(f"[{self.ORIGIN_SOURCE}] Found {len(subpages)} subpage(s) under {root_url}")
        return subpages

    def discover_links(self, page_url: str) -> list[tuple[str, str]]:
        """Return (absolute_url, filename) pairs for supported file links on page_url."""
        soup = self._fetch_soup(page_url)
        if soup is None:
            return []
        results: list[tuple[str, str]] = []
        seen: set[str] = set()
        for tag in soup.find_all("a", href=True):
            href: str = tag["href"].strip()
            ext = Path(urlparse(href).path).suffix.lower()
            if ext not in self.SUPPORTED_EXTENSIONS:
                continue
            absolute = urljoin(page_url, href)
            filename = Path(urlparse(absolute).path).name
            if not filename or absolute in seen:
                continue
            seen.add(absolute)
            results.append((absolute, filename))
        if results:
            print(f"[{self.ORIGIN_SOURCE}] Discovered {len(results)} document link(s) on {page_url}")
        return results

    def collect_pages(self, max_depth: int) -> list[tuple[str, int]]:
        """BFS from ROOT_URL up to max_depth. Returns (url, depth) pairs."""
        visited: set[str] = {self.ROOT_URL}
        queue: list[tuple[str, int]] = [(self.ROOT_URL, 0)]
        ordered: list[tuple[str, int]] = [(self.ROOT_URL, 0)]
        while queue:
            page_url, depth = queue.pop(0)
            if depth >= max_depth:
                continue
            for subpage in self.discover_subpages(page_url):
                if subpage not in visited:
                    visited.add(subpage)
                    ordered.append((subpage, depth + 1))
                    queue.append((subpage, depth + 1))
            time.sleep(RATE_LIMIT_SECONDS)
        return ordered

    # ── Abstract interface ────────────────────────────────────────────────────

    @abstractmethod
    def extract_page_text(self, soup: BeautifulSoup) -> str | None:
        """Extract editorial text from a parsed page, or None for thin/empty pages."""
        ...

    @abstractmethod
    def subject_path_for(self, url: str) -> str:
        """Route a URL to its Qdrant subject_path (e.g. 'regulatory/anp')."""
        ...

    @abstractmethod
    def classification_for(self, subject_path: str, url: str) -> str:
        """Return classification_level ('Public'/'Internal'/'Confidential')."""
        ...

    # ── Main run loop ─────────────────────────────────────────────────────────

    def run(self, mode: str = "files", max_depth_html: int = 4, max_depth_files: int = 2) -> None:
        try:
            self._run(mode=mode, max_depth_html=max_depth_html, max_depth_files=max_depth_files)
        except KeyboardInterrupt:
            print(f"[{self.ORIGIN_SOURCE}] Interrupted — state was saved up to last completed file")
        except Exception as exc:
            import traceback
            print(f"[{self.ORIGIN_SOURCE}] FATAL {type(exc).__name__}: {exc}")
            traceback.print_exc()

    def _run(self, mode: str = "files", max_depth_html: int = 4, max_depth_files: int = 2) -> None:
        print(
            f"[{self.ORIGIN_SOURCE}] Starting crawl — target: {self.ROOT_URL} — mode: {mode} "
            f"— depth html≤{max_depth_html} files≤{max_depth_files}"
        )
        state = self._load_state()
        new_count = updated_count = skipped_count = 0

        try:
            effective_max = max_depth_html if mode == "html" else (
                max_depth_files if mode == "files" else max(max_depth_html, max_depth_files)
            )
            all_pages = self.collect_pages(max_depth=effective_max)
            print(f"[{self.ORIGIN_SOURCE}] Discovered {len(all_pages)} page(s) total (depth ≤ {effective_max})")

            html_pages = [url for url, d in all_pages if d <= max_depth_html]
            file_pages  = [url for url, d in all_pages if d <= max_depth_files]

            # ── HTML pass ──────────────────────────────────────────────────
            if mode in ("html", "all"):
                print(f"[{self.ORIGIN_SOURCE}] HTML pass — {len(html_pages)} page(s) (depth ≤ {max_depth_html})")
                for page_url in html_pages:
                    slug = self.url_to_slug(page_url)
                    state_key = f"html::{slug}"

                    soup = self._fetch_soup(page_url)
                    if soup is None:
                        skipped_count += 1
                        time.sleep(RATE_LIMIT_SECONDS)
                        continue

                    text = self.extract_page_text(soup)
                    if text is None:
                        print(f"[{self.ORIGIN_SOURCE}] SKIP  {page_url} — no extractable content")
                        skipped_count += 1
                        continue

                    sha = self._sha256(text.encode())
                    if state.get(state_key) == sha:
                        print(f"[{self.ORIGIN_SOURCE}] SKIP  {slug}.txt unchanged")
                        skipped_count += 1
                        continue

                    is_new = state_key not in state
                    filename = f"{slug}.txt"
                    subject_path = self.subject_path_for(page_url)
                    cl = self.classification_for(subject_path, page_url)

                    if self.post_to_ingest(filename, text.encode(), subject_path,
                                           classification_level=cl):
                        new_count += 1 if is_new else 0
                        updated_count += 0 if is_new else 1
                        state[state_key] = sha
                        self._save_state(state)

                    time.sleep(RATE_LIMIT_SECONDS)

            # ── Files pass ─────────────────────────────────────────────────
            if mode in ("files", "all"):
                seen_urls: set[str] = set()
                all_links: list[tuple[str, str]] = []
                print(f"[{self.ORIGIN_SOURCE}] Files pass — scanning {len(file_pages)} page(s) (depth ≤ {max_depth_files})")
                for page_url in file_pages:
                    for url, filename in self.discover_links(page_url):
                        if url not in seen_urls:
                            seen_urls.add(url)
                            all_links.append((url, filename))
                    time.sleep(RATE_LIMIT_SECONDS)

                print(f"[{self.ORIGIN_SOURCE}] {len(all_links)} unique document(s) found across all pages")

                for url, filename in all_links:
                    stored_sha, stored_size, stored_etag, stored_last_mod = self._file_state(state, filename)

                    if stored_sha is not None:
                        remote_size, remote_etag, remote_last_mod = self._head_info(url)

                        can_skip = (
                            (remote_etag is not None and remote_etag == stored_etag)
                            or (remote_last_mod is not None and remote_last_mod == stored_last_mod)
                            or (remote_size is not None and remote_size == stored_size)
                            or (stored_size is None and stored_etag is None and stored_last_mod is None
                                and (remote_size is not None or remote_etag is not None or remote_last_mod is not None))
                        )

                        if can_skip:
                            new_entry: dict = {"sha": stored_sha}
                            new_entry["size"] = remote_size if remote_size is not None else stored_size
                            new_entry["etag"] = remote_etag if remote_etag is not None else stored_etag
                            new_entry["last_modified"] = remote_last_mod if remote_last_mod is not None else stored_last_mod
                            new_entry = {k: v for k, v in new_entry.items() if v is not None}
                            if isinstance(state.get(filename), str) or state.get(filename) != new_entry:
                                state[filename] = new_entry
                                self._save_state(state)
                            print(f"[{self.ORIGIN_SOURCE}] SKIP  {filename} unchanged")
                            skipped_count += 1
                            time.sleep(HEAD_RATE_LIMIT_SECONDS)
                            continue

                    content, dl_etag, dl_last_mod = self.download(url)
                    if content is None:
                        skipped_count += 1
                        time.sleep(RATE_LIMIT_SECONDS)
                        continue

                    sha = self._sha256(content)
                    if sha == stored_sha:
                        new_entry = {"sha": sha, "size": len(content)}
                        if dl_etag:
                            new_entry["etag"] = dl_etag
                        if dl_last_mod:
                            new_entry["last_modified"] = dl_last_mod
                        state[filename] = new_entry
                        self._save_state(state)
                        print(f"[{self.ORIGIN_SOURCE}] SKIP  {filename} unchanged")
                        skipped_count += 1
                        time.sleep(RATE_LIMIT_SECONDS)
                        continue

                    is_new = stored_sha is None
                    subject_path = self.subject_path_for(url)
                    cl = self.classification_for(subject_path, url)

                    if self.post_to_ingest(filename, content, subject_path, classification_level=cl):
                        new_count += 1 if is_new else 0
                        updated_count += 0 if is_new else 1
                        new_entry = {"sha": sha, "size": len(content)}
                        if dl_etag:
                            new_entry["etag"] = dl_etag
                        if dl_last_mod:
                            new_entry["last_modified"] = dl_last_mod
                        state[filename] = new_entry
                        self._save_state(state)

                    time.sleep(RATE_LIMIT_SECONDS)

        finally:
            self._save_state(state)
            print(
                f"[{self.ORIGIN_SOURCE}] Done — {new_count} new, {updated_count} updated, "
                f"{skipped_count} skipped. State saved to {self._state_location()}"
            )
