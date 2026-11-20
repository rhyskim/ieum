package com.ieum.backend.domain.answer.service;

import com.ieum.backend.domain.answer.dto.AnswerDtos.AnswerResponse;
import com.ieum.backend.domain.answer.entity.Answer;
import com.ieum.backend.domain.answer.repository.AnswerRepository;
import com.ieum.backend.domain.common.enums.RevealStatus;
import com.ieum.backend.domain.group.repository.FamilyGroupRepository;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.domain.question.entity.DailyQuestion;
import com.ieum.backend.domain.question.repository.DailyQuestionRepository;
import com.ieum.backend.domain.user.repository.UserRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 텍스트 답변 저장·수정(ADR-0007). 마감은 없다: 지난 질문에도 언제든 답할 수 있다. */
@Service
@RequiredArgsConstructor
public class AnswerService {

    /** 공백 제외 최소 글자 수(수정도 동일). */
    public static final int MIN_TEXT_LENGTH = 30;

    /** 공백 포함 최대 글자 수(ADR-0007). 초과는 INVALID_INPUT(400)으로 거절한다. */
    public static final int MAX_TEXT_LENGTH = 2000;

    private final DailyQuestionRepository dailyQuestionRepository;
    private final AnswerRepository answerRepository;
    private final UserRepository userRepository;
    private final FamilyGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final RevealEvaluator revealEvaluator;
    private final Clock clock;

    @Transactional
    public AnswerResponse save(UUID groupId, UUID dailyQuestionId, UUID userId, String rawText) {
        if (rawText.codePointCount(0, rawText.length()) > MAX_TEXT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "답변은 " + MAX_TEXT_LENGTH + "자까지 쓸 수 있어요.");
        }
        String text = rawText.strip();
        if (countNonWhitespace(text) < MIN_TEXT_LENGTH) {
            throw new BusinessException(ErrorCode.ANSWER_TOO_SHORT);
        }

        DailyQuestion dq = lockForSave(groupId, dailyQuestionId, userId);

        Answer answer = answerRepository.findByDailyQuestion_IdAndUser_Id(dailyQuestionId, userId).orElse(null);
        if (answer == null) {
            answer = answerRepository.saveAndFlush(
                Answer.createTextAnswer(dq, dq.getGroup(), userRepository.getReferenceById(userId), text));
        } else {
            answer.editText(text);
            answerRepository.flush();
        }

        Instant now = clock.instant();
        RevealStatus status = revealEvaluator.evaluateLocked(dailyQuestionId, groupId, now);
        return new AnswerResponse(answer.getId(), dailyQuestionId, answer.getTextContent(), status, answer.getUpdatedAt());
    }

    /**
     * 전역 락 순서 users → family_groups → daily_questions 를 지킨다(ADR-0007 결정 9). 답변 INSERT는 FK 검사로
     * 세 테이블 행에 KEY SHARE를 걸고 이는 FOR UPDATE와 충돌하므로, 어느 하나라도 순서가 뒤집히면
     * leave/remove(group X → dq X)·withdraw(users X → group X → dq X)와 교착이 난다.
     * 1) users(본인) FOR SHARE: 탈퇴 유예로의 전환과 줄을 선다. 잠근 뒤 유예 여부를 판단하므로 경쟁에서 안전하다.
     * 2) family_groups FOR SHARE: 강퇴·이탈·승인과 줄을 선다. 잠근 뒤 유효 멤버 여부를 다시 확인한다.
     * 3) daily_questions FOR UPDATE: 같은 질문의 저장·재평가를 한 줄로 세운다(write skew 방지의 핵심).
     * 앞의 둘은 SHARE라서 저장끼리는 서로 막지 않고, 줄을 서는 건 질문 한 행뿐이다.
     */
    private DailyQuestion lockForSave(UUID groupId, UUID dailyQuestionId, UUID userId) {
        var user = userRepository.findByIdForShare(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_ACCESS_DENIED));
        if (user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.GROUP_ACCESS_DENIED);
        }
        groupRepository.findByIdForShare(groupId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!memberRepository.existsValidActive(groupId, userId)) {
            throw new BusinessException(ErrorCode.GROUP_ACCESS_DENIED);
        }
        return dailyQuestionRepository.findByIdAndGroupIdForUpdate(dailyQuestionId, groupId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static long countNonWhitespace(String text) {
        return text.codePoints()
            .filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
            .count();
    }
}
