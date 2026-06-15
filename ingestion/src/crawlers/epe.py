"""EPE (Empresa de Pesquisa Energética) publications crawler.

Scrapes area-4 (Petróleo, Gás Natural e Biocombustíveis) from the EPE
publications portal using Playwright for JavaScript-driven pagination.

The listing page (ROOT_URL) shows 10 publications per page via client-side JS.
Playwright clicks through up to MAX_LISTING_PAGES pages and collects all detail
page URLs; then plain requests finds the PDF link on each detail page and POSTs
it to /ingest. No HTML text is indexed — EPE value is in the PDFs.
"""

import re
import time
from pathlib import Path
from urllib.parse import urljoin, urlparse

from .base import BaseCrawler, HEAD_RATE_LIMIT_SECONDS, RATE_LIMIT_SECONDS


class EPECrawler(BaseCrawler):
    """Crawler for EPE O&G publications (area-4).

    Uses Playwright to navigate JS-paginated listing pages, then plain
    requests to fetch each detail page and download its PDF.
    """

    ROOT_URL      = "https://www.epe.gov.br/pt/publicacoes-dados-abertos/publicacoes/area-4"
    ORIGIN_SOURCE = "epe"
    JURISDICTION  = "br/epe"

    _BASE_URL            = "https://www.epe.gov.br"
    _MAX_LISTING_PAGES   = 10
    _DETAIL_PREFIX       = "/pt/publicacoes-dados-abertos/publicacoes/"

    # Required by BaseCrawler ABC — not used since this crawler overrides run().
    def extract_page_text(self, soup):
        return None

    def subject_path_for(self, url: str) -> str:
        return "regulatory/epe"

    def classification_for(self, subject_path: str, url: str) -> str:
        return "Public"

    # ── Playwright listing scrape ────────────────────────────────────────────

    def _collect_detail_urls(self) -> list[str]:
        """Click through up to _MAX_LISTING_PAGES pages and return detail page URLs."""
        from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

        detail_urls: list[str] = []
        seen: set[str] = set()

        def _extract_links(pg) -> list[str]:
            hrefs = pg.eval_on_selector_all(
                f"a[href*='{self._DETAIL_PREFIX}']",
                "els => els.map(el => el.href)",
            )
            result = []
            for href in hrefs:
                path = urlparse(href).path
                if (
                    path.startswith(self._DETAIL_PREFIX)
                    and path.rstrip("/") != self._DETAIL_PREFIX.rstrip("/")
                    and "area-" not in path
                    and href not in seen
                ):
                    seen.add(href)
                    result.append(href)
            return result

        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True)
            ctx = browser.new_context(user_agent="Enterprise-SecureChat-Crawler/1.0")
            pg = ctx.new_page()

            print(f"[{self.ORIGIN_SOURCE}] Navigating to listing page...")
            pg.goto(self.ROOT_URL, wait_until="networkidle", timeout=60_000)

            for listing_page in range(1, self._MAX_LISTING_PAGES + 1):
                links = _extract_links(pg)
                detail_urls.extend(links)
                print(
                    f"[{self.ORIGIN_SOURCE}] Listing page {listing_page}/{self._MAX_LISTING_PAGES} "
                    f"— {len(links)} new publications (total {len(detail_urls)})"
                )

                if listing_page == self._MAX_LISTING_PAGES:
                    break

                # Click the next page number in the pagination widget. The current
                # page is rendered as a <span> in Liferay; other pages are <a> links
                # with href="javascript:void(0);". We take the last match to avoid
                # accidentally clicking a number inside an article snippet.
                next_num = str(listing_page + 1)
                try:
                    # Try role-based locator first (more semantically correct).
                    next_btn = pg.get_by_role("link", name=re.compile(rf"^{next_num}$")).last
                    if not next_btn.is_visible(timeout=3_000):
                        # Fallback: any <a> whose full text is exactly next_num.
                        next_btn = pg.locator("a").filter(
                            has_text=re.compile(rf"^{next_num}$")
                        ).last
                    next_btn.click(timeout=5_000)
                    pg.wait_for_load_state("networkidle", timeout=20_000)
                    time.sleep(1)  # let any deferred JS re-render settle
                except PWTimeout:
                    print(
                        f"[{self.ORIGIN_SOURCE}] Timeout clicking page {next_num} — stopping early"
                    )
                    break
                except Exception as exc:
                    print(
                        f"[{self.ORIGIN_SOURCE}] Could not navigate to page {next_num} "
                        f"({type(exc).__name__}: {exc}) — stopping early"
                    )
                    break

            browser.close()

        return detail_urls

    # ── PDF discovery on detail pages ────────────────────────────────────────

    def _find_pdf_url(self, detail_url: str) -> str | None:
        """Return the absolute PDF URL linked from a publication detail page."""
        soup = self._fetch_soup(detail_url)
        if soup is None:
            return None
        for a in soup.find_all("a", href=True):
            href = a["href"].strip()
            if Path(urlparse(href).path).suffix.lower() == ".pdf":
                return urljoin(self._BASE_URL, href)
        return None

    # ── Run loop ─────────────────────────────────────────────────────────────

    def run(self, mode: str = "files", max_depth_html: int = 4, max_depth_files: int = 2) -> None:
        try:
            self._run_epe()
        except KeyboardInterrupt:
            print(f"[{self.ORIGIN_SOURCE}] Interrupted — state saved up to last completed file")
        except Exception as exc:
            import traceback
            print(f"[{self.ORIGIN_SOURCE}] FATAL {type(exc).__name__}: {exc}")
            traceback.print_exc()

    def _run_epe(self) -> None:
        print(
            f"[{self.ORIGIN_SOURCE}] Starting EPE crawl — "
            f"up to {self._MAX_LISTING_PAGES} listing pages × 10 publications"
        )
        state = self._load_state()
        new_count = updated_count = skipped_count = 0

        detail_urls = self._collect_detail_urls()
        print(f"[{self.ORIGIN_SOURCE}] Collected {len(detail_urls)} publication detail pages")

        for detail_url in detail_urls:
            time.sleep(RATE_LIMIT_SECONDS)

            pdf_url = self._find_pdf_url(detail_url)
            if pdf_url is None:
                print(f"[{self.ORIGIN_SOURCE}] SKIP  no PDF found on {detail_url}")
                skipped_count += 1
                continue

            filename = Path(urlparse(pdf_url).path).name
            if not filename:
                skipped_count += 1
                continue

            stored_sha, stored_size, stored_etag, stored_last_mod = self._file_state(state, filename)

            if stored_sha is not None:
                remote_size, remote_etag, remote_last_mod = self._head_info(pdf_url)
                can_skip = (
                    (remote_etag is not None and remote_etag == stored_etag)
                    or (remote_last_mod is not None and remote_last_mod == stored_last_mod)
                    or (remote_size is not None and remote_size == stored_size)
                )
                if can_skip:
                    print(f"[{self.ORIGIN_SOURCE}] SKIP  {filename} unchanged")
                    skipped_count += 1
                    time.sleep(HEAD_RATE_LIMIT_SECONDS)
                    continue

            content, dl_etag, dl_last_mod = self.download(pdf_url)
            if content is None:
                skipped_count += 1
                continue

            sha = self._sha256(content)
            if sha == stored_sha:
                entry: dict = {"sha": sha, "size": len(content)}
                if dl_etag:
                    entry["etag"] = dl_etag
                if dl_last_mod:
                    entry["last_modified"] = dl_last_mod
                state[filename] = entry
                self._save_state(state)
                print(f"[{self.ORIGIN_SOURCE}] SKIP  {filename} unchanged (sha match)")
                skipped_count += 1
                continue

            is_new = stored_sha is None
            subject_path = self.subject_path_for(pdf_url)
            cl = self.classification_for(subject_path, pdf_url)

            if self.post_to_ingest(filename, content, subject_path, classification_level=cl):
                new_count += 1 if is_new else 0
                updated_count += 0 if is_new else 1
                entry = {"sha": sha, "size": len(content)}
                if dl_etag:
                    entry["etag"] = dl_etag
                if dl_last_mod:
                    entry["last_modified"] = dl_last_mod
                state[filename] = entry
                self._save_state(state)

        self._save_state(state)
        print(
            f"[{self.ORIGIN_SOURCE}] Done — {new_count} new, {updated_count} updated, "
            f"{skipped_count} skipped. State saved to {self._state_location()}"
        )
