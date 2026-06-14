package com.enterprise.securechat.rag;

import com.enterprise.securechat.rag.dto.ChatRequest;
import com.enterprise.securechat.rag.dto.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class RagController {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".xlsx", ".xls", ".png", ".jpg", ".jpeg", ".tiff", ".tif",
            ".txt", ".md", ".csv");

    private final RagService ragService;
    private final ParseClient parseClient;
    private final ThreadPoolTaskExecutor sseExecutor;

    public RagController(RagService ragService, ParseClient parseClient,
                         @Qualifier("sseExecutor") ThreadPoolTaskExecutor sseExecutor) {
        this.ragService = ragService;
        this.parseClient = parseClient;
        this.sseExecutor = sseExecutor;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ragService.chat(request, auth));
    }

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @Valid @RequestBody ChatRequest request,
            Authentication auth) {
        var emitter = new SseEmitter(120_000L);
        sseExecutor.submit(() -> ragService.chatStream(request, auth, emitter));
        return emitter;
    }

    @PostMapping(value = "/chat/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatResponse> verify(
            @RequestPart("message") String message,
            @RequestPart(name = "conversationId", required = false) String conversationId,
            @RequestPart("file") MultipartFile file,
            Authentication auth) throws IOException {

        var rawFilename = file.getOriginalFilename();
        var filename = rawFilename != null ? rawFilename : "";
        var ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.')).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) return ResponseEntity.badRequest().build();
        if (message == null || message.isBlank() || message.length() > 4000)
            return ResponseEntity.badRequest().build();

        UUID convId = null;
        if (conversationId != null && !conversationId.isBlank()) {
            try {
                convId = UUID.fromString(conversationId);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }

        var documentText = parseClient.parse(file);
        return ResponseEntity.ok(
                ragService.chatWithDocument(new ChatRequest(message, convId), documentText, filename, auth));
    }
}
