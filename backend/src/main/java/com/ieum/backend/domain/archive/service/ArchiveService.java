package com.ieum.backend.domain.archive.service;

import com.ieum.backend.domain.answer.entity.Answer;
import com.ieum.backend.domain.answer.repository.AnswerRepository;
import com.ieum.backend.domain.answer.service.AnswerVisibility;
import com.ieum.backend.domain.answer.service.RevealEvaluator;
import com.ieum.backend.domain.archive.dto.ArchiveDtos.ArchiveAnswer;
import com.ieum.backend.domain.archive.dto.ArchiveDtos.ArchiveItem;
import com.ieum.backend.domain.archive.dto.ArchiveDtos.ArchivePage;
import com.ieum.backend.domain.common.enums.RevealStatus;
import com.ieum.backend.domain.question.entity.DailyQuestion;
import com.ieum.backend.domain.question.repository.DailyQuestionRepository;
import com.ieum.backend.domain.reaction.dto.ReactionDtos.EmojiReaction;
import com.ieum.backend.domain.reaction.service.DisplayNames;
import com.ieum.backend.domain.reaction.service.ReactionService;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아카이브 타임라인(ADR-0008). cycle_date 내림차순 키셋 페이징이고 커서는 마지막 항목의 (cycle_date, id)다.
 * 마스킹은 답변 조회와 같은 규칙(AnswerVisibility)이며, 열람 권한이 없는 질문에서는 타인 답변 행을 아예 읽지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ArchiveService {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 30;

    private final DailyQuestionRepository dailyQuestionRepository;
    private final AnswerRepository answerRepository;
    private final ReactionService reactionService;
    private final DisplayNames displayNames;
    private final RevealEvaluator revealEvaluator;
    private final Clock clock;

    @Transactional
    public ArchivePage timeline(UUID groupId, UUID viewerId, String cursor, Integer requestedSize) {
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Pageable limit = Pageable.ofSize(size + 1);
        List<DailyQuestion> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = dailyQuestionRepository.findArchiveFirstPage(groupId, limit);
        } else {
            Cursor c = Cursor.decode(cursor);
            rows = dailyQuestionRepository.findArchiveAfter(groupId, c.cycleDate(), c.id(), limit);
        }
        boolean hasNext = rows.size() > size;
        List<DailyQuestion> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext ? Cursor.encode(page.get(page.size() - 1)) : null;
        return new ArchivePage(build(groupId, viewerId, page), nextCursor);
    }

    private List<ArchiveItem> build(UUID groupId, UUID viewerId, List<DailyQuestion> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        // 1) 공개 상태: LOCKED인 질문만 지연 전환(조건부 UPDATE)을 시도한다. 벌크 UPDATE가 영속성 컨텍스트를 비우므로
        //    질문·본문 정보는 먼저 값으로 꺼내 둔다.
        //    전환 UPDATE는 행 락을 커밋까지 쥐므로 daily_questions는 id 오름차순으로 잠근다(ADR-0007 결정 9).
        Map<UUID, RevealStatus> switched = new HashMap<>();
        page.stream().filter(d -> d.getRevealStatus() == RevealStatus.LOCKED).map(DailyQuestion::getId).sorted()
            .forEach(id -> switched.put(id, revealEvaluator.revealIfTimedOut(id, clock.instant())));
        Map<UUID, RevealStatus> statuses = new LinkedHashMap<>();
        for (DailyQuestion d : page) {
            statuses.put(d.getId(), switched.getOrDefault(d.getId(), d.getRevealStatus()));
        }
        List<UUID> dqIds = new ArrayList<>(statuses.keySet());

        // 2) 내가 답한 질문, 열람 권한에 따라 읽을 답변 범위를 가른다.
        Set<UUID> answeredByMe = new HashSet<>(answerRepository.findAnsweredDqIds(dqIds, viewerId));
        List<UUID> allVisible = new ArrayList<>();
        List<UUID> mineOnly = new ArrayList<>();
        for (UUID id : dqIds) {
            (AnswerVisibility.canSeeOthers(statuses.get(id), answeredByMe.contains(id)) ? allVisible : mineOnly).add(id);
        }
        List<Answer> answers = new ArrayList<>();
        if (!allVisible.isEmpty()) {
            answers.addAll(answerRepository.findAllWithUserByDqIds(allVisible));
        }
        if (!mineOnly.isEmpty()) {
            answers.addAll(answerRepository.findMineWithUserByDqIds(mineOnly, viewerId));
        }

        // 3) 리액션 집계(열람 가능한 답변만)와 표기 이름.
        Map<UUID, List<EmojiReaction>> reactions = reactionService.summaries(
            answers.stream().map(Answer::getId).toList(), viewerId);
        Function<UUID, String> names = displayNames.forViewer(groupId, viewerId);

        Map<UUID, List<ArchiveAnswer>> byDq = new HashMap<>();
        for (Answer a : answers) {
            UUID author = a.getUser().getId();
            byDq.computeIfAbsent(a.getDailyQuestion().getId(), k -> new ArrayList<>())
                .add(new ArchiveAnswer(a.getId(), author, names.apply(author), author.equals(viewerId),
                    a.getTextContent(), a.getUpdatedAt(), reactions.get(a.getId())));
        }

        return page.stream()
            .map(d -> new ArchiveItem(d.getId(), d.getCycleDate(), d.getQuestion().getQuestionText(),
                d.getQuestion().getCategory(), statuses.get(d.getId()), answeredByMe.contains(d.getId()),
                byDq.getOrDefault(d.getId(), List.of())))
            .toList();
    }

    /** 불투명 커서: "cycleDate|id"를 URL-safe Base64로 감싼다. 형식이 틀리면 INVALID_INPUT. */
    record Cursor(LocalDate cycleDate, UUID id) {

        static String encode(DailyQuestion last) {
            String raw = last.getCycleDate() + "|" + last.getId();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException();
                }
                return new Cursor(LocalDate.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }
    }
}
