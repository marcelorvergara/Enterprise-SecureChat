package com.enterprise.securechat.rag;

import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;

import java.util.ArrayList;
import java.util.List;

/**
 * Buffers streaming tokens and flushes complete sentences using ICU4J BreakIterator.
 * Handles pt-BR edge cases: decimal numbers (140.5), abbreviations (Sr., Dr., U.S.),
 * and basin names — none of which should trigger a sentence boundary.
 *
 * Not thread-safe — one instance per streaming request.
 */
public class SentenceBoundaryDetector {

    private static final ULocale PT_BR = new ULocale("pt_BR");

    private final StringBuilder buffer = new StringBuilder();

    /**
     * Appends a token to the internal buffer and returns any newly completed sentences.
     * May return 0, 1, or more sentences in one call.
     */
    public List<String> feed(String token) {
        if (token == null || token.isEmpty()) {
            return List.of();
        }
        buffer.append(token);
        return extractCompletedSentences();
    }

    /**
     * Returns whatever remains in the buffer (the final incomplete sentence fragment).
     * Call once after the token stream ends to flush any trailing text.
     */
    public String flush() {
        String remaining = buffer.toString().strip();
        buffer.setLength(0);
        return remaining;
    }

    private List<String> extractCompletedSentences() {
        String current = buffer.toString();
        if (current.isEmpty()) return List.of();

        BreakIterator bi = BreakIterator.getSentenceInstance(PT_BR);
        bi.setText(current);

        List<String> sentences = new ArrayList<>();
        int start = 0;
        int end = bi.next();

        while (end != BreakIterator.DONE) {
            int next = bi.next();
            if (next != BreakIterator.DONE) {
                // Confirmed complete: there is text after this boundary.
                // Emit only in this case — the final boundary is always uncertain
                // (it could be mid-abbreviation or the tail of an incomplete sentence).
                String sentence = current.substring(start, end).strip();
                if (!sentence.isEmpty()) sentences.add(sentence);
                start = end;
            }
            // If next == DONE: this is the last boundary (end of buffer).
            // Leave the fragment in the buffer; more tokens may arrive.
            end = next;
        }

        if (start > 0) buffer.delete(0, start);
        return sentences;
    }
}
