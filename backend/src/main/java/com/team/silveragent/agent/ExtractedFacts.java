package com.team.silveragent.agent;

import java.time.LocalDate;
import java.time.LocalTime;

public record ExtractedFacts(
        String intent,
        String hospital,
        String department,
        LocalDate date,
        Boolean acceptAlternative,
        Boolean needCompanion,
        Boolean needTravel,
        Boolean notifyFamily,
        String transport,
        LocalTime selectedTime,
        String timePreference,
        Boolean acceptRecommendedTime,
        String acknowledgement,
        String emotion,
        String concern,
        String familyContact
) {
    public static ExtractedFacts empty() {
        return new ExtractedFacts("UNKNOWN", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
