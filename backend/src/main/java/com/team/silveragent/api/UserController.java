package com.team.silveragent.api;

import com.team.silveragent.application.CareCatalogRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/{userId}")
public class UserController {
    private final CareCatalogRepository catalog;

    public UserController(CareCatalogRepository catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public CareCatalogRepository.UserProfile get(@PathVariable("userId") String userId) {
        return catalog.user(userId)
                .orElseThrow(() -> new IllegalArgumentException("没有找到当前用户"));
    }
}
