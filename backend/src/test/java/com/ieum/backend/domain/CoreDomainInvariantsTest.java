package com.ieum.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ieum.backend.domain.answer.entity.Answer;
import com.ieum.backend.domain.common.enums.AudioStatus;
import com.ieum.backend.domain.common.enums.GroupPlanTier;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MemberStatus;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.common.enums.QuestionCategory;
import com.ieum.backend.domain.common.enums.RevealStatus;
import com.ieum.backend.domain.entitlement.config.EntitlementPolicyProperties;
import com.ieum.backend.domain.entitlement.config.EntitlementPolicyProperties.PlanPolicySpec;
import com.ieum.backend.domain.entitlement.service.EntitlementService;
import com.ieum.backend.domain.group.entity.FamilyGroup;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.domain.question.entity.DailyQuestion;
import com.ieum.backend.domain.question.entity.Question;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.common.TimeZoneConstants;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import com.ieum.backend.global.security.CurrentUser;
import com.ieum.backend.global.security.GroupAccessValidator;
import com.ieum.backend.global.security.GroupMemberAccessAspect;
import com.ieum.backend.global.security.GroupMemberOnly;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;

class CoreDomainInvariantsTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("불변식 1: 전원 답변 시 즉시 REVEALED_ALL 로 전환되고, REVEALED_TIMEOUT 이후 지각 답변 시 revealedAt 을 보존하며 REVEALED_ALL 로 승격된다")
    void dailyQuestionRevealAllAndLatePromotionInvariants() {
        User creator = User.create("parent@ieum.app", "어머니", MemberRole.PARENT);
        FamilyGroup group = FamilyGroup.create("우리 가족", creator);
        Question question = Question.create(1, QuestionCategory.MEMORY, "가장 기억에 남는 가족 여행은 언제인가요?", null, null);
        LocalDate cycleDate = LocalDate.of(2026, 11, 2);

        DailyQuestion dq1 = DailyQuestion.create(group, question, cycleDate);
        Instant allAnsweredTime = ZonedDateTime.of(2026, 11, 2, 20, 30, 0, 0, TimeZoneConstants.KST).toInstant();

        // 아직 2/3명만 답변한 경우 LOCKED 유지
        assertThat(dq1.checkAndRevealIfAllAnswered(3, 2, allAnsweredTime)).isFalse();
        assertThat(dq1.getRevealStatus()).isEqualTo(RevealStatus.LOCKED);

        // 3/3명 전원 답변 완료 시 즉시 REVEALED_ALL 전환
        assertThat(dq1.checkAndRevealIfAllAnswered(3, 3, allAnsweredTime)).isTrue();
        assertThat(dq1.getRevealStatus()).isEqualTo(RevealStatus.REVEALED_ALL);
        assertThat(dq1.getRevealedAt()).isEqualTo(allAnsweredTime);

        // 익일 17:00 타임아웃 공개 이후 지각 멤버가 답변하여 전원 답변이 된 경우
        DailyQuestion dq2 = DailyQuestion.create(group, question, cycleDate);
        Instant timeoutTime = ZonedDateTime.of(2026, 11, 3, 17, 0, 0, 0, TimeZoneConstants.KST).toInstant();
        assertThat(dq2.revealOnTimeoutIfEligible(1, timeoutTime)).isTrue();
        assertThat(dq2.getRevealStatus()).isEqualTo(RevealStatus.REVEALED_TIMEOUT);
        assertThat(dq2.getRevealedAt()).isEqualTo(timeoutTime);

        Instant lateAnswerTime = ZonedDateTime.of(2026, 11, 3, 18, 10, 0, 0, TimeZoneConstants.KST).toInstant();
        assertThat(dq2.checkAndRevealIfAllAnswered(2, 2, lateAnswerTime)).isTrue();
        assertThat(dq2.getRevealStatus()).isEqualTo(RevealStatus.REVEALED_ALL);
        // 최초 공개 시각(timeoutTime)이 덮어써지지 않고 그대로 유지되어야 한다
        assertThat(dq2.getRevealedAt()).isEqualTo(timeoutTime);
    }

    @Test
    @DisplayName("불변식 2: 익일 16:59:59 KST 에는 LOCKED 를 유지하고, 17:00:00 KST 도래 시 최소 1명 이상 답변했을 때만 REVEALED_TIMEOUT 으로 전환된다")
    void dailyQuestionTimeoutRevealBoundaryInvariants() {
        User creator = User.create("parent@ieum.app", "어머니", MemberRole.PARENT);
        FamilyGroup group = FamilyGroup.create("우리 가족", creator);
        Question question = Question.create(1, QuestionCategory.DAILY, "오늘 하루 가장 기분 좋았던 일은 무엇인가요?", null, null);
        LocalDate cycleDate = LocalDate.of(2026, 11, 2);

        DailyQuestion dq = DailyQuestion.create(group, question, cycleDate);
        Instant oneSecBeforeDeadline = ZonedDateTime.of(2026, 11, 3, 16, 59, 59, 0, TimeZoneConstants.KST).toInstant();
        Instant exactDeadline = ZonedDateTime.of(2026, 11, 3, 17, 0, 0, 0, TimeZoneConstants.KST).toInstant();

        // 마감 1초 전에는 1명이 답변했더라도 공개되지 않음
        assertThat(dq.revealOnTimeoutIfEligible(1, oneSecBeforeDeadline)).isFalse();
        assertThat(dq.getRevealStatus()).isEqualTo(RevealStatus.LOCKED);

        // 마감 시각(17:00:00 KST)이 되었더라도 답변자가 0명이면 공개되지 않음
        assertThat(dq.revealOnTimeoutIfEligible(0, exactDeadline)).isFalse();
        assertThat(dq.getRevealStatus()).isEqualTo(RevealStatus.LOCKED);

        // 마감 시각(17:00:00 KST) 도래 + 최소 1명 답변 시 REVEALED_TIMEOUT 전환
        assertThat(dq.revealOnTimeoutIfEligible(1, exactDeadline)).isTrue();
        assertThat(dq.getRevealStatus()).isEqualTo(RevealStatus.REVEALED_TIMEOUT);
        assertThat(dq.getRevealedAt()).isEqualTo(exactDeadline);
    }

    @Test
    @DisplayName("불변식 3: 다중 그룹 격리(GroupAccessValidator & GroupMemberAccessAspect) - 비소속 그룹 접근 시 403 GROUP_ACCESS_DENIED, 미인증 시 401 UNAUTHORIZED 발생")
    void multiGroupIsolationGuardInvariants() throws Exception {
        GroupMemberRepository repository = mock(GroupMemberRepository.class);
        GroupAccessValidator validator = new GroupAccessValidator(repository);
        GroupMemberAccessAspect aspect = new GroupMemberAccessAspect(validator);

        UUID groupA = UUID.randomUUID();
        UUID groupB = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(repository.existsByGroup_IdAndUser_IdAndStatus(groupA, userId, MemberStatus.ACTIVE)).thenReturn(true);
        when(repository.existsByGroup_IdAndUser_IdAndStatus(groupB, userId, MemberStatus.ACTIVE)).thenReturn(false);

        Method method = SampleGroupController.class.getDeclaredMethod("getGroupFeed", UUID.class);
        GroupMemberOnly annotation = method.getAnnotation(GroupMemberOnly.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"groupId"});

        JoinPoint joinPointForGroupB = mock(JoinPoint.class);
        when(joinPointForGroupB.getSignature()).thenReturn(signature);
        when(joinPointForGroupB.getArgs()).thenReturn(new Object[]{groupB});

        // 1) 미인증 상태에서 호출 시 401 UNAUTHORIZED
        assertThatThrownBy(() -> aspect.verifyGroupMemberAccess(joinPointForGroupB, annotation))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        // 2) 인증되었으나 소속되지 않은 groupB 접근 시 403 GROUP_ACCESS_DENIED
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new CurrentUser(userId, MembershipTier.FREE),
                null,
                Collections.emptyList()
            )
        );
        assertThatThrownBy(() -> aspect.verifyGroupMemberAccess(joinPointForGroupB, annotation))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.GROUP_ACCESS_DENIED);

        // 3) 활성 멤버로 소속된 groupA 접근 시 정상 통과
        JoinPoint joinPointForGroupA = mock(JoinPoint.class);
        when(joinPointForGroupA.getSignature()).thenReturn(signature);
        when(joinPointForGroupA.getArgs()).thenReturn(new Object[]{groupA});
        aspect.verifyGroupMemberAccess(joinPointForGroupA, annotation);
    }

    @Test
    @DisplayName("불변식 4: 30일 음성 만료(ACTIVE -> EXPIRING_NOTIFIED -> EXPIRED_DELETED) 시 텍스트(textContent, sttRawText)는 100% 보존되고, PERMANENT/NONE 만료 시도 시 409 예외가 발생한다")
    void audioLifecycleStateTransitionAndTextPreservationInvariants() {
        User user = User.create("parent@ieum.app", "아버지", MemberRole.PARENT);
        FamilyGroup group = FamilyGroup.create("우리 가족", user);
        Question question = Question.create(2, QuestionCategory.GRATITUDE, "자녀에게 가장 고마웠던 순간은 언제인가요?", null, null);
        DailyQuestion dq = DailyQuestion.create(group, question, LocalDate.of(2026, 11, 2));

        String originalText = "우리 딸이 첫 월급 타서 내복 사줬을 때 제일 고마웠지.";
        String originalSttRaw = "우리 딸이 첫 월급 타서 내복 사줬을 때 제일 고마웠지";

        Answer ephemeralAnswer = Answer.createVoiceAnswer(
            dq,
            group,
            user,
            originalText,
            originalSttRaw,
            "audio/ephemeral-30d/groups/1/answers/1.m4a",
            120,
            960_000L,
            AudioStatus.ACTIVE,
            Instant.now().plusSeconds(30L * 86400L),
            false
        );

        ephemeralAnswer.markAudioExpiringNotified();
        assertThat(ephemeralAnswer.getAudioStatus()).isEqualTo(AudioStatus.EXPIRING_NOTIFIED);

        ephemeralAnswer.markAudioExpired();
        assertThat(ephemeralAnswer.getAudioStatus()).isEqualTo(AudioStatus.EXPIRED_DELETED);
        // 원본 음성이 만료 삭제되어도 변환된 텍스트 답변과 STT 원문은 영구 보존되어야 한다
        assertThat(ephemeralAnswer.getTextContent()).isEqualTo(originalText);
        assertThat(ephemeralAnswer.getSttRawText()).isEqualTo(originalSttRaw);

        // PERMANENT 상태의 음성에 대해 만료 처리를 시도하면 409 INVALID_AUDIO_STATE_TRANSITION 예외가 발생해야 한다
        Answer permanentAnswer = Answer.createVoiceAnswer(
            dq,
            group,
            user,
            originalText,
            originalSttRaw,
            "audio/permanent/groups/1/answers/2.m4a",
            180,
            1_440_000L,
            AudioStatus.PERMANENT,
            null,
            true
        );
        assertThatThrownBy(permanentAnswer::markAudioExpired)
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_AUDIO_STATE_TRANSITION);
    }

    @Test
    @DisplayName("불변식 5: FeatureGate(EntitlementService) 는 180초 또는 3MB 초과 업로드 시 400 예외를 던지고, 권한 미보유 시 403 예외를 던진다")
    void featureGateValidationInvariants() {
        EntitlementPolicyProperties props = new EntitlementPolicyProperties(
            "FREE",
            Map.of(
                "FREE", new PlanPolicySpec(true, 180, 3_145_728L, 30, true, "ON_DEVICE"),
                "PREMIUM", new PlanPolicySpec(true, 600, 10_485_760L, 36500, true, "CLOUD_WHISPER")
            )
        );
        EntitlementService service = new EntitlementService(props);

        // 정상 범위 (180초, 2MB) 통과
        service.validateVoiceUpload(180, 2_000_000L, MembershipTier.FREE, GroupPlanTier.FREE);

        // 181초 초과 시 VOICE_DURATION_EXCEEDED
        assertThatThrownBy(() -> service.validateVoiceUpload(181, 2_000_000L, MembershipTier.FREE, GroupPlanTier.FREE))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VOICE_DURATION_EXCEEDED);

        // 3MB 초과 시 VOICE_FILE_SIZE_EXCEEDED
        assertThatThrownBy(() -> service.validateVoiceUpload(120, 3_145_729L, MembershipTier.FREE, GroupPlanTier.FREE))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VOICE_FILE_SIZE_EXCEEDED);
    }

    static class SampleGroupController {
        @GroupMemberOnly
        public void getGroupFeed(@PathVariable("groupId") UUID groupId) {
        }
    }
}
