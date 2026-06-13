"""
Integration security test — M15 Enterprise Document Classification Sync

Validates that Qdrant classification_level filtering enforces the FGA
role-clearance hierarchy defined in FgaService.java end-to-end.

Run locally (ingestion service must already be up):
    export QDRANT_URL=...  QDRANT_API_KEY=...
    pytest tests/test_classification_fga_integration.py -m integration -v

CI: triggered automatically by .github/workflows/security-audit.yml on any
push/PR touching ingestion/src/** or backend/**/fga/**

Test data: 3 XLSX files (Public / Internal / Confidential) are ingested
under subject_path=corporate-answers so that BU path restrictions do not
interfere — only the classification filter gates access.  All fixtures are
deleted from Qdrant in the session teardown regardless of pass/fail outcome.
"""

import os
import time
import uuid
from pathlib import Path
from typing import Generator

import pytest
import requests

# ── Skip the entire module when credentials are absent ───────────────────────
# This means bare `pytest` in the ingestion directory prints a skip notice
# rather than failing with a connection error.

_QDRANT_URL = os.environ.get("QDRANT_URL", "")
_QDRANT_KEY = os.environ.get("QDRANT_API_KEY", "")
_CREDS_PRESENT = bool(_QDRANT_URL and _QDRANT_KEY)

pytestmark = [
    pytest.mark.integration,
    *(
        []
        if _CREDS_PRESENT
        else [
            pytest.mark.skip(
                reason="QDRANT_URL and QDRANT_API_KEY env vars not set — "
                       "integration tests skipped"
            )
        ]
    ),
]

# ── Configuration ─────────────────────────────────────────────────────────────

INGEST_URL   = os.environ.get("INGEST_URL", "http://localhost:8001")
COLLECTION   = "enterprise_knowledge"
TEST_PATH    = "corporate-answers"
FIXTURES_DIR = Path(__file__).parent / "fixtures"

FIXTURE_FILES = [
    ("classification_public.xlsx",       "Public"),
    ("classification_internal.xlsx",     "Internal"),
    ("classification_confidential.xlsx", "Confidential"),
]

# doc_id = uuid5(NAMESPACE_URL, f"{bu_path}/{filename}") — mirrors embed_api.py
FIXTURE_DOC_IDS: dict[str, str] = {
    fname: str(uuid.uuid5(uuid.NAMESPACE_URL, f"{TEST_PATH}/{fname}"))
    for fname, _ in FIXTURE_FILES
}

# ── FGA simulation — mirrors FgaService.java exactly ─────────────────────────

ROLE_CLEARANCE = {
    "admin": 2, "reserves-coordination": 2, "reserves-management": 2,
    "reservoir-team": 1, "bu-user": 1, "employee": 1,
}
LEVEL_TIER = {"Public": 0, "Internal": 1, "Confidential": 2}
KNOWN_BUS  = ["campos", "santos", "solimoes"]
CROSS_BU   = {"admin", "reserves-management", "reserves-coordination"}


def get_blocked_classifications(roles: list[str]) -> list[str]:
    max_tier = max((ROLE_CLEARANCE.get(r, 0) for r in roles), default=0)
    return [lvl for lvl, tier in LEVEL_TIER.items() if tier > max_tier]


def get_bu_restricted_paths(roles: list[str], groups: list[str]) -> list[str]:
    user_bus = {
        g.replace("GROUP_BU_", "").lower()
        for g in groups
        if g.startswith("GROUP_BU_")
    }
    if user_bus:
        return [f"bu/{bu}" for bu in KNOWN_BUS if bu not in user_bus]
    if any(r in CROSS_BU for r in roles):
        return []
    return [f"bu/{bu}" for bu in KNOWN_BUS]


def build_qdrant_filter(restricted_paths: list[str], blocked_cls: list[str]) -> dict:
    must_not: list[dict] = []
    for p in restricted_paths:
        must_not.append({"key": "ancestor_paths", "match": {"any": [p]}})
    if blocked_cls:
        must_not.append({"key": "classification_level", "match": {"any": blocked_cls}})
    return {"must_not": must_not} if must_not else {}

# ── Qdrant REST helpers ───────────────────────────────────────────────────────

def _qdrant(method: str, path: str, **kwargs) -> dict:
    headers = {"api-key": _QDRANT_KEY, "Content-Type": "application/json"}
    url = f"{_QDRANT_URL}/collections/{COLLECTION}{path}"
    r = requests.request(method, url, headers=headers, timeout=30, **kwargs)
    r.raise_for_status()
    return r.json()


def scroll_fixtures(extra_filter: dict | None = None) -> list[dict]:
    """Return only our 3 test fixture points, optionally applying a FGA must_not."""
    body: dict = {
        "filter": {
            "should": [
                {"key": "doc_id", "match": {"value": did}}
                for did in FIXTURE_DOC_IDS.values()
            ]
        },
        "with_payload": True,
        "with_vectors": False,
        "limit": 50,
    }
    if extra_filter and extra_filter.get("must_not"):
        body["filter"]["must_not"] = extra_filter["must_not"]
    result = _qdrant("POST", "/points/scroll", json=body)
    return result.get("result", {}).get("points", [])


def delete_fixtures() -> None:
    """Delete all fixture points from Qdrant using the indexed doc_id field."""
    body = {
        "filter": {
            "should": [
                {"key": "doc_id", "match": {"value": did}}
                for did in FIXTURE_DOC_IDS.values()
            ]
        }
    }
    _qdrant("POST", "/points/delete", json=body)

# ── Session-scoped fixtures ───────────────────────────────────────────────────

@pytest.fixture(scope="session", autouse=True)
def ingestion_service_ready() -> None:
    """Assert the ingestion service is up and the embedding model is loaded."""
    try:
        r = requests.get(f"{INGEST_URL}/health", timeout=15)
        assert r.status_code == 200, f"Health check returned {r.status_code}"
        data = r.json()
        assert data.get("model_loaded"), f"Model not loaded yet: {data}"
    except Exception as exc:
        pytest.fail(
            f"Ingestion service at {INGEST_URL} is not ready: {exc}\n"
            "Ensure the service is running: "
            "uvicorn src.embed_api:app --host 0.0.0.0 --port 8001"
        )


@pytest.fixture(scope="session")
def ingested_points(ingestion_service_ready) -> Generator[dict[str, list[dict]], None, None]:
    """
    Ingest all 3 fixture XLSX files once per test session.
    Yields a dict of {filename: [qdrant_point, ...]} for use in all tests.
    Deletes the fixture points from Qdrant on teardown (runs even if tests fail).
    """
    for filename, _ in FIXTURE_FILES:
        fpath = FIXTURES_DIR / filename
        assert fpath.exists(), (
            f"Fixture not found: {fpath}\n"
            "Generate it first: python tests/generate_test_fixtures.py"
        )
        with open(fpath, "rb") as fh:
            resp = requests.post(
                f"{INGEST_URL}/ingest",
                files={
                    "file": (
                        filename, fh,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    )
                },
                data={"bu_path": TEST_PATH},
                timeout=60,
            )
        assert resp.status_code == 200 and resp.json().get("status") == "indexed", (
            f"Ingest of {filename} failed: HTTP {resp.status_code} — {resp.text[:300]}"
        )

    time.sleep(2)  # allow Qdrant write propagation

    all_points = scroll_fixtures()
    by_file: dict[str, list[dict]] = {}
    for pt in all_points:
        src = pt["payload"].get("source_file", "unknown")
        by_file.setdefault(src, []).append(pt)

    yield by_file

    # ── Teardown ─────────────────────────────────────────────────────────────
    try:
        delete_fixtures()
    except Exception as exc:
        # Non-fatal — warn but don't mask test failures
        print(f"\n[WARN] Fixture cleanup failed (manual cleanup may be needed): {exc}")

# ── Step 1: Payload Audit ─────────────────────────────────────────────────────

class TestPayloadAudit:
    """Verify that the ingestion pipeline writes the correct classification_level."""

    def test_public_fixture_classification(self, ingested_points):
        points = ingested_points.get("classification_public.xlsx", [])
        assert points, "No Qdrant points found for classification_public.xlsx"
        levels = {p["payload"].get("classification_level") for p in points}
        assert levels == {"Public"}, f"Expected {{'Public'}}, got {levels}"

    def test_internal_fixture_classification(self, ingested_points):
        points = ingested_points.get("classification_internal.xlsx", [])
        assert points, "No Qdrant points found for classification_internal.xlsx"
        levels = {p["payload"].get("classification_level") for p in points}
        assert levels == {"Internal"}, f"Expected {{'Internal'}}, got {levels}"

    def test_confidential_fixture_classification(self, ingested_points):
        points = ingested_points.get("classification_confidential.xlsx", [])
        assert points, "No Qdrant points found for classification_confidential.xlsx"
        levels = {p["payload"].get("classification_level") for p in points}
        assert levels == {"Confidential"}, f"Expected {{'Confidential'}}, got {levels}"

# ── Step 2: Low-Clearance Isolation ──────────────────────────────────────────

class TestLowClearanceIsolation:
    """ROLE_bu-user (tier=1) must be blocked from Confidential (tier=2) chunks."""

    ROLES  = ["bu-user"]
    GROUPS: list[str] = []

    @pytest.fixture(scope="class")
    def alice_qdrant_filter(self):
        blocked_cls   = get_blocked_classifications(self.ROLES)
        blocked_paths = get_bu_restricted_paths(self.ROLES, self.GROUPS)
        return build_qdrant_filter(blocked_paths, blocked_cls)

    @pytest.fixture(scope="class")
    def alice_visible_points(self, ingested_points, alice_qdrant_filter):
        return scroll_fixtures(alice_qdrant_filter)

    def test_confidential_is_in_blocked_classifications(self):
        blocked = get_blocked_classifications(self.ROLES)
        assert "Confidential" in blocked, \
            f"ROLE_bu-user should block 'Confidential', got blocked={blocked}"

    def test_internal_is_not_blocked(self):
        blocked = get_blocked_classifications(self.ROLES)
        assert "Internal" not in blocked, \
            "'Internal' must not be blocked for ROLE_bu-user (tier=1)"

    def test_fga_filter_contains_classification_must_not(self, alice_qdrant_filter):
        must_not = alice_qdrant_filter.get("must_not", [])
        assert any(c.get("key") == "classification_level" for c in must_not), \
            "FGA filter is missing a must_not clause on classification_level"

    def test_no_confidential_chunks_visible_to_alice(
        self, ingested_points, alice_visible_points
    ):
        confidential_ids = {
            p["id"]
            for pts in ingested_points.values()
            for p in pts
            if p["payload"].get("classification_level") == "Confidential"
        }
        visible_ids = {p["id"] for p in alice_visible_points}
        leaked = confidential_ids & visible_ids
        assert not leaked, \
            f"SECURITY FAILURE — Confidential chunk IDs leaked to bu-user: {leaked}"

    def test_public_and_internal_remain_accessible_to_alice(self, alice_visible_points):
        visible_levels = {
            p["payload"].get("classification_level") for p in alice_visible_points
        }
        accessible = visible_levels - {"Confidential"}
        assert accessible, (
            "Public and Internal chunks should be accessible to bu-user, "
            f"but visible levels are: {visible_levels}"
        )

# ── Step 3: High-Clearance Elevation ─────────────────────────────────────────

class TestHighClearanceElevation:
    """ROLE_admin (tier=2) must see all classification levels including Confidential."""

    ROLES  = ["admin"]
    GROUPS: list[str] = []

    @pytest.fixture(scope="class")
    def admin_visible_points(self, ingested_points):
        filt = build_qdrant_filter(
            get_bu_restricted_paths(self.ROLES, self.GROUPS),
            get_blocked_classifications(self.ROLES),
        )
        return scroll_fixtures(filt)

    def test_admin_blocks_no_classification_levels(self):
        blocked = get_blocked_classifications(self.ROLES)
        assert blocked == [], \
            f"Admin should block no classification levels, got {blocked}"

    def test_admin_has_no_bu_path_restrictions(self):
        paths = get_bu_restricted_paths(self.ROLES, self.GROUPS)
        assert paths == [], \
            f"Admin should have no BU path restrictions (CROSS_BU_ROLE), got {paths}"

    def test_admin_can_see_confidential_chunks(self, admin_visible_points):
        levels = {p["payload"].get("classification_level") for p in admin_visible_points}
        assert "Confidential" in levels, \
            f"Admin must see Confidential chunks, but visible levels are {levels}"

    def test_admin_sees_confidential_fixture_source_file(self, admin_visible_points):
        files = {p["payload"].get("source_file") for p in admin_visible_points}
        assert "classification_confidential.xlsx" in files, \
            f"classification_confidential.xlsx not visible to admin; found: {files}"

# ── Step 4: ID-Enumeration Penetration Test ───────────────────────────────────

class TestIdEnumerationAttack:
    """
    A low-clearance user who learns a Confidential chunk ID (e.g. from admin
    logs or guessing) must still be blocked at the backend source-preview
    endpoint — GET /api/conversations/*/sources/{chunkId}.
    """

    ALICE_ROLES  = ["bu-user"]
    ALICE_GROUPS: list[str] = []

    @pytest.fixture(scope="class")
    def confidential_chunk_id(self, ingested_points) -> str:
        for pts in ingested_points.values():
            for p in pts:
                if p["payload"].get("classification_level") == "Confidential":
                    return p["id"]
        pytest.skip("No Confidential chunk ID found — Step 1 likely failed")

    def test_confidential_chunk_directly_fetchable_from_qdrant(
        self, confidential_chunk_id
    ):
        """Confirms QdrantSearchClient.getPoint() returns the chunk (no DB-level block)."""
        resp    = _qdrant("GET", f"/points/{confidential_chunk_id}")
        payload = resp.get("result", {}).get("payload", {})
        assert payload.get("classification_level") == "Confidential", (
            f"Expected Confidential classification for chunk {confidential_chunk_id}, "
            f"got: {payload.get('classification_level')}"
        )

    def test_backend_fga_check_would_403_alice(self, confidential_chunk_id):
        """
        Replays ConversationController FGA logic:
          (a) ancestor_paths vs getRestrictedPaths(roles, groups)
          (b) classification_level vs getBlockedClassifications(roles)
        At least one must be True for the backend to return 403 Forbidden.
        """
        resp    = _qdrant("GET", f"/points/{confidential_chunk_id}")
        payload = resp.get("result", {}).get("payload", {})
        cls     = payload.get("classification_level")
        paths   = payload.get("ancestor_paths", [])

        blocked_cls   = get_blocked_classifications(self.ALICE_ROLES)
        blocked_paths = get_bu_restricted_paths(self.ALICE_ROLES, self.ALICE_GROUPS)

        cls_blocked  = cls in blocked_cls
        path_blocked = any(p in blocked_paths for p in paths)

        assert cls_blocked or path_blocked, (
            f"SECURITY FAILURE — backend would NOT return 403 for alice on chunk "
            f"{confidential_chunk_id}: "
            f"cls={cls!r} blocked_cls={blocked_cls} | "
            f"paths={paths} blocked_paths={blocked_paths}"
        )
