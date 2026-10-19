package com.nasim.camundaai.agent;

import java.util.Map;

public record AgentTaskResult(Map<String, Object> variables, boolean alreadyCompleted) { }
