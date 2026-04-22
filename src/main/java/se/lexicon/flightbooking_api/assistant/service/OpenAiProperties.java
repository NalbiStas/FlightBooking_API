package se.lexicon.flightbooking_api.assistant.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        String apiKey,
        String model,
        String url,
        Integer maxHistoryMessages
) {
}
