package com.team.silveragent.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class UserPreferenceStore {
    private final JdbcTemplate jdbc;

    public UserPreferenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public VoicePreference get(String userId) {
        List<VoicePreference> rows = jdbc.query("""
                SELECT user_id,auto_speak_enabled,speech_rate,speech_volume
                FROM user_preferences WHERE user_id=?
                """, (rs, row) -> new VoicePreference(rs.getString(1), rs.getBoolean(2),
                rs.getDouble(3), rs.getDouble(4)), userId);
        return rows.isEmpty() ? new VoicePreference(userId, false, 0.9, 1.0) : rows.get(0);
    }

    @Transactional
    public VoicePreference save(String userId, Boolean autoSpeakEnabled,
                                Double speechRate, Double speechVolume) {
        VoicePreference current = get(userId);
        boolean enabled = autoSpeakEnabled == null ? current.autoSpeakEnabled() : autoSpeakEnabled;
        double rate = speechRate == null ? current.speechRate() : speechRate;
        double volume = speechVolume == null ? current.speechVolume() : speechVolume;
        if (rate < 0.5 || rate > 2.0) throw new IllegalArgumentException("语速必须在0.5到2.0之间");
        if (volume < 0.0 || volume > 1.0) throw new IllegalArgumentException("音量必须在0到1之间");
        jdbc.update("""
                MERGE INTO user_preferences
                (user_id,auto_speak_enabled,speech_rate,speech_volume,updated_at)
                KEY(user_id) VALUES (?,?,?,?,?)
                """, userId, enabled, rate, volume, Timestamp.valueOf(LocalDateTime.now()));
        return new VoicePreference(userId, enabled, rate, volume);
    }

    public record VoicePreference(String userId, boolean autoSpeakEnabled,
                                  double speechRate, double speechVolume) { }
}
