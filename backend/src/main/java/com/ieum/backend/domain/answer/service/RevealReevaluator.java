package com.ieum.backend.domain.answer.service;

import com.ieum.backend.domain.question.repository.DailyQuestionRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멤버가 빠지는 사건(탈퇴 요청·강퇴·나가기) 뒤에 그 그룹의 LOCKED 질문을 재평가한다(ADR-0007 결정 8).
 * 미답변자가 빠지면 답변 저장 이벤트 없이도 "전원 답변"이 성립할 수 있기 때문이다.
 * <p>
 * <b>이 클래스는 family_groups·users 락을 절대 잡지 않는다.</b> 호출자가 이미 자기 순서대로 잡은 상태에서 부르며,
 * 여기서는 daily_questions 행만 id 오름차순으로 FOR UPDATE 한다. 전역 락 순서(users → family_groups(id 오름차순)
 * → daily_questions(id 오름차순))의 마지막 단계만 맡는 것이다. 여기서 group 락을 추가하면 순서가 깨져 교착이 난다.
 * 호출자는 멤버 상태 변경 후에 불러야 하며(같은 트랜잭션), 이 호출 뒤에 엔티티를 더 만지지 않는 편이 안전하다
 * (벌크 UPDATE가 영속성 컨텍스트를 비운다).
 */
@Component
@RequiredArgsConstructor
public class RevealReevaluator {

    private final DailyQuestionRepository dailyQuestionRepository;
    private final RevealEvaluator revealEvaluator;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void reevaluateGroup(UUID groupId) {
        dailyQuestionRepository.flush(); // 호출자가 바꾼 멤버 상태를 먼저 DB에 반영한다.
        for (UUID dqId : dailyQuestionRepository.findLockedIdsByGroup(groupId)) {
            // 잠근 뒤 상태를 다시 읽는다(evaluateLocked가 상태를 새로 조회한다): 그 사이 다른 쪽이 전환했을 수 있다.
            if (dailyQuestionRepository.findByIdAndGroupIdForUpdate(dqId, groupId).isPresent()) {
                revealEvaluator.evaluateLocked(dqId, groupId, clock.instant());
            }
        }
    }
}
