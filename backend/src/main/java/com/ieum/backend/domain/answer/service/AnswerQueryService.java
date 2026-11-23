package com.ieum.backend.domain.answer.service;

import com.ieum.backend.domain.answer.dto.AnswerDtos.AnswerListResponse;
import com.ieum.backend.domain.answer.dto.AnswerDtos.AnswerView;
import com.ieum.backend.domain.answer.dto.AnswerDtos.MemberAnswerStatus;
import com.ieum.backend.domain.answer.dto.AnswerDtos.QuestionDetailResponse;
import com.ieum.backend.domain.answer.entity.Answer;
import com.ieum.backend.domain.answer.repository.AnswerRepository;
import com.ieum.backend.domain.common.enums.RevealStatus;
import com.ieum.backend.domain.group.entity.GroupMember;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.domain.group.service.MemberAliasService;
import com.ieum.backend.domain.question.entity.DailyQuestion;
import com.ieum.backend.domain.question.entity.Question;
import com.ieum.backend.domain.question.repository.DailyQuestionRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 질문 상세·답변 목록 조회. 서버 마스킹(ADR-0007 결정 3): 열람 권한이 없는 타인의 답변은 본문을 읽지도 않고
 * 응답에도 담지 않는다. 열람 권한 = 본인 답변 + (REVEALED_ALL, 또는 REVEALED_TIMEOUT이면서 내가 답변을 마침).
 * 조회 시점에 17시가 지났으면 시간 초과 전환을 한다(혼합형). 접근 권한은 컨트롤러의 @GroupMemberOnly가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class AnswerQueryService {

    static final String WITHDRAWN_NAME = "탈퇴한 사용자";

    private final DailyQuestionRepository dailyQuestionRepository;
    private final AnswerRepository answerRepository;
    private final GroupMemberRepository memberRepository;
    private final MemberAliasService aliasService;
    private final RevealEvaluator revealEvaluator;
    private final Clock clock;

    @Transactional
    public QuestionDetailResponse detail(UUID groupId, UUID dailyQuestionId, UUID viewerId) {
        DailyQuestion dq = find(groupId, dailyQuestionId);
        RevealStatus status = revealEvaluator.revealIfTimedOut(dailyQuestionId, clock.instant());
        // 벌크 UPDATE가 영속성 컨텍스트를 비우므로 최신 값은 다시 읽는다.
        DailyQuestion fresh = find(groupId, dailyQuestionId);
        Question q = fresh.getQuestion();

        Set<UUID> answered = Set.copyOf(answerRepository.findAnsweredUserIds(dailyQuestionId));
        Map<UUID, String> aliases = aliasService.aliasesOf(groupId, viewerId);
        List<MemberAnswerStatus> members = memberRepository.findValidActive(groupId).stream()
            .map(m -> new MemberAnswerStatus(
                m.getUser().getId(),
                aliases.getOrDefault(m.getUser().getId(), m.getFamilyNickname()),
                answered.contains(m.getUser().getId()),
                m.getUser().getId().equals(viewerId)))
            .toList();

        return new QuestionDetailResponse(fresh.getId(), groupId, fresh.getCycleDate(), q.getQuestionText(),
            q.getCategory(), q.getTtsGuideText(), q.getFollowUpHint(), status, fresh.getAutoRevealAt(),
            fresh.getRevealedAt(), answered.contains(viewerId), members);
    }

    @Transactional
    public AnswerListResponse answers(UUID groupId, UUID dailyQuestionId, UUID viewerId) {
        find(groupId, dailyQuestionId);
        RevealStatus status = revealEvaluator.revealIfTimedOut(dailyQuestionId, clock.instant());
        boolean iAnswered = answerRepository.findAnsweredUserIds(dailyQuestionId).contains(viewerId);
        boolean canSeeOthers = AnswerVisibility.canSeeOthers(status, iAnswered);

        // 권한이 없으면 타인 행은 조회 자체를 하지 않는다(본문이 메모리에도 올라오지 않는다).
        List<Answer> visible = canSeeOthers
            ? answerRepository.findAllWithUser(dailyQuestionId)
            : answerRepository.findMineWithUser(dailyQuestionId, viewerId);

        Map<UUID, String> names = new HashMap<>();
        for (GroupMember m : memberRepository.findValidActive(groupId)) {
            names.put(m.getUser().getId(), m.getFamilyNickname());
        }
        Map<UUID, String> aliases = aliasService.aliasesOf(groupId, viewerId);

        List<AnswerView> views = visible.stream()
            .map(a -> {
                UUID author = a.getUser().getId();
                String name = aliases.getOrDefault(author, names.getOrDefault(author, WITHDRAWN_NAME));
                return new AnswerView(a.getId(), author, name, author.equals(viewerId), a.getTextContent(), a.getUpdatedAt());
            })
            .toList();
        return new AnswerListResponse(status, iAnswered, views);
    }

    private DailyQuestion find(UUID groupId, UUID dailyQuestionId) {
        return dailyQuestionRepository.findByIdAndGroup_Id(dailyQuestionId, groupId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
