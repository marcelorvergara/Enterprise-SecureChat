package com.enterprise.securechat.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ChatRequest(
        @NotBlank @Size(max = 4000) String message,
        UUID conversationId
) {}
