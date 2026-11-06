package dev.distill.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;

import java.util.regex.Pattern;

public class DistillResultParser {

    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think\\b[^>]*>.*?</think\\s*>");

    private final ObjectMapper objectMapper;

    public DistillResultParser() {
        this(createDefaultObjectMapper());
    }

    public DistillResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.coercionConfigFor(String.class)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    public DistillResult parse(String rawJson) {
        if (rawJson == null) {
            throw new LlmException("LLM response is empty", true);
        }
        String json = THINK_BLOCK.matcher(rawJson).replaceAll("").trim();
        if (json.isEmpty()) {
            throw new LlmException("LLM response is empty", true);
        }
        try {
            return objectMapper.readValue(json, DistillResult.class);
        } catch (JsonProcessingException exception) {
            throw new LlmException("LLM response is not valid JSON for DistillResult", true, exception);
        } catch (IllegalArgumentException exception) {
            throw new LlmException("LLM response has invalid DistillResult fields", true, exception);
        }
    }
}
