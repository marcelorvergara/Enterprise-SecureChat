"""ANP E&P regulatory crawler.

Discovers PDF/XLSX files and HTML page text on the ANP Exploração e Produção
portal and indexes them via the /ingest API.

Subject-path routing:
  URLs containing 'reserva', 'recursos', or 'bar' → 'regulatory/anp/bar' (Confidential)
  All other ANP URLs                              → 'regulatory/anp'       (Public)
"""

from bs4 import BeautifulSoup

from .base import BaseCrawler, PloneMixin

_BAR_KEYWORDS = ("reserva", "recursos", "bar")


class ANPCrawler(PloneMixin, BaseCrawler):
    ROOT_URL      = "https://www.gov.br/anp/pt-br/assuntos/exploracao-e-producao-de-oleo-e-gas"
    ORIGIN_SOURCE = "anp"
    JURISDICTION  = "br/anp"

    def subject_path_for(self, url: str) -> str:
        lower = url.lower()
        if any(k in lower for k in _BAR_KEYWORDS):
            return "regulatory/anp/bar"
        return "regulatory/anp"

    def classification_for(self, subject_path: str, url: str) -> str:
        return "Confidential" if subject_path == "regulatory/anp/bar" else "Public"

    # extract_page_text, extract_breadcrumb, url_to_slug, discover_subpages,
    # discover_links, and collect_pages are all inherited from PloneMixin /
    # BaseCrawler and work correctly for the ANP gov.br Plone portal.
