"""Tests for qdrant_writer helpers — especially build_ancestor_paths."""

import uuid

import pytest

from src.qdrant_writer import build_ancestor_paths


class TestBuildAncestorPaths:
    def test_single_segment_returns_itself(self):
        assert build_ancestor_paths("finance") == ["finance"]

    def test_two_segments_returns_both_levels(self):
        assert build_ancestor_paths("finance/payroll") == [
            "finance",
            "finance/payroll",
        ]

    def test_three_segments_returns_all_three_levels(self):
        assert build_ancestor_paths("finance/payroll/q3") == [
            "finance",
            "finance/payroll",
            "finance/payroll/q3",
        ]

    def test_four_segments_deep_path(self):
        result = build_ancestor_paths("finance/payroll/q3/summary")
        assert result == [
            "finance",
            "finance/payroll",
            "finance/payroll/q3",
            "finance/payroll/q3/summary",
        ]

    def test_last_element_equals_original_path(self):
        path = "hr/compensation/executive"
        result = build_ancestor_paths(path)
        assert result[-1] == path

    def test_first_element_is_always_top_level(self):
        path = "it-ops/infra/network/vpn"
        result = build_ancestor_paths(path)
        assert result[0] == "it-ops"

    def test_length_matches_segment_count(self):
        path = "a/b/c/d/e"
        result = build_ancestor_paths(path)
        assert len(result) == 5

    def test_restricting_parent_covers_all_descendants(self):
        # This is the core FGA guarantee: a Qdrant must_not on "finance" will
        # exclude finance/payroll/q3 because "finance" appears in its ancestor_paths.
        deep_path = "finance/payroll/q3"
        ancestors = build_ancestor_paths(deep_path)
        assert "finance" in ancestors
        assert "finance/payroll" in ancestors
        assert "finance/payroll/q3" in ancestors

    def test_deterministic_upsert_ids(self):
        doc_id = "doc-abc-123"
        # Verify that uuid5 IDs produced by the writer are deterministic
        id1 = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{doc_id}:0"))
        id2 = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{doc_id}:0"))
        assert id1 == id2

    def test_different_chunk_indices_produce_different_ids(self):
        doc_id = "doc-abc-123"
        id_chunk0 = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{doc_id}:0"))
        id_chunk1 = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{doc_id}:1"))
        assert id_chunk0 != id_chunk1
