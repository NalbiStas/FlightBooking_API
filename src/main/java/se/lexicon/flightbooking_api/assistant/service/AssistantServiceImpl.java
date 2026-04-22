package se.lexicon.flightbooking_api.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import se.lexicon.flightbooking_api.dto.BookFlightRequestDTO;
import se.lexicon.flightbooking_api.dto.FlightBookingDTO;
import se.lexicon.flightbooking_api.dto.FlightListDTO;
import se.lexicon.flightbooking_api.service.FlightBookingService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AssistantServiceImpl implements AssistantService {

    private static final String SYSTEM_MESSAGE = """
            You are a flight reservation assistant for this application.
            Your job is to help the user search flights, book flights, and cancel bookings.
            Be helpful, concise, and guide the user step by step.
            If information is missing, ask for exactly the missing details.
            Never invent flight data or booking results.
            Always use tools when the user wants to search flights, book a flight, or cancel a booking.
            When booking, make sure you have flightId, passengerName, and passengerEmail.
            When cancelling, make sure you have flightId and passengerEmail.
            """;

    private final FlightBookingService flightBookingService;
    private final OpenAiProperties openAiProperties;
    private final ObjectMapper objectMapper;

    private final Map<String, Deque<Map<String, Object>>> chatHistory = new ConcurrentHashMap<>();

    @Override
    public String chat(String sessionId, String userMessage) {
        Deque<Map<String, Object>> history = chatHistory.computeIfAbsent(sessionId, key -> new ArrayDeque<>());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", SYSTEM_MESSAGE));
        messages.addAll(history);
        messages.add(message("user", userMessage));

        JsonNode firstResponse = callOpenAi(messages, toolDefinitions());
        JsonNode assistantMessage = extractAssistantMessage(firstResponse);

        messages.add(jsonNodeToMap(assistantMessage));

        JsonNode toolCalls = assistantMessage.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            for (JsonNode toolCall : toolCalls) {
                messages.add(executeToolCall(toolCall));
            }

            JsonNode secondResponse = callOpenAi(messages, null);
            JsonNode finalAssistantMessage = extractAssistantMessage(secondResponse);
            String reply = finalAssistantMessage.path("content").asText("Sorry, I could not process that request.");

            appendToHistory(history, message("user", userMessage));
            appendToHistory(history, message("assistant", reply));
            trimHistory(history);
            return reply;
        }

        String reply = assistantMessage.path("content").asText("Sorry, I could not process that request.");
        appendToHistory(history, message("user", userMessage));
        appendToHistory(history, message("assistant", reply));
        trimHistory(history);
        return reply;
    }

    private JsonNode callOpenAi(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", valueOrDefault(openAiProperties.model(), "gpt-4o-mini"));
        requestBody.put("messages", messages);

        if (tools != null) {
            requestBody.put("tools", tools);
            requestBody.put("tool_choice", "auto");
        }

        WebClient webClient = WebClient.builder()
                .baseUrl(valueOrDefault(openAiProperties.url(), "https://api.openai.com/v1"))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + openAiProperties.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        String responseBody = webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        try {
            return objectMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse OpenAI response", e);
        }
    }

    private JsonNode extractAssistantMessage(JsonNode response) {
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("OpenAI response did not contain choices");
        }
        return choices.get(0).path("message");
    }

    private Map<String, Object> executeToolCall(JsonNode toolCall) {
        String toolCallId = toolCall.path("id").asText(UUID.randomUUID().toString());
        String functionName = toolCall.path("function").path("name").asText();
        String argumentsJson = toolCall.path("function").path("arguments").asText("{}");

        Map<String, Object> arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse tool arguments", e);
        }

        Object result = switch (functionName) {
            case "get_all_flights" -> flightBookingService.findAll();
            case "get_available_flights" -> flightBookingService.findAvailableFlights();
            case "book_flight" -> bookFlight(arguments);
            case "cancel_flight" -> cancelFlight(arguments);
            default -> Map.of("error", "Unknown tool: " + functionName);
        };

        return toolMessage(toolCallId, writeJson(result));
    }

    private FlightBookingDTO bookFlight(Map<String, Object> arguments) {
        Long flightId = toLong(arguments.get("flightId"));
        String passengerName = toStringValue(arguments.get("passengerName"));
        String passengerEmail = toStringValue(arguments.get("passengerEmail"));
        return flightBookingService.bookFlight(flightId, new BookFlightRequestDTO(passengerName, passengerEmail));
    }

    private Map<String, Object> cancelFlight(Map<String, Object> arguments) {
        Long flightId = toLong(arguments.get("flightId"));
        String passengerEmail = toStringValue(arguments.get("passengerEmail"));
        flightBookingService.cancelFlight(flightId, passengerEmail);
        return Map.of("success", true, "message", "Flight booking cancelled successfully");
    }

    private List<Map<String, Object>> toolDefinitions() {
        return List.of(
                functionTool(
                        "get_all_flights",
                        "Get all flights in the system",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(),
                                "additionalProperties", false
                        )
                ),
                functionTool(
                        "get_available_flights",
                        "Get all flights that are currently available for booking",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(),
                                "additionalProperties", false
                        )
                ),
                functionTool(
                        "book_flight",
                        "Book a flight for a passenger",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "flightId", Map.of("type", "integer", "description", "The flight ID to book"),
                                        "passengerName", Map.of("type", "string", "description", "The passenger full name"),
                                        "passengerEmail", Map.of("type", "string", "description", "The passenger email")
                                ),
                                "required", List.of("flightId", "passengerName", "passengerEmail"),
                                "additionalProperties", false
                        )
                ),
                functionTool(
                        "cancel_flight",
                        "Cancel a booked flight using flight ID and passenger email",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "flightId", Map.of("type", "integer", "description", "The flight ID to cancel"),
                                        "passengerEmail", Map.of("type", "string", "description", "The passenger email used for the booking")
                                ),
                                "required", List.of("flightId", "passengerEmail"),
                                "additionalProperties", false
                        )
                )
        );
    }

    private Map<String, Object> functionTool(String name, String description, Map<String, Object> parameters) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", parameters
                )
        );
    }

    private Map<String, Object> message(String role, String content) {
        return Map.of(
                "role", role,
                "content", content
        );
    }

    private Map<String, Object> toolMessage(String toolCallId, String content) {
        return Map.of(
                "role", "tool",
                "tool_call_id", toolCallId,
                "content", content
        );
    }

    private Map<String, Object> jsonNodeToMap(JsonNode jsonNode) {
        return objectMapper.convertValue(jsonNode, new TypeReference<>() {});
    }

    private void appendToHistory(Deque<Map<String, Object>> history, Map<String, Object> message) {
        history.addLast(message);
    }

    private void trimHistory(Deque<Map<String, Object>> history) {
        int maxHistoryMessages = valueOrDefault(openAiProperties.maxHistoryMessages(), 10);
        while (history.size() > maxHistoryMessages) {
            history.removeFirst();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tool result", e);
        }
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private Integer valueOrDefault(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Long.parseLong(stringValue);
        }
        throw new IllegalArgumentException("Missing required numeric value");
    }

    private String toStringValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing required string value");
        }
        return String.valueOf(value);
    }
    private String cleanReply(String reply) {
        if (reply == null) {
            return "";
        }

        return reply
                .replace("\\n", "\n")
                .replace("**", "")
                .trim();
    }
}
