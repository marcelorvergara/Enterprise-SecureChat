package com.enterprise.securechat.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourcePreviewResponse(
        String chunkId,
        String chunkText,
        String sourceFile,
        String subjectPath,
        Integer pageNumber,
        String sheetName
) {}
