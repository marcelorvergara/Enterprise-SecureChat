"""Crawler package — factory for all regulatory source crawlers."""

from .anp import ANPCrawler
from .epe import EPECrawler
from .mme import MMECrawler

__all__ = ["ANPCrawler", "EPECrawler", "MMECrawler", "crawl"]

_CRAWLERS: dict[str, type] = {
    "anp": ANPCrawler,
    "epe": EPECrawler,
    "mme": MMECrawler,
}


def crawl(source: str = "anp", **kwargs) -> None:
    """Instantiate and run the crawler for *source*.

    Args:
        source: One of the registered crawler keys ('anp', 'mme').
        **kwargs: Forwarded to BaseCrawler.run() — mode, max_depth_html,
                  max_depth_files.

    Raises:
        ValueError: If *source* is not registered.
    """
    cls = _CRAWLERS.get(source)
    if cls is None:
        available = ", ".join(sorted(_CRAWLERS))
        raise ValueError(f"Unknown crawler source '{source}'. Available: {available}")
    cls().run(**kwargs)
