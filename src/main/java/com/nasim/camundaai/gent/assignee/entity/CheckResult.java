package com.nasim.camundaai.gent.assignee.entity;


import io.camunda.zeebe.client.api.response.EvaluateDecisionResponse;

public record CheckResult(String decisionId, EvaluateDecisionResponse response, String output,String failure) { }