package se.lexicon.flightbooking_api.assistant.service;

public interface AssistantService {
    String chat(String sessionId, String userMessage);
}
