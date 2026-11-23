package com.ieum.backend.domain.reaction.service;

import com.ieum.backend.domain.answer.entity.Answer;
import com.ieum.backend.domain.answer.repository.AnswerRepository;
import com.ieum.backend.domain.answer.service.AnswerVisibility;
import com.ieum.backend.domain.answer.service.RevealEvaluator;
import com.ieum.backend.domain.common.enums.EmojiCode;
import com.ieum.backend.domain.common.enums.RevealStatus;
import com.ieum.backend.domain.group.repository.FamilyGroupRepository;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.domain.reaction.dto.ReactionDtos.EmojiReaction;
import com.ieum.backend.domain.reaction.dto.ReactionDtos.EmojiReactors;
import com.ieum.backend.domain.reaction.dto.ReactionDtos.ReactionListResponse;
import com.ieum.backend.domain.reaction.dto.ReactionDtos.ReactionSummary;
import com.ieum.backend.domain.reaction.dto.ReactionDtos.Reactor;
import com.ieum.backend.domain.reaction.repository.AnswerReactionRepository;
import com.ieum.backend.domain.reaction.repository.AnswerReactionRepository.CountRow;
import com.ieum.backend.domain.reaction.repository.AnswerReactionRepository.MineRow;
import com.ieum.backend.domain.user.repository.UserRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이모지 리액션 달기·취소·목록(ADR-0008). 달기는 멱등 PUT(ON CONFLICT DO NOTHING), 취소는 멱등 DELETE.
 * 권한은 서버가 판단한다: 유효 멤버 + 공개된 답변 + (타임아웃 공개라면) 내가 답변을 마친 경우(AnswerVisibility).
 */
@Service
@RequiredArgsConstructor
public class ReactionService {

    private final AnswerRepository answerRepository;
    private final AnswerReactionRepository reactionRepository;
    private final UserRepository userRepository;
    private final FamilyGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final RevealEvaluator revealEvaluator;
    private final DisplayNames displayNames;
    private final Clock clock;

    /**
     * 락 순서 users → family_groups → (daily_questions) → answers 를 지킨다(ADR-0007 결정 9). 리액션 INSERT의 FK 검사는
     * users·answers 행에 KEY SHARE를 걸 뿐 daily_questions는 참조하지 않으므로 답변 저장의 dq FOR UPDATE와 서로 막지 않는다.
     * 1) users(본인) FOR SHARE: 탈퇴 유예 전환과 줄을 서고, 잠근 뒤 유예 여부를 판단한다.
     * 2) family_groups FOR SHARE: 강퇴·이탈과 줄을 서고, 잠근 뒤 유효 멤버 여부를 다시 확인한다.
     * 3) 지연 전환(조건부 UPDATE)이 dq 행을 잠글 수 있는데, 앞의 두 락 뒤이므로 순서를 거스르지 않는다.
     * 4) INSERT가 answers 행에 KEY SHARE: 답변 삭제(파기)는 users 락을 먼저 잡으므로 순서가 같다.
     */
    @Transactional
    public ReactionSummary add(UUID groupId, UUID answerId, UUID userId, EmojiCode emoji) {
        lockMember(groupId, userId);
        Answer answer = findAnswer(groupId, answerId);
        requireVisible(answer, userId);
        reactionRepository.insertIfAbsent(answerId, userId, emoji.name());
        return summary(answerId, userId);
    }

    /**
     * 취소는 없으면 0행이라 그대로 성공한다. 응답에 집계가 담기므로 달기와 같은 열람 권한을 확인한다.
     * 멤버 여부는 컨트롤러가 확인하고, 삭제는 새 FK 락을 걸지 않아 lockMember는 생략한다.
     */
    @Transactional
    public ReactionSummary remove(UUID groupId, UUID answerId, UUID userId, EmojiCode emoji) {
        requireVisible(findAnswer(groupId, answerId), userId);
        reactionRepository.deleteEmoji(answerId, userId, emoji);
        return summary(answerId, userId);
    }

    @Transactional
    public ReactionListResponse list(UUID groupId, UUID answerId, UUID viewerId) {
        Answer answer = findAnswer(groupId, answerId);
        requireVisible(answer, viewerId);
        Function<UUID, String> names = displayNames.forViewer(groupId, viewerId);
        Map<EmojiCode, List<Reactor>> byEmoji = new EnumMap<>(EmojiCode.class);
        reactionRepository.findEmojiByAnswer(answerId).forEach(r -> byEmoji
            .computeIfAbsent(r.getEmojiCode(), k -> new ArrayList<>())
            .add(new Reactor(r.getUser().getId(), names.apply(r.getUser().getId()), r.getUser().getId().equals(viewerId))));
        List<EmojiReactors> result = Arrays.stream(EmojiCode.values())
            .map(code -> {
                List<Reactor> reactors = byEmoji.getOrDefault(code, List.of());
                return new EmojiReactors(code, reactors.size(), reactors.stream().anyMatch(Reactor::mine), reactors);
            })
            .toList();
        return new ReactionListResponse(answerId, result);
    }

    /** 타임라인과 같은 집계 규칙: 5종 모두, 종류별 개수와 내가 단 것. 호출자가 열람 권한을 이미 확인한 답변만 넘긴다. */
    public Map<UUID, List<EmojiReaction>> summaries(Collection<UUID> answerIds, UUID viewerId) {
        Map<UUID, Map<EmojiCode, Integer>> counts = new HashMap<>();
        Map<UUID, Set<EmojiCode>> mine = new HashMap<>();
        if (!answerIds.isEmpty()) {
            for (CountRow row : reactionRepository.countByAnswers(answerIds)) {
                counts.computeIfAbsent(row.getAnswerId(), k -> new EnumMap<>(EmojiCode.class))
                    .put(row.getEmojiCode(), (int) row.getCnt());
            }
            for (MineRow row : reactionRepository.findMine(answerIds, viewerId)) {
                mine.computeIfAbsent(row.getAnswerId(), k -> EnumSet.noneOf(EmojiCode.class)).add(row.getEmojiCode());
            }
        }
        Map<UUID, List<EmojiReaction>> result = new HashMap<>();
        for (UUID id : answerIds) {
            Map<EmojiCode, Integer> c = counts.getOrDefault(id, Map.of());
            Set<EmojiCode> m = mine.getOrDefault(id, Set.of());
            result.put(id, Arrays.stream(EmojiCode.values())
                .map(code -> new EmojiReaction(code, c.getOrDefault(code, 0), m.contains(code)))
                .toList());
        }
        return result;
    }

    private ReactionSummary summary(UUID answerId, UUID viewerId) {
        return new ReactionSummary(answerId, summaries(List.of(answerId), viewerId).get(answerId));
    }

    private void lockMember(UUID groupId, UUID userId) {
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
    }

    private Answer findAnswer(UUID groupId, UUID answerId) {
        return answerRepository.findByIdAndGroup_Id(answerId, groupId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 공개 전(LOCKED)이면 본인 답변이어도 거절한다. 그 외에는 답변 본문 열람 규칙과 같다. */
    private void requireVisible(Answer answer, UUID viewerId) {
        // 지연 전환의 벌크 UPDATE가 영속성 컨텍스트를 비우므로 식별자는 그 전에 꺼내 둔다.
        UUID dqId = answer.getDailyQuestion().getId();
        boolean mine = answer.getUser().getId().equals(viewerId);
        RevealStatus status = revealEvaluator.revealIfTimedOut(dqId, clock.instant());
        boolean viewerAnswered = answerRepository.existsByDailyQuestion_IdAndUser_Id(dqId, viewerId);
        if (status == RevealStatus.LOCKED || !(mine || AnswerVisibility.canSeeOthers(status, viewerAnswered))) {
            throw new BusinessException(ErrorCode.ANSWER_NOT_VISIBLE);
        }
    }
}
