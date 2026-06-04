package com.enterprise.securechat.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceCitation(
        String sourceFile,
        String subjectPath,
        Integer pageNumber,
        String sheetName,
        float score
) {}
