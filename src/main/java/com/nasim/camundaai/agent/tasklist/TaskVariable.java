package com.nasim.camundaai.agent.tasklist;

public record TaskVariable(String id, String name, Object value, Boolean isValueTruncated, String previewValue) {
}
