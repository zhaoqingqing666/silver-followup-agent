package com.team.silveragent.api;

import com.team.silveragent.application.HealthProfileStore;
import com.team.silveragent.application.care.CareCatalogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 老人健康档案：过敏史、既往病史、身高、体重。
 *
 * <p>家属端"长辈信息"点进去的那一页调的就是这一对接口。谁改的对方下次打开都能看到，
 * 不需要同步——两端读写的是同一份数据。写入口对家属和志愿者开放：替老人填，
 * 比让老人自己打字现实得多。
 *
 * <p>这里只对人和界面开放，助手那条链路一律不碰这块数据（{@link HealthProfileStore} 里有说明）。
 */
@RestController
@RequestMapping("/api/users/{userId}/health-profile")
public class HealthProfileController {
    private final CareCatalogRepository catalog;
    private final HealthProfileStore profiles;

    public HealthProfileController(CareCatalogRepository catalog, HealthProfileStore profiles) {
        this.catalog = catalog;
        this.profiles = profiles;
    }

    @GetMapping
    public HealthProfileStore.HealthProfile get(@PathVariable("userId") String userId) {
        requireUser(userId);
        return profiles.get(userId);
    }

    @PutMapping
    public HealthProfileStore.HealthProfile save(@PathVariable("userId") String userId,
                                                 @RequestBody HealthProfileRequest request) {
        requireUser(userId);
        // editorName 只用来显示"最近是谁填的"：这块信息两个人都能改，得看得出是谁写的。
        return profiles.save(userId, request.editorName(), request.heightCm(), request.weightKg(),
                request.allergies(), request.medicalHistory());
    }

    private void requireUser(String userId) {
        if (catalog.user(userId).isEmpty()) throw new IllegalArgumentException("没有找到这位老人");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }

    /** 四个字段一个口径：null = 这一项没动，空串 = 清掉，有值 = 改成它。 */
    public record HealthProfileRequest(String heightCm, String weightKg, String allergies,
                                       String medicalHistory, String editorName) { }
}
