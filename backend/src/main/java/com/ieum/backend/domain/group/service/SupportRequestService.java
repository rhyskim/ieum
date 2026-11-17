package com.ieum.backend.domain.group.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 상한 도달 등 관리자 문의. 전달 채널 없이 DB 저장까지만 한다. */
@Service
@RequiredArgsConstructor
public class SupportRequestService {

    private final JdbcTemplate jdbc;

    public void submit(UUID groupId, UUID userId, String message) {
        jdbc.update(
            "insert into support_requests (group_id, user_id, message) values (?, ?, ?)",
            groupId, userId, message.trim());
    }
}
