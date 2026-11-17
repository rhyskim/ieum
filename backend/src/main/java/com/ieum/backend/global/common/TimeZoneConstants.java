package com.ieum.backend.global.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class TimeZoneConstants {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final LocalTime DAILY_QUESTION_ASSIGN_TIME_KST = LocalTime.of(19, 0);
    public static final LocalTime PRE_REVEAL_REMINDER_TIME_KST = LocalTime.of(15, 0);
    public static final LocalTime AUTO_REVEAL_TIME_KST = LocalTime.of(17, 0);

    private TimeZoneConstants() {}

    public static Instant assignedAt(LocalDate cycleDate) {
        return ZonedDateTime.of(cycleDate, DAILY_QUESTION_ASSIGN_TIME_KST, KST).toInstant();
    }

    public static Instant autoRevealAt(LocalDate cycleDate) {
        return ZonedDateTime.of(cycleDate.plusDays(1), AUTO_REVEAL_TIME_KST, KST).toInstant();
    }
}
