package com.team.silveragent.domain.model;

import java.util.List;

public record FollowupPlan(String conversationId, String hospital, String department, String date, String time,
                           String departureTime, List<String> materials, boolean needsConfirmation) { }
