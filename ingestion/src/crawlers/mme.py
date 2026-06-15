"""MME petroleum & gas regulatory crawler.

Indexes the Ministério de Minas e Energia petroleum, gas, and biofuels
section via the /ingest API. MME's gov.br portal uses the same Plone CMS
as ANP, so PloneMixin HTML extraction is used unchanged.

All MME content is classified Public (official government publications).
"""

from .base import BaseCrawler, PloneMixin


class MMECrawler(PloneMixin, BaseCrawler):
    ROOT_URL      = "https://www.gov.br/mme/pt-br/assuntos/secretarias/petroleo-gas-natural-e-biocombustiveis"
    ORIGIN_SOURCE = "mme"
    JURISDICTION  = "br/mme"

    def subject_path_for(self, url: str) -> str:
        return "regulatory/mme"

    def classification_for(self, subject_path: str, url: str) -> str:
        return "Public"

    # extract_page_text, extract_breadcrumb, url_to_slug, discover_subpages,
    # discover_links, and collect_pages are all inherited from PloneMixin /
    # BaseCrawler and work correctly for the MME gov.br Plone portal.
