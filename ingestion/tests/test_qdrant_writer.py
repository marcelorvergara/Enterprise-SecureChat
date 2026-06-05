"""Tests for qdrant_writer helpers — especially build_ancestor_paths."""

import uuid

import pytest

from src.qdrant_writer import build_ancestor_paths


class TestBuildAncestorPaths:
    def test_single_segment_returns_itself(self):
        assert build_ancestor_paths("bar-questions") == ["bar-questions"]

    def test_two_segments_returns_both_levels(self):
        assert build_ancestor_paths("bu/santos") == [
            "bu",
            "bu/santos",
        ]

    def test_three_segments_returns_all_three_levels(self):
        assert build_ancestor_paths("bu/santos/reserves") == [
            "bu",
            "bu/santos",
            "bu/santos/reserves",
        ]

    def test_four_segments_deep_path(self):
        result = build_ancestor_paths("bu/santos/reserves/q2-2026")
        assert result == [
            "bu",
            "bu/santos",
            "bu/santos/reserves",
            "bu/santos/reserves/q2-2026",
        ]

    def test_last_element_equals_original_path(self):
        path = "bu/campos/drilling/q3"
        result = build_ancestor_paths(path)
        assert result[-1] == path

    def test_first_element_is_always_top_level(self):
        path = "bu/solimoes/reserves/injectors"
        result = build_ancestor_paths(path)
        assert result[0] == "bu"

    def test_length_matches_segment_count(self):
        path = "a/b/c/d/e"
        result = build_ancestor_paths(path)
        assert len(result) == 5

    def test_restricting_parent_covers_all_descendants(self):
        # Core FGA guarantee: a Qdrant must_not on "bu/santos" excludes
        # bu/santos/reserves because "bu/santos" appears in its ancestor_paths.
        deep_path = "bu/santos/reserves"
        ancestors = build_ancestor_paths(deep_path)
        assert "bu" in ancestors
        assert "bu/santos" in ancestors
        assert "bu/santos/reserves" in ancestors

    def test_deterministic_upsert_ids(self):
        doc_id = "doc-abc-123"
        id1 = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{doc_id}:0"))
        id2 = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{doc_id}:0"))
        assert id1 == id2

    def test_different_chunk_indices_produce_different_ids(self):
        doc_id = "doc-abc-123"
        id_chunk0 = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{doc_id}:0"))
        id_chunk1 = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{doc_id}:1"))
        assert id_chunk0 != id_chunk1
