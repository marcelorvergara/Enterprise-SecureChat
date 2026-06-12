package com.enterprise.securechat.rag.dto;

import java.util.List;
import java.util.UUID;

public record ChatResponse(
        String answer,
        UUID conversationId,
        List<SourceCitation> sources,
        boolean fgaApplied,
        int dlpEntitiesRedacted,
        List<String> suggestions
) {}
