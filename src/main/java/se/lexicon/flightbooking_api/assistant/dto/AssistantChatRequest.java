package se.lexicon.flightbooking_api.assistant.dto;

import jakarta.validation.constraints.NotBlank;

public record AssistantChatRequest(
        @NotBlank(message = "Session ID is required")
        String sessionId,

        @NotBlank(message = "Message is required")
        String message
) {
}
