package com.enterprise.securechat.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceCitation(
        String chunkId,
        String sourceFile,
        String subjectPath,
        Integer pageNumber,
        String sheetName,
        String originSource,
        String jurisdiction,
        float score
) {}
