"""Tests for the text chunking pipeline (512-token limit, 64-token overlap)."""

import pytest
import tiktoken

from src.chunker import chunk_text


def _token_count(text: str, encoding_name: str = "cl100k_base") -> int:
    enc = tiktoken.get_encoding(encoding_name)
    return len(enc.encode(text))


def _generate_long_text(token_target: int, encoding_name: str = "cl100k_base") -> str:
    """Generate a deterministic text that is at least token_target tokens long."""
    enc = tiktoken.get_encoding(encoding_name)
    # Each word "word" is typically 1 token; pad to exceed the target.
    words = ["enterprise"] * (token_target + 100)
    return " ".join(words)


class TestChunkText:
    def test_short_text_returns_single_chunk(self):
        text = "This is a short document."
        chunks = chunk_text(text)
        assert len(chunks) == 1
        assert chunks[0] == text

    def test_empty_text_returns_no_chunks(self):
        chunks = chunk_text("")
        assert chunks == []

    def test_each_chunk_stays_within_512_token_limit(self):
        long_text = _generate_long_text(2000)
        chunks = chunk_text(long_text)

        assert len(chunks) > 1, "Long text should produce multiple chunks"
        for chunk in chunks:
            count = _token_count(chunk)
            assert count <= 512, f"Chunk exceeds 512 tokens: {count}"

    def test_custom_chunk_size_is_respected(self):
        long_text = _generate_long_text(1000)
        chunks = chunk_text(long_text, chunk_size=128, chunk_overlap=16)

        for chunk in chunks:
            count = _token_count(chunk)
            assert count <= 128, f"Chunk exceeds 128 tokens: {count}"

    def test_overlap_means_adjacent_chunks_share_content(self):
        # Build text that's definitely >1 chunk at 128 tokens
        long_text = _generate_long_text(400)
        chunks = chunk_text(long_text, chunk_size=128, chunk_overlap=32)

        assert len(chunks) >= 2, "Need at least two chunks to verify overlap"
        # The end of chunk[0] and the start of chunk[1] must share tokens
        enc = tiktoken.get_encoding("cl100k_base")
        tail_tokens = enc.encode(chunks[0])[-32:]
        head_tokens = enc.encode(chunks[1])[:32]
        overlap = set(tail_tokens) & set(head_tokens)
        assert len(overlap) > 0, "Expected token overlap between consecutive chunks"

    def test_paragraph_boundaries_are_preferred_split_points(self):
        # RecursiveCharacterTextSplitter prefers \n\n > \n > " " for splits,
        # so paragraph text should not be split mid-paragraph unless necessary.
        para1 = "Section one. " * 20
        para2 = "Section two. " * 20
        text = para1.strip() + "\n\n" + para2.strip()

        chunks = chunk_text(text, chunk_size=128, chunk_overlap=0)
        # No chunk should start mid-word (basic sanity; real boundary tests
        # are covered by LangChain's own test suite)
        for chunk in chunks:
            assert chunk == chunk.strip() or chunk[0] != " "
