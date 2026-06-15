"""Crawler entry point — delegates to the per-source crawler factory.

All crawling logic lives in src/crawlers/. This module preserves the original
CLI interface (defaulting to ANP for backward compat) and exposes a --source
flag so any registered crawler can be invoked from a single command:

    python -m src.crawler [--source anp|mme] [--mode files|html|all]
                          [--max-depth-html N] [--max-depth-files N]

The embed_api.py /crawl endpoint calls run() without arguments — ANP default
is preserved so existing Cloud Scheduler / Docker Compose commands still work.
"""

import argparse
import os

from src.crawlers import crawl as _crawl_factory


def run(
    mode: str = "files",
    max_depth_html: int = 4,
    max_depth_files: int = 2,
    source: str = "anp",
) -> None:
    _crawl_factory(source, mode=mode, max_depth_html=max_depth_html, max_depth_files=max_depth_files)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="O&G regulatory crawler")
    parser.add_argument(
        "--source",
        choices=["anp", "epe", "mme"],
        default=os.getenv("CRAWLER_SOURCE", "anp"),
        help=(
            "Which source to crawl (default: anp). "
            "Defaults to CRAWLER_SOURCE env var, then 'anp'."
        ),
    )
    parser.add_argument(
        "--mode",
        choices=["files", "html", "all"],
        default=os.getenv("CRAWLER_MODE", "files"),
        help=(
            "files: download PDFs/XLSX only (default, backwards-compatible); "
            "html: extract and index HTML page text only; "
            "all: both HTML pages and file downloads. "
            "Defaults to CRAWLER_MODE env var, then 'files'."
        ),
    )
    parser.add_argument(
        "--max-depth-html",
        type=int,
        default=int(os.getenv("CRAWLER_MAX_DEPTH_HTML", "4")),
        dest="max_depth_html",
        help=(
            "BFS depth limit for HTML page extraction (default 4). "
            "Defaults to CRAWLER_MAX_DEPTH_HTML env var."
        ),
    )
    parser.add_argument(
        "--max-depth-files",
        type=int,
        default=int(os.getenv("CRAWLER_MAX_DEPTH_FILES", "2")),
        dest="max_depth_files",
        help=(
            "BFS depth limit for file (PDF/XLSX) discovery (default 2). "
            "Defaults to CRAWLER_MAX_DEPTH_FILES env var."
        ),
    )
    args = parser.parse_args()
    run(args.mode, max_depth_html=args.max_depth_html, max_depth_files=args.max_depth_files, source=args.source)
