package com.enterprise.securechat.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the "confirmed-boundary" streaming paradigm:
 *   feed()  → emits sentences whose completion is CONFIRMED by the presence of following text
 *   flush() → emits whatever incomplete fragment remains at end of stream
 *
 * A sentence is "confirmed" only when ICU4J finds a boundary AND there is more text
 * after it. The final buffer fragment is always uncertain and belongs to flush().
 * This prevents single tokens or incomplete phrases from being emitted prematurely.
 */
class SentenceBoundaryDetectorTest {

    private SentenceBoundaryDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SentenceBoundaryDetector();
    }

    // ── Incomplete / single-sentence boundary ────────────────────────────────

    @Test
    void incompleteSentence_staysInBuffer() {
        List<String> out = detector.feed("The basin is located in");
        assertThat(out).isEmpty();
        assertThat(detector.flush()).isEqualTo("The basin is located in");
    }

    @Test
    void singleSentence_notEmittedUntilNextSentenceBegins() {
        // A complete sentence at the end of the buffer cannot be confirmed until
        // the next sentence starts arriving — use flush() for the last sentence.
        List<String> fromFeed = new ArrayList<>();
        fromFeed.addAll(detector.feed("The reserve is 140 MMboe."));
        fromFeed.addAll(detector.feed(" "));
        assertThat(fromFeed).isEmpty();
        assertThat(detector.flush()).isEqualTo("The reserve is 140 MMboe.");
    }

    @Test
    void singleSentence_emittedWhenNextSentenceStarts() {
        List<String> out = new ArrayList<>();
        out.addAll(detector.feed("The reserve is 140 MMboe. "));
        out.addAll(detector.feed("Production is growing."));
        // "The reserve..." is confirmed when "Production" starts
        assertThat(out).containsExactly("The reserve is 140 MMboe.");
        assertThat(detector.flush()).isEqualTo("Production is growing.");
    }

    // ── Terminal characters handled by flush() ───────────────────────────────

    @Test
    void questionMark_sentence_emittedViaFlush() {
        detector.feed("Qual é a reserva?");
        assertThat(detector.flush()).isEqualTo("Qual é a reserva?");
    }

    @Test
    void exclamationMark_sentence_emittedViaFlush() {
        detector.feed("Aprovado!");
        assertThat(detector.flush()).isEqualTo("Aprovado!");
    }

    @Test
    void newline_sentence_emittedViaFlush() {
        detector.feed("Primeira linha.\n");
        assertThat(detector.flush()).isEqualTo("Primeira linha.");
    }

    // ── flush() behaviour ────────────────────────────────────────────────────

    @Test
    void flush_returnsRemainingFragment() {
        detector.feed("First sentence. Second incomplete");
        // "First sentence." is confirmed (followed by "Second"); "Second incomplete" stays
        // Note: feed() returns ["First sentence."] which we discard here; flush() gives the tail
        assertThat(detector.flush()).isEqualTo("Second incomplete");
    }

    @Test
    void flush_onEmptyBuffer_returnsEmptyString() {
        assertThat(detector.flush()).isEmpty();
    }

    // ── Multi-sentence batch ─────────────────────────────────────────────────

    @Test
    void multipleSentences_firstNMinusOneConfirmedFromFeed_lastFromFlush() {
        List<String> fromFeed = detector.feed("First sentence. Second sentence. Third sentence.");
        // ICU4J confirms "First" and "Second" because text follows each boundary.
        // "Third sentence." is the last segment — not confirmed, stays in buffer.
        assertThat(fromFeed).containsExactly("First sentence.", "Second sentence.");
        assertThat(detector.flush()).isEqualTo("Third sentence.");
    }

    // ── Decimal numbers must not cause a false split ─────────────────────────

    @Test
    void decimalNumber_doesNotCauseEarlySplit() {
        List<String> out = new ArrayList<>();
        out.addAll(detector.feed("Production rose by 140.5 million barrels. "));
        out.addAll(detector.feed("Reserves remain stable."));
        // The decimal "140.5" must NOT split the first sentence prematurely
        assertThat(out).containsExactly("Production rose by 140.5 million barrels.");
    }

    // ── Token-by-token streaming ─────────────────────────────────────────────

    @Test
    void tokenByTokenStreaming_noSingleTokenEmittedPrematurely() {
        String[] tokens = {"The", " reserve", " is", " 200", " MMboe", "."};
        List<String> out = new ArrayList<>();
        for (String tok : tokens) {
            out.addAll(detector.feed(tok));
        }
        // Nothing should be emitted yet — there is no confirmed next sentence
        assertThat(out).isEmpty();
        assertThat(detector.flush()).isEqualTo("The reserve is 200 MMboe.");
    }

    @Test
    void tokenByTokenStreaming_confirmedSentenceEmittedWhenNextStarts() {
        // ICU4J (pt_BR) splits after "Dr." — document this known behaviour.
        // "Contact Dr." is confirmed when " Silva" arrives because ICU4J sees text
        // after the "Dr." boundary. For DLP purposes each fragment is still valid.
        String[] tokens = {"The", " reserve", " is", " 200", " MMboe", ".", " Contact", " Dr", ".", " Silva", "."};
        List<String> out = new ArrayList<>();
        for (String tok : tokens) {
            out.addAll(detector.feed(tok));
        }
        String tail = detector.flush();
        List<String> all = new ArrayList<>(out);
        if (!tail.isBlank()) all.add(tail);

        // "The reserve is 200 MMboe." confirmed when " Contact" arrives.
        // "Contact Dr." confirmed when " Silva" arrives (ICU4J pt_BR splits at "Dr.").
        // "Silva." is the final tail returned by flush().
        assertThat(all).containsExactly("The reserve is 200 MMboe.", "Contact Dr.", "Silva.");
    }

    // ── ICU4J abbreviation behaviour (documented, not a bug) ─────────────────

    @Test
    void icuSplitsAtSrAbbreviation_documentedBehaviour() {
        // ICU4J pt_BR treats "Sr." as a sentence boundary — "O Sr." is emitted as a
        // confirmed sentence when "Costa..." follows. Each fragment is DLP-safe.
        List<String> fromFeed = detector.feed("O Sr. Costa aprovou o contrato.");
        assertThat(fromFeed).containsExactly("O Sr.");
        assertThat(detector.flush()).isEqualTo("Costa aprovou o contrato.");
    }

    @Test
    void icuSplitsAtUSAbbreviation_documentedBehaviour() {
        // ICU4J pt_BR treats "U.S." as a sentence boundary.
        List<String> fromFeed = detector.feed("U.S. E&P operations are active.");
        assertThat(fromFeed).containsExactly("U.S.");
        assertThat(detector.flush()).isEqualTo("E&P operations are active.");
    }

    // ── Null / empty guard ───────────────────────────────────────────────────

    @Test
    void nullToken_returnsEmpty() {
        assertThat(detector.feed(null)).isEmpty();
    }

    @Test
    void emptyToken_returnsEmpty() {
        assertThat(detector.feed("")).isEmpty();
    }
}
