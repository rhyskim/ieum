package com.ieum.backend.domain.notification.push;

import java.util.Map;

/** 푸시 한 건. 제목·본문·data에는 개인 정보(이름·호칭·메시지)를 넣지 않는다(잠금 화면 노출). */
public record PushMessage(String title, String body, Map<String, String> data) {
}
