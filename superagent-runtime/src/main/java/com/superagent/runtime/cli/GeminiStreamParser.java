package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.usage.TokenUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Gemini CLI {@code --output-format stream-json} events into {@link JsonLineCliRunner.Line}s.
 * Event types: {@code init}, {@code message}, {@code tool_use}, {@code tool_result}, {@code error}, {@code result}.
 * Assistant {@code message} events with {@code delta:true} carry incremental content; without {@code delta}
 * the content is treated as a full snapshot (legacy / non-stream paths).
 */
final class GeminiStreamParser implements JsonLineCliRunner.LineParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode stats;
    private String lastAssistant = "";

    @Override
    public List<JsonLineCliRunner.Line> parse(String line) {
        JsonNode root;
        try {
            root = JSON.readTree(line);
        } catch (Exception e) {
            return List.of();
        }
        String type = root.path("type").asText("");
        return switch (type) {
            case "init" -> {
                String sessionId = root.path("session_id").asText("");
                yield sessionId.isBlank() ? List.of()
                        : List.of(new JsonLineCliRunner.Line(AgentEventType.SYSTEM, sessionId));
            }
            case "message" -> {
                if (!"assistant".equals(root.path("role").asText(""))) {
                    yield List.of();
                }
                String content = root.path("content").asText("");
                if (content.isEmpty()) {
                    yield List.of();
                }
                if (root.path("delta").asBoolean(false)) {
                    // Official stream-json: each chunk is a delta to append (not a cumulative prefix).
                    lastAssistant += content;
                    yield List.of(new JsonLineCliRunner.Line(AgentEventType.TEXT, content));
                }
                // Non-delta snapshot: emit only the new suffix when content grows; otherwise replace.
                String emit = content.startsWith(lastAssistant) ? content.substring(lastAssistant.length()) : content;
                lastAssistant = content;
                yield emit.isEmpty() ? List.of()
                        : List.of(new JsonLineCliRunner.Line(AgentEventType.TEXT, emit));
            }
            case "tool_use" -> {
                String name = root.path("tool_name").asText(root.path("name").asText("tool"));
                yield List.of(new JsonLineCliRunner.Line(AgentEventType.TOOL_USE, name));
            }
            case "tool_result" -> {
                String out = root.path("output").asText("");
                if (out.isBlank() && root.path("error").isObject()) {
                    out = root.path("error").path("message").asText("tool error");
                }
                yield List.of(new JsonLineCliRunner.Line(AgentEventType.TOOL_RESULT, out));
            }
            case "error" -> {
                String msg = root.path("error").path("message").asText(root.path("message").asText("gemini error"));
                yield List.of(new JsonLineCliRunner.Line(AgentEventType.ERROR, msg));
            }
            case "result" -> {
                if (root.path("stats").isObject()) {
                    stats = root.path("stats");
                }
                if ("error".equals(root.path("status").asText(""))) {
                    String msg = root.path("error").path("message").asText("gemini failed");
                    yield List.of(new JsonLineCliRunner.Line(AgentEventType.ERROR, msg));
                }
                String text = lastAssistant.isBlank() ? root.path("response").asText("") : lastAssistant;
                yield List.of(new JsonLineCliRunner.Line(AgentEventType.RESULT, text));
            }
            default -> List.of();
        };
    }

    @Override
    public List<TokenUsage> tokens(String model) {
        if (stats == null) {
            return List.of();
        }
        // stream-json stats: input_tokens / output_tokens / cached (+ optional models.<id>.*).
        // Prefer per-model detail when present; do not also accumulate the flat totals.
        List<TokenUsage> out = new ArrayList<>();
        JsonNode models = stats.path("models");
        if (models.isObject() && !models.isEmpty()) {
            models.fields().forEachRemaining(e -> out.add(streamUsage(e.getKey(), e.getValue())));
            return out.stream().filter(u -> !u.nothingUsed()).toList();
        }
        TokenUsage flat = streamUsage(model, stats);
        return flat.nothingUsed() ? List.of() : List.of(flat);
    }

    /** Splits cached from total input_tokens; uses explicit {@code input} when the CLI already split it. */
    private static TokenUsage streamUsage(String model, JsonNode node) {
        long output = Tokens.n(node, "output_tokens");
        long cached = Tokens.n(node, "cached");
        long explicitInput = Tokens.n(node, "input");
        if (explicitInput > 0) {
            // StreamJsonFormatter also emits non-cached "input" alongside total input_tokens.
            return new TokenUsage(model, explicitInput, cached, 0, 0, output, 0, null);
        }
        return Tokens.fromTotalInput(model, Tokens.n(node, "input_tokens"), cached, 0, output, 0);
    }
}
