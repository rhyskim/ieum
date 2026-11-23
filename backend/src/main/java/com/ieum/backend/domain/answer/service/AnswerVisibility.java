package com.ieum.backend.domain.answer.service;

import com.ieum.backend.domain.common.enums.RevealStatus;

/**
 * 서버 마스킹 규칙의 단일 정의(ADR-0007 결정 3). 타인의 답변(본문·리액션)은 REVEALED_ALL이거나,
 * REVEALED_TIMEOUT이면서 내가 이미 답변을 마쳤을 때만 볼 수 있다. 본인 답변은 항상 볼 수 있다.
 */
public final class AnswerVisibility {

    private AnswerVisibility() {}

    public static boolean canSeeOthers(RevealStatus status, boolean viewerAnswered) {
        return status == RevealStatus.REVEALED_ALL
            || (status == RevealStatus.REVEALED_TIMEOUT && viewerAnswered);
    }
}
