package com.ieum.backend.domain.notification.push;

/** 토큰 하나에 푸시를 보내는 포트. 예외를 던지지 않고 결과로 알린다. */
public interface PushSender {

    PushResult send(String fcmToken, PushMessage message);
}
