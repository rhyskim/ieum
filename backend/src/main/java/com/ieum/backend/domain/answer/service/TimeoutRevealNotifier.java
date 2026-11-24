package com.ieum.backend.domain.answer.service;

import com.ieum.backend.domain.answer.repository.AnswerRepository;
import com.ieum.backend.domain.group.entity.GroupMember;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.domain.notification.event.GroupPushEvent;
import com.ieum.backend.domain.notification.event.GroupPushEvent.Type;
import com.ieum.backend.domain.reaction.service.DisplayNames;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 17시 시간 초과 공개 푸시를 수신자별 문구로 만들어 발행한다(ADR-0004 결정 3). 전환이 1행일 때만 호출되고,
 * 조회 쿼리만 쓰므로 추가 락을 잡지 않는다. 문구에는 호칭만 들어가고 답변 내용은 들어가지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TimeoutRevealNotifier {

    static final int MAX_NAMES = 3;
    static final int MAX_NAME_CODEPOINTS = 10;
    static final String GENERIC_BODY = "가족의 답변이 공개됐어요";

    private final GroupMemberRepository memberRepository;
    private final AnswerRepository answerRepository;
    private final DisplayNames displayNames;
    private final ApplicationEventPublisher publisher;

    public void notifyRevealed(UUID dqId, UUID groupId) {
        List<GroupMember> members = memberRepository.findValidActive(groupId);
        List<UUID> answered = answerRepository.findAnsweredUserIdsInOrder(dqId);
        Map<UUID, GroupMember> validById = new LinkedHashMap<>();
        members.forEach(m -> validById.put(m.getUser().getId(), m));
        // 유효 멤버인 답변자만 호칭에 쓴다(탈퇴·이탈한 사람의 이름을 알림에 싣지 않는다).
        List<UUID> validAnswerers = answered.stream().filter(validById::containsKey).distinct().toList();

        for (GroupMember recipient : members) {
            UUID recipientId = recipient.getUser().getId();
            Function<UUID, String> nameOf = displayNames.forViewer(groupId, recipientId);
            String body = answered.contains(recipientId)
                ? bodyForAnswered(validAnswerers, recipientId, nameOf)
                : bodyForUnanswered(validAnswerers, recipient.getFamilyNickname(), nameOf);
            publisher.publishEvent(new GroupPushEvent(Type.REVEALED_TIMEOUT, groupId, recipientId, body));
        }
    }

    private static String bodyForAnswered(List<UUID> answerers, UUID self, Function<UUID, String> nameOf) {
        return answerers.stream().filter(id -> !id.equals(self)).findFirst()
            .map(id -> sanitize(nameOf.apply(id)) + "님은 오늘의 질문에 어떤 답을 했을까요?")
            .orElse(GENERIC_BODY);
    }

    private static String bodyForUnanswered(List<UUID> answerers, String selfName, Function<UUID, String> nameOf) {
        if (answerers.isEmpty()) {
            return GENERIC_BODY;
        }
        String names = answerers.stream().limit(MAX_NAMES).map(id -> sanitize(nameOf.apply(id)))
            .collect(java.util.stream.Collectors.joining(", "));
        if (answerers.size() > MAX_NAMES) {
            names += " 외 " + (answerers.size() - MAX_NAMES) + "명";
        }
        return names + "님은 오늘의 질문에 답을 남겼어요. " + sanitize(selfName) + "님도 답을 남겨보시는 게 어떨까요?";
    }

    /** 줄바꿈·제어 문자를 공백으로 줄이고 길이를 제한한다. 호칭은 사용자 입력이다. */
    static String sanitize(String name) {
        String s = name == null ? "" : name.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (s.isEmpty()) {
            return "가족";
        }
        if (s.codePointCount(0, s.length()) > MAX_NAME_CODEPOINTS) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX_NAME_CODEPOINTS)) + "…";
        }
        return s;
    }
}
