package com.team.silveragent.api;

import com.team.silveragent.application.care.CareCatalogRepository;
import com.team.silveragent.application.preference.UserPreferenceStore;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/{userId}")
public class UserController {
    private final CareCatalogRepository catalog;
    private final UserPreferenceStore preferences;

    public UserController(CareCatalogRepository catalog, UserPreferenceStore preferences) {
        this.catalog = catalog;
        this.preferences = preferences;
    }

    @GetMapping
    public CareCatalogRepository.UserProfile get(@PathVariable("userId") String userId) {
        return catalog.user(userId)
                .orElseThrow(() -> new IllegalArgumentException("没有找到当前用户"));
    }

    @GetMapping("/preferences")
    public UserPreferenceStore.VoicePreference preferences(@PathVariable("userId") String userId) {
        requireUser(userId);
        return preferences.get(userId);
    }

    @PutMapping("/preferences")
    public UserPreferenceStore.VoicePreference updatePreferences(
            @PathVariable("userId") String userId,
            @RequestBody PreferenceRequest request) {
        requireUser(userId);
        return preferences.save(userId, request.autoSpeakEnabled(),
                request.speechRate(), request.speechVolume());
    }

    private void requireUser(String userId) {
        if (catalog.user(userId).isEmpty()) throw new IllegalArgumentException("没有找到当前用户");
    }

    public record PreferenceRequest(Boolean autoSpeakEnabled, Double speechRate,
                                    Double speechVolume) { }
}
