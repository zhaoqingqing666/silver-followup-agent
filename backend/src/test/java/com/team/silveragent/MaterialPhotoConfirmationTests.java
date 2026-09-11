package com.team.silveragent;

import com.team.silveragent.domain.model.ToolModels.AppointmentMaterial;
import com.team.silveragent.domain.tool.MaterialPreparationTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 材料清单「拍照确认」。
 * 关键约束：appointment_materials.photo_url 只有 VARCHAR(500)，压缩后的 JPEG data URL 动辄几十万字符，
 * 直接写会截断或报错——照片本体必须落到 conversation_attachments，photo_url 只存短引用，
 * 列表响应才不会因为一张照片整体膨胀。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-material-photo;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class MaterialPhotoConfirmationTests {
    private static final String APPOINTMENT = "appt-photo-001";
    private static final String USER = "user-001";
    /** 一张够长的假 data URL：模拟压缩后的照片体量，用来证明它不会进 photo_url。 */
    private static final String PHOTO = "data:image/jpeg;base64," + "A".repeat(400_000);

    @Autowired MaterialPreparationTool materials;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM appointment_materials WHERE appointment_id=?", APPOINTMENT);
        jdbc.update("DELETE FROM conversation_attachments WHERE conversation_id=?",
                "appointment:" + APPOINTMENT);
        jdbc.update("DELETE FROM appointment_slots WHERE id='slot-photo'");
        jdbc.update("DELETE FROM appointments WHERE id=?", APPOINTMENT);
        jdbc.update("""
                INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,
                    appointment_date,appointment_time,available)
                VALUES ('slot-photo','h001','市第一医院','心内科','2026-09-18','09:00',TRUE)
                """);
        jdbc.update("""
                INSERT INTO appointments(id,slot_id,user_id,status,created_at,conversation_id,materials)
                VALUES (?,?,'user-001','CONFIRMED',CURRENT_TIMESTAMP,'conv-photo','身份证、医保卡')
                """, APPOINTMENT, "slot-photo");
        jdbc.update("""
                INSERT INTO appointment_materials(id,appointment_id,material_code,material_name,
                    required,status,confirm_source,photo_url,updated_at)
                VALUES ('AM-photo-1',?,'id-card','身份证',TRUE,'NOT_PREPARED',NULL,NULL,CURRENT_TIMESTAMP)
                """, APPOINTMENT);
    }

    private AppointmentMaterial update(String status, String source, String photoUrl) {
        return materials.updateStatus(USER, APPOINTMENT, "AM-photo-1", status, source, photoUrl);
    }

    private List<String> storedPhotoUrls() {
        return jdbc.queryForList("SELECT photo_url FROM appointment_materials WHERE id='AM-photo-1'",
                String.class);
    }

    @Test
    void photoBecomesAShortReferenceAndTheImageItselfLandsInAttachments() {
        AppointmentMaterial updated = update("PHOTO_CONFIRMED", "PHOTO", PHOTO);

        assertThat(updated.status()).isEqualTo("PHOTO_CONFIRMED");
        assertThat(updated.confirmSource()).isEqualTo("PHOTO");
        assertThat(updated.photoUrl()).startsWith("attachment:").hasSizeLessThan(40);

        // 图片本体在附件表里，且确实是我们那一张
        Long id = Long.valueOf(updated.photoUrl().substring("attachment:".length()));
        String dataUrl = jdbc.queryForObject(
                "SELECT data_url FROM conversation_attachments WHERE id=?", String.class, id);
        assertThat(dataUrl).isEqualTo(PHOTO);
        assertThat(jdbc.queryForObject(
                "SELECT kind FROM conversation_attachments WHERE id=?", String.class, id))
                .isEqualTo("MATERIAL_PHOTO");
    }

    @Test
    void listStaysSmallEvenAfterAPhotoIsConfirmed() {
        update("PHOTO_CONFIRMED", "PHOTO", PHOTO);

        List<AppointmentMaterial> rows = materials.list(USER, APPOINTMENT);
        assertThat(rows).hasSize(1);
        // 列表接口带的是短引用而不是 base64：一次 list 的响应不该被照片撑大
        assertThat(rows.get(0).photoUrl()).startsWith("attachment:").hasSizeLessThan(40);
    }

    @Test
    void photoSurvivesAReload() {
        update("PHOTO_CONFIRMED", "PHOTO", PHOTO);

        AppointmentMaterial reloaded = materials.list(USER, APPOINTMENT).get(0);
        assertThat(reloaded.status()).isEqualTo("PHOTO_CONFIRMED");
        assertThat(reloaded.photoUrl()).isNotBlank();
    }

    @Test
    void repeatingTheSameReferenceDoesNotStoreThePhotoTwice() {
        String first = update("PHOTO_CONFIRMED", "PHOTO", PHOTO).photoUrl();
        AppointmentMaterial again = update("PHOTO_CONFIRMED", "PHOTO", first);

        assertThat(again.photoUrl()).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM conversation_attachments
                WHERE conversation_id=? AND kind='MATERIAL_PHOTO'
                """, Integer.class, "appointment:" + APPOINTMENT)).isEqualTo(1);
    }

    @Test
    void unsupportedStatusIsRejectedAndTheRowIsUntouched() {
        assertThatThrownBy(() -> update("ALREADY_DONE", "USER", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的材料状态");
        assertThat(storedPhotoUrls()).containsExactly((String) null);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM appointment_materials WHERE id='AM-photo-1'", String.class))
                .isEqualTo("NOT_PREPARED");
    }

    @Test
    void unknownConfirmSourceIsNormalisedInsteadOfStoredRaw() {
        AppointmentMaterial odd = update("PREPARED", "  photo ", null);
        assertThat(odd.confirmSource()).isEqualTo("PHOTO");

        AppointmentMaterial junk = update("PREPARED", "' OR 1=1 --", null);
        assertThat(junk.confirmSource()).isEqualTo("USER");

        AppointmentMaterial blank = update("PREPARED", "", null);
        assertThat(blank.confirmSource()).isEqualTo("USER");
    }

    @Test
    void nonImageAndOversizedPhotosAreRejectedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> update("PHOTO_CONFIRMED", "PHOTO", "https://example.com/a.jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持图片");

        assertThatThrownBy(() -> update("PHOTO_CONFIRMED", "PHOTO",
                "data:image/jpeg;base64," + "A".repeat(2_000_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("太大");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM conversation_attachments
                WHERE conversation_id=? AND kind='MATERIAL_PHOTO'
                """, Integer.class, "appointment:" + APPOINTMENT)).isZero();
        assertThat(storedPhotoUrls()).containsExactly((String) null);
    }

    @Test
    void theConfirmedPhotoCanBeReadBack() {
        String ref = update("PHOTO_CONFIRMED", "PHOTO", PHOTO).photoUrl();

        assertThat(materials.photo(USER, APPOINTMENT, "AM-photo-1")).contains(PHOTO);
        assertThat(ref).startsWith("attachment:");
    }

    @Test
    void aMaterialWithoutAPhotoHasNothingToRead() {
        assertThat(materials.photo(USER, APPOINTMENT, "AM-photo-1")).isEmpty();

        update("PREPARED", "USER", null);
        assertThat(materials.photo(USER, APPOINTMENT, "AM-photo-1")).isEmpty();
    }

    @Test
    void readingSomeoneElsesAppointmentIsRefused() {
        update("PHOTO_CONFIRMED", "PHOTO", PHOTO);

        // 换一位用户来读同一条预约：归属校验先拦下，和材料列表一样不给看
        assertThatThrownBy(() -> materials.photo("user-999", APPOINTMENT, "AM-photo-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有找到这条预约记录");
    }

    @Test
    void anotherAppointmentsPhotosCannotBeHungOnThisOnesMaterial() {
        // 另一条预约自己拍的照片
        jdbc.update("DELETE FROM appointments WHERE id='appt-photo-002'");
        jdbc.update("""
                INSERT INTO appointments(id,slot_id,user_id,status,created_at,conversation_id)
                VALUES ('appt-photo-002','slot-photo','user-001','CONFIRMED',CURRENT_TIMESTAMP,'conv-photo-2')
                """);
        jdbc.update("""
                INSERT INTO appointment_materials(id,appointment_id,material_code,material_name,
                    required,status,confirm_source,photo_url,updated_at)
                VALUES ('AM-photo-2','appt-photo-002','id-card','身份证',TRUE,'NOT_PREPARED',NULL,NULL,CURRENT_TIMESTAMP)
                """);
        String otherRef = materials.updateStatus(USER, "appt-photo-002", "AM-photo-2",
                "PHOTO_CONFIRMED", "PHOTO", PHOTO).photoUrl();

        // 把别人那条预约的照片编号填进自己这行的 photoUrl：引用是客户端传的，必须被认出来不是自己的
        assertThatThrownBy(() -> update("PHOTO_CONFIRMED", "PHOTO", otherRef))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已经失效");
        // 凭空编一个编号也一样
        assertThatThrownBy(() -> update("PHOTO_CONFIRMED", "PHOTO", "attachment:999999"))
                .isInstanceOf(IllegalArgumentException.class);
        // 自己这张仍然读得回来
        assertThat(materials.photo(USER, "appt-photo-002", "AM-photo-2")).contains(PHOTO);
    }

    @Test
    void goingBackToNotPreparedClearsThePhotoReference() {
        update("PHOTO_CONFIRMED", "PHOTO", PHOTO);

        AppointmentMaterial cleared = update("NOT_PREPARED", "USER", null);
        assertThat(cleared.photoUrl()).isNull();
        assertThat(cleared.status()).isEqualTo("NOT_PREPARED");
    }
}
