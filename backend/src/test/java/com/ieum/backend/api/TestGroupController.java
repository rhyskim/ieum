package com.ieum.backend.api;

import com.ieum.backend.global.security.GroupMemberOnly;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** @GroupMemberOnly 가 실제 JWT 인증과 함께 동작하는지 검증하기 위한 테스트 전용 엔드포인트. */
@RestController
@RequestMapping("/api/v1/test-groups")
class TestGroupController {

    @GetMapping("/{groupId}")
    @GroupMemberOnly
    public String get(@PathVariable UUID groupId) {
        return "ok";
    }
}
