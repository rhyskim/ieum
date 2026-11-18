package com.ieum.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.backend.domain.account.apple.AppleRevocationService;
import com.ieum.backend.domain.account.apple.AppleTokenRevoker;
import com.ieum.backend.domain.account.purge.AccountPurgeService;
import com.ieum.backend.domain.account.purge.AccountPurgeService.Outcome;
import com.ieum.backend.domain.account.storage.AudioObjectStorage;
import com.ieum.backend.domain.auth.service.AccountService;
import com.ieum.backend.domain.auth.social.AppleTokenVerifier;
import com.ieum.backend.domain.auth.social.KakaoTokenVerifier;
import com.ieum.backend.domain.auth.social.NaverTokenVerifier;
import com.ieum.backend.domain.auth.social.SocialProfile;
import com.ieum.backend.domain.common.enums.ContentChoice;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 파기 배치(ADR-0002 결정 1·9·10·14)와 Apple 해지 큐(결정 13)의 수용 기준 테스트. S3와 Apple 호출은 대역으로 바꾼다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AccountPurgeIntegrationTest {

    private static final String ANON = "탈퇴한 사용자";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private AccountPurgeService purgeService;
    @Autowired
    private AppleRevocationService appleService;
    @Autowired
    private AccountService accountService;
    @PersistenceContext
    private EntityManager em;

    @MockBean
    private AudioObjectStorage storage;
    @MockBean
    private AppleTokenRevoker revoker;
    @MockBean
    private KakaoTokenVerifier kakao;
    @MockBean
    private NaverTokenVerifier naver;
    @MockBean
    private AppleTokenVerifier appleVerifier;

    @BeforeEach
    void cleanAppleQueue() {
        // 테스트끼리 남긴 해지 큐 행이 다른 테스트의 runDue 집계에 섞이지 않게 한다.
        jdbc.update("delete from apple_revocations");
    }

    // ---------- helpers ----------

    private UUID newUser(String name) {
        return tx.execute(s -> {
            User user = User.create(name + "@test.dev", name, MemberRole.CHILD);
            em.persist(user);
            return user.getId();
        });
    }

    /** 탈퇴 후 daysAgo일이 지난 사용자. 로그인 수단·세션·기기 토큰을 갖고, 휴대폰 번호도 있다. */
    private UUID withdrawnUser(int daysAgo, String choice) {
        UUID user = newUser("탈퇴자" + UUID.randomUUID().toString().substring(0, 4));
        jdbc.update("update users set phone_number = '010-1234-5678', deleted_at = now() - make_interval(days => ?), "
            + "withdraw_content_choice = ? where id = ?", daysAgo, choice, user);
        jdbc.update("insert into user_identities (user_id, provider, subject) values (?, 'KAKAO', ?)",
            user, "k-" + UUID.randomUUID());
        jdbc.update("insert into refresh_tokens (user_id, family_id, token_hash, expires_at) "
            + "values (?, gen_random_uuid(), ?, now() + interval '60 days')", user, "hash-" + UUID.randomUUID());
        jdbc.update("insert into user_devices (user_id, fcm_token, device_os) values (?, ?, 'ANDROID')",
            user, "fcm-" + UUID.randomUUID());
        return user;
    }

    private UUID newGroup(UUID owner) {
        UUID groupId = jdbc.queryForObject(
            "insert into family_groups (name, creator_id) values ('가족', ?) returning id", UUID.class, owner);
        jdbc.update(
            "insert into group_members (group_id, user_id, member_role, family_nickname, status, is_owner, joined_at) "
                + "values (?, ?, 'PARENT', '방장', 'ACTIVE', true, now() - interval '100 days')", groupId, owner);
        return groupId;
    }

    private void addMember(UUID groupId, UUID user, String status, boolean owner) {
        jdbc.update(
            "insert into group_members (group_id, user_id, member_role, family_nickname, target_call_phone, status, "
                + "is_owner, joined_at, requested_at) values (?, ?, 'CHILD', '우리 아들', '010-9999-0000', ?, ?, now(), now())",
            groupId, user, status, owner);
    }

    private UUID addValidMember(UUID groupId) {
        UUID user = newUser("m" + UUID.randomUUID().toString().substring(0, 6));
        addMember(groupId, user, "ACTIVE", false);
        return user;
    }

    private UUID newDailyQuestion(UUID groupId, int dayOffset) {
        Long questionId = jdbc.queryForObject(
            "insert into questions (sequence_order, category, question_text) "
                + "values ((select coalesce(max(sequence_order), 0) + 1 from questions), 'DAILY', '질문') returning id",
            Long.class);
        return jdbc.queryForObject(
            "insert into daily_questions (group_id, question_id, cycle_date, assigned_at, auto_reveal_at) "
                + "values (?, ?, current_date + ?, now(), now()) returning id", UUID.class, groupId, questionId, dayOffset);
    }

    private UUID addAnswer(UUID groupId, UUID user, int dayOffset, String audioKey) {
        UUID dq = newDailyQuestion(groupId, dayOffset);
        return jdbc.queryForObject(
            "insert into answers (daily_question_id, group_id, user_id, text_content, input_method, audio_object_key) "
                + "values (?, ?, ?, '답변', 'TEXT', ?) returning id", UUID.class, dq, groupId, user, audioKey);
    }

    private UUID addReaction(UUID answerId, UUID user, String voiceKey) {
        return jdbc.queryForObject(
            "insert into answer_reactions (answer_id, user_id, reaction_type, voice_object_key) "
                + "values (?, ?, 'EMOJI', ?) returning id", UUID.class, answerId, user, voiceKey);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private boolean purged(UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select purged_at is not null from users where id = ?", Boolean.class, user));
    }

    // ---------- 수용 기준: 31일 ----------

    @Test
    @DisplayName("탈퇴 후 31일 미만은 파기 대상이 아니다")
    void under31DaysIsNotPurged() {
        UUID user = withdrawnUser(30, "KEEP");

        AccountPurgeService.Summary summary = purgeService.runBatch();

        assertThat(summary.purged()).isZero();
        assertThat(purged(user)).isFalse();
        assertThat(count("select count(*) from user_identities where user_id = ?", user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from users where id = ?", String.class, user)).isNotEqualTo(ANON);
        assertThat(purgeService.purgeOne(user)).isEqualTo(Outcome.SKIPPED); // 직접 호출해도 31일 조건이 막는다
    }

    @Test
    @DisplayName("31일 이상이면 익명화된다: 이름·이메일·전화 제거, 로그인 수단·세션·기기 토큰 삭제, 행은 PURGED로 남는다")
    void after31DaysIsAnonymized() {
        UUID viewer = newUser("보는사람");
        UUID user = withdrawnUser(32, "KEEP");
        UUID groupId = newGroup(viewer);
        addMember(groupId, user, "ACTIVE", false);
        // 내가 보는 사람인 호칭은 삭제되고, 타인이 나를 부르던 호칭(target=나)은 유지된다.
        jdbc.update("insert into member_aliases (group_id, viewer_id, target_id, alias) values (?, ?, ?, '내가 부르는 호칭')",
            groupId, user, viewer);
        jdbc.update("insert into member_aliases (group_id, viewer_id, target_id, alias) values (?, ?, ?, '할머니')",
            groupId, viewer, user);

        AccountPurgeService.Summary summary = purgeService.runBatch();

        assertThat(summary.purged()).isGreaterThanOrEqualTo(1);
        assertThat(purged(user)).isTrue();
        assertThat(jdbc.queryForObject("select name from users where id = ?", String.class, user)).isEqualTo(ANON);
        assertThat(jdbc.queryForObject("select email from users where id = ?", String.class, user)).isNull();
        assertThat(jdbc.queryForObject("select phone_number from users where id = ?", String.class, user)).isNull();
        assertThat(count("select count(*) from users where id = ? and deleted_at is not null", user)).isEqualTo(1);
        assertThat(count("select count(*) from user_identities where user_id = ?", user)).isZero();
        assertThat(count("select count(*) from refresh_tokens where user_id = ?", user)).isZero();
        assertThat(count("select count(*) from user_devices where user_id = ?", user)).isZero();
        assertThat(jdbc.queryForObject(
            "select family_nickname from group_members where group_id = ? and user_id = ?", String.class, groupId, user))
            .isEqualTo(ANON);
        assertThat(jdbc.queryForObject(
            "select target_call_phone from group_members where group_id = ? and user_id = ?", String.class, groupId, user))
            .isNull();
        assertThat(count("select count(*) from member_aliases where viewer_id = ?", user)).isZero();
        assertThat(jdbc.queryForObject(
            "select alias from member_aliases where group_id = ? and viewer_id = ? and target_id = ?",
            String.class, groupId, viewer, user)).isEqualTo("할머니");
    }

    @Test
    @DisplayName("파기 후 같은 소셜 계정으로 새로 가입할 수 있고 이전 데이터와 연결되지 않는다")
    void sameSocialAccountCanSignUpAgainAfterPurge() throws Exception {
        UUID oldUser = withdrawnUser(40, "KEEP");
        String subject = jdbc.queryForObject("select subject from user_identities where user_id = ?", String.class, oldUser);
        assertThat(purgeService.purgeOne(oldUser)).isEqualTo(Outcome.PURGED);

        String token = "kakao-" + UUID.randomUUID();
        when(kakao.provider()).thenReturn(OAuthProvider.KAKAO);
        when(kakao.verify(token)).thenReturn(new SocialProfile(subject, null, "새로 가입"));
        MvcResult r = mockMvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.path("newUser").asBoolean()).isTrue();
        UUID newUserId = jdbc.queryForObject("select user_id from user_identities where subject = ?", UUID.class, subject);
        assertThat(newUserId).isNotEqualTo(oldUser);
        assertThat(purged(newUserId)).isFalse();
        assertThat(purged(oldUser)).isTrue();
    }

    // ---------- 수용 기준: 멱등·재개 ----------

    @Test
    @DisplayName("파기 배치를 두 번 실행해도 결과가 같다")
    void runningTwiceGivesSameResult() {
        UUID user = withdrawnUser(35, "DELETE");
        UUID groupId = newGroup(newUser("방장"));
        addMember(groupId, user, "ACTIVE", false);
        addAnswer(groupId, user, 1, "audio/k1.m4a");

        purgeService.runBatch();
        Object firstPurgedAt = jdbc.queryForObject("select purged_at from users where id = ?", Object.class, user);
        int answersAfterFirst = count("select count(*) from answers where user_id = ?", user);
        AccountPurgeService.Summary second = purgeService.runBatch();

        assertThat(second.purged()).isZero();
        assertThat(jdbc.queryForObject("select purged_at from users where id = ?", Object.class, user)).isEqualTo(firstPurgedAt);
        assertThat(count("select count(*) from answers where user_id = ?", user)).isEqualTo(answersAfterFirst).isZero();
        assertThat(purgeService.purgeOne(user)).isEqualTo(Outcome.SKIPPED);
        verify(storage, times(1)).delete("audio/k1.m4a"); // 두 번째 실행은 S3를 다시 부르지 않는다
    }

    @Test
    @DisplayName("S3 삭제가 실패하면 DB 행은 먼저 사라지지 않고, 임대가 만료된 뒤 다음 실행이 이어받아 끝낸다")
    void s3FailureKeepsDbRowsAndNextRunResumesAfterLeaseExpiry() {
        UUID user = withdrawnUser(33, "DELETE");
        UUID groupId = newGroup(newUser("방장"));
        addMember(groupId, user, "ACTIVE", false);
        addAnswer(groupId, user, 1, "audio/a1.m4a");
        addAnswer(groupId, user, 2, "audio/a2.m4a");
        doThrow(new IllegalStateException("S3 down")).when(storage).delete(anyString());

        assertThat(purgeService.purgeOne(user)).isEqualTo(Outcome.FAILED);

        assertThat(purged(user)).isFalse();
        assertThat(count("select count(*) from answers where user_id = ?", user)).isEqualTo(2); // DB 행 보존
        assertThat(count("select count(*) from user_identities where user_id = ?", user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from users where id = ?", String.class, user)).isNotEqualTo(ANON);

        // 임대가 유효한 동안(방금 시도)에는 다음 실행이 건너뛴다.
        reset(storage);
        assertThat(purgeService.runBatch().skipped() + purgeService.runBatch().purged()).isZero();
        assertThat(purged(user)).isFalse();

        // 임대가 만료되면(31분 전) 다음 실행이 이어받아 끝낸다.
        jdbc.update("update users set purge_started_at = now() - interval '31 minutes' where id = ?", user);
        AccountPurgeService.Summary resumed = purgeService.runBatch();

        assertThat(resumed.purged()).isGreaterThanOrEqualTo(1);
        assertThat(purged(user)).isTrue();
        assertThat(count("select count(*) from answers where user_id = ?", user)).isZero();
        verify(storage).delete("audio/a1.m4a");
        verify(storage).delete("audio/a2.m4a");
    }

    @Test
    @DisplayName("임대가 유효(30분 이내)한 사용자는 다른 실행이 건너뛴다")
    void validLeaseIsSkipped() {
        UUID user = withdrawnUser(33, "DELETE");
        jdbc.update("update users set purge_started_at = now() - interval '10 minutes' where id = ?", user);
        UUID groupId = newGroup(newUser("방장"));
        addMember(groupId, user, "ACTIVE", false);
        addAnswer(groupId, user, 1, "audio/lease.m4a");

        assertThat(purgeService.purgeOne(user)).isEqualTo(Outcome.SKIPPED);
        purgeService.runBatch();

        assertThat(purged(user)).isFalse();
        verify(storage, never()).delete(any());
        assertThat(count("select count(*) from answers where user_id = ?", user)).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 단계가 중간에 실패하면 전체가 롤백되고(식별정보도 그대로), 다음 실행이 처음부터 다시 해서 끝낸다")
    void dbStepFailureRollsBackAndResumes() {
        UUID user = withdrawnUser(34, "KEEP");
        UUID groupId = newGroup(newUser("방장"));
        addMember(groupId, user, "ACTIVE", false);
        jdbc.update("insert into member_aliases (group_id, viewer_id, target_id, alias) values (?, ?, ?, '호칭')",
            groupId, user, jdbc.queryForObject("select user_id from group_members where group_id = ? and is_owner", UUID.class, groupId));
        // 식별정보 삭제 뒤, 호칭 삭제 단계에서 실패하도록 주입한다.
        jdbc.execute("create or replace function purge_test_fail() returns trigger as $$ "
            + "begin raise exception 'injected'; end $$ language plpgsql");
        jdbc.execute("create trigger purge_test_fail_trg before delete on member_aliases "
            + "for each row execute function purge_test_fail()");
        try {
            assertThat(purgeService.purgeOne(user)).isEqualTo(Outcome.FAILED);

            // 롤백: 앞 단계의 삭제·익명화가 하나도 남아 있지 않다.
            assertThat(purged(user)).isFalse();
            assertThat(count("select count(*) from user_identities where user_id = ?", user)).isEqualTo(1);
            assertThat(count("select count(*) from refresh_tokens where user_id = ?", user)).isEqualTo(1);
            assertThat(count("select count(*) from user_devices where user_id = ?", user)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select name from users where id = ?", String.class, user)).isNotEqualTo(ANON);
        } finally {
            jdbc.execute("drop trigger if exists purge_test_fail_trg on member_aliases");
            jdbc.execute("drop function if exists purge_test_fail()");
        }

        jdbc.update("update users set purge_started_at = now() - interval '31 minutes' where id = ?", user);
        assertThat(purgeService.purgeOne(user)).isEqualTo(Outcome.PURGED);
        assertThat(purged(user)).isTrue();
        assertThat(count("select count(*) from user_identities where user_id = ?", user)).isZero();
        assertThat(count("select count(*) from member_aliases where viewer_id = ?", user)).isZero();
    }

    @Test
    @DisplayName("서버 2대가 같은 시점에 같은 사용자를 처리해도 이중 처리하지 않는다(한쪽만 PURGED, S3 삭제 1회)")
    void twoServersDoNotDoubleProcessSameUser() throws Exception {
        UUID user = withdrawnUser(36, "DELETE");
        UUID groupId = newGroup(newUser("방장"));
        addMember(groupId, user, "ACTIVE", false);
        addAnswer(groupId, user, 1, "audio/two.m4a");

        List<Outcome> outcomes = runConcurrently(() -> purgeService.purgeOne(user), () -> purgeService.purgeOne(user));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.PURGED, Outcome.SKIPPED);
        verify(storage, times(1)).delete("audio/two.m4a");
        assertThat(purged(user)).isTrue();
    }

    // ---------- 수용 기준: 콘텐츠 선택 (ADR 결정 14) ----------

    @Test
    @DisplayName("DELETE: 본인 답변(텍스트·음성 S3)만 삭제하고 그 답변에 달린 남의 리액션도 사라지며, 본인이 남에게 단 리액션은 유지한다")
    void deleteChoiceRemovesOnlyOwnAnswers() {
        UUID owner = newUser("방장");
        UUID groupId = newGroup(owner);
        UUID other = addValidMember(groupId);
        UUID user = withdrawnUser(32, "DELETE");
        addMember(groupId, user, "ACTIVE", false);

        UUID ownAnswerVoice = addAnswer(groupId, user, 1, "audio/own-voice.m4a");
        UUID ownAnswerText = addAnswer(groupId, user, 2, null);
        UUID othersAnswer = addAnswer(groupId, other, 3, "audio/other-voice.m4a");
        UUID reactionByOtherOnOwn = addReaction(ownAnswerVoice, other, null);
        UUID myReactionOnOthers = addReaction(othersAnswer, user, "audio/my-reaction-voice.m4a");

        assertThat(purgeService.purgeOne(user)).isEqualTo(Outcome.PURGED);

        // 본인 답변 삭제 + S3 삭제는 음성이 있는 답변만, 키는 정확히 그것들뿐
        assertThat(count("select count(*) from answers where id in (?, ?)", ownAnswerVoice, ownAnswerText)).isZero();
        verify(storage, times(1)).delete("audio/own-voice.m4a");
        verify(storage, times(1)).delete(any());
        // 삭제되는 답변에 달린 남의 리액션은 함께 사라진다
        assertThat(count("select count(*) from answer_reactions where id = ?", reactionByOtherOnOwn)).isZero();
        // 남의 답변과, 본인이 남에게 단 리액션(음성 리액션 포함)은 유지된다. 음성 리액션의 S3도 지우지 않는다.
        assertThat(count("select count(*) from answers where id = ?", othersAnswer)).isEqualTo(1);
        assertThat(count("select count(*) from answer_reactions where id = ?", myReactionOnOthers)).isEqualTo(1);
        verify(storage, never()).delete("audio/my-reaction-voice.m4a");
        verify(storage, never()).delete("audio/other-voice.m4a");
    }

    @Test
    @DisplayName("KEEP: 아무 콘텐츠도 지우지 않고 S3도 부르지 않으며, 개인정보 익명화는 된다")
    void keepChoiceDeletesNoContent() {
        UUID owner = newUser("방장");
        UUID groupId = newGroup(owner);
        UUID other = addValidMember(groupId);
        UUID user = withdrawnUser(32, "KEEP");
        addMember(groupId, user, "ACTIVE", false);
        UUID ownAnswer = addAnswer(groupId, user, 1, "audio/keep.m4a");
        UUID reactionByOther = addReaction(ownAnswer, other, null);
        UUID myReaction = addReaction(addAnswer(groupId, other, 2, null), user, "audio/keep-reaction.m4a");

        assertThat(purgeService.purgeOne(user)).isEqualTo(Outcome.PURGED);

        assertThat(count("select count(*) from answers where id = ?", ownAnswer)).isEqualTo(1);
        assertThat(count("select count(*) from answer_reactions where id in (?, ?)", reactionByOther, myReaction)).isEqualTo(2);
        verify(storage, never()).delete(any());
        assertThat(jdbc.queryForObject("select name from users where id = ?", String.class, user)).isEqualTo(ANON);
        // 본문은 유지되고 작성자 행은 tombstone으로 남는다(작성자 FK가 끊기지 않는다)
        assertThat(count("select count(*) from answers a join users u on u.id = a.user_id where a.id = ?", ownAnswer)).isEqualTo(1);
    }

    @Test
    @DisplayName("콘텐츠 선택이 없는 과거 탈퇴 데이터는 KEEP처럼 아무것도 지우지 않는다")
    void missingChoiceIsTreatedAsKeep() {
        UUID owner = newUser("방장");
        UUID groupId = newGroup(owner);
        UUID user = withdrawnUser(32, null);
        addMember(groupId, user, "ACTIVE", false);
        UUID ownAnswer = addAnswer(groupId, user, 1, "audio/legacy.m4a");

        assertThat(purgeService.purgeOne(user)).isEqualTo(Outcome.PURGED);

        assertThat(count("select count(*) from answers where id = ?", ownAnswer)).isEqualTo(1);
        verify(storage, never()).delete(any());
    }

    // ---------- 수용 기준: 혼자 남은 그룹 ----------

    @Test
    @DisplayName("혼자 남은 그룹은 파기 때 해산(archive)되고, 다른 유효 멤버가 있는 그룹은 유지된다")
    void lonelyGroupIsArchivedOthersKept() {
        UUID user = withdrawnUser(32, "KEEP");
        UUID lonelyGroup = newGroup(user);              // 방장이 본인뿐
        UUID pending = newUser("대기자");
        addMember(lonelyGroup, pending, "PENDING", false);
        jdbc.update("insert into group_invites (group_id, inviter_id, invite_code, expires_at) "
            + "values (?, ?, '654321', now() + interval '5 minutes')", lonelyGroup, user);
        assertThat(count("select count(*) from group_invites where group_id = ? and is_active", lonelyGroup)).isEqualTo(1);
        UUID sharedOwner = newUser("다른방장");
        UUID sharedGroup = newGroup(sharedOwner);       // 다른 유효 멤버(방장)가 있다
        addMember(sharedGroup, user, "ACTIVE", false);

        assertThat(purgeService.purgeOne(user)).isEqualTo(Outcome.PURGED);

        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, lonelyGroup))
            .isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?", String.class, lonelyGroup, user))
            .isEqualTo("LEFT");
        assertThat(count("select count(*) from group_members where group_id = ? and is_owner and status = 'ACTIVE'",
            lonelyGroup)).isZero();
        assertThat(jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?", String.class, lonelyGroup, pending))
            .isEqualTo("LEFT"); // 승인 대기는 거절
        assertThat(count("select count(*) from group_invites where group_id = ? and is_active", lonelyGroup)).isZero();

        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, sharedGroup))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?", String.class, sharedGroup, user))
            .isEqualTo("ACTIVE"); // 유효 멤버 규칙으로 숨겨질 뿐 행은 유지
    }

    @Test
    @DisplayName("복구할 수 있는 유예 중 방장이 남은 그룹은, 먼저 31일이 지난 멤버가 파기돼도 해산되지 않고 방장이 복구하면 그대로 돌아온다(Agy 검수 반영)")
    void groupWithGraceOwnerIsNotDissolvedByAnotherMembersPurge() {
        UUID graceOwner = withdrawnUser(10, "KEEP");     // 방장이지만 탈퇴 10일째(복구 가능)
        UUID groupId = newGroup(graceOwner);
        UUID purgedMember = withdrawnUser(32, "KEEP");
        addMember(groupId, purgedMember, "ACTIVE", false);

        assertThat(purgeService.purgeOne(purgedMember)).isEqualTo(Outcome.PURGED);

        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?", String.class, groupId, graceOwner))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
            "select is_owner from group_members where group_id = ? and user_id = ?", Boolean.class, groupId, graceOwner))
            .isTrue();

        accountService.restore(graceOwner);

        assertThat(purged(graceOwner)).isFalse();
        assertThat(jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?", String.class, groupId, graceOwner))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("방장이 파기될 때 그룹에 유효 멤버가 있으면 가장 오래된 유효 멤버에게 위임하고 그룹은 유지된다: M 탈퇴 → O 탈퇴 → M 복구 → O 파기(ADR 결정 15)")
    void purgedOwnerDelegatesToOldestValidMember() {
        UUID owner = newUser("방장O");
        UUID groupId = newGroup(owner);
        UUID member = addValidMember(groupId);
        UUID newerMember = addValidMember(groupId);
        jdbc.update("update group_members set joined_at = now() - interval '50 days' where group_id = ? and user_id = ?",
            groupId, member);
        jdbc.update("update group_members set joined_at = now() - interval '10 days' where group_id = ? and user_id = ?",
            groupId, newerMember);

        // 재현 순서: 멤버들이 먼저 탈퇴 → O 탈퇴(유효 후보가 없어 방장 행이 그대로 남는다) → 멤버 복구 → O 파기
        accountService.withdraw(member, ContentChoice.KEEP);
        accountService.withdraw(newerMember, ContentChoice.KEEP);
        accountService.withdraw(owner, ContentChoice.KEEP);
        // 유효 후보가 없었으므로 탈퇴 시점에는 방장 행이 그대로다(PR 1 규칙).
        assertThat(isOwnerOf(groupId, owner)).isTrue();

        // 시간 경과: O는 31일 넘게 지났고, 멤버들은 유예(30일) 안에 복구한다.
        jdbc.update("update users set deleted_at = now() - interval '35 days' where id = ?", owner);
        jdbc.update("update users set deleted_at = now() - interval '10 days' where id in (?, ?)", member, newerMember);
        accountService.restore(member);
        accountService.restore(newerMember);

        assertThat(purgeService.purgeOne(owner)).isEqualTo(Outcome.PURGED);

        // 가장 오래된 유효 멤버(가입 일시 순)에게 위임, 그룹은 유지, 해산·LEFT 정리 없음
        assertThat(isOwnerOf(groupId, member)).isTrue();
        assertThat(isOwnerOf(groupId, newerMember)).isFalse();
        assertThat(isOwnerOf(groupId, owner)).isFalse();
        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ACTIVE");
        assertThat(count("select count(*) from group_members where group_id = ? and is_owner and status = 'ACTIVE'",
            groupId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?", String.class, groupId, member))
            .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("방장 행이 이미 파기된 그룹으로 멤버가 복구하면 복구자가 방장이 되고 방장 플래그는 정확히 1명이다: M 탈퇴 → O 탈퇴 → O 파기 → M 복구(ADR 결정 16)")
    void restorerBecomesOwnerWhenOwnerRowWasPurged() {
        UUID owner = newUser("방장O");
        UUID groupId = newGroup(owner);
        UUID member = addValidMember(groupId);
        accountService.withdraw(member, ContentChoice.KEEP);
        accountService.withdraw(owner, ContentChoice.KEEP);
        assertThat(isOwnerOf(groupId, owner)).isTrue(); // 유효 후보가 없어 방장 행이 그대로다(PR 1 규칙)
        jdbc.update("update users set deleted_at = now() - interval '35 days' where id = ?", owner);
        jdbc.update("update users set deleted_at = now() - interval '10 days' where id = ?", member);

        assertThat(purgeService.purgeOne(owner)).isEqualTo(Outcome.PURGED);

        // 파기 시점에는 M이 유예 중이라 위임도 해산도 하지 않고, 파기된 방장 행이 남는다.
        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ACTIVE");
        assertThat(isOwnerOf(groupId, owner)).isTrue();

        accountService.restore(member);

        assertThat(isOwnerOf(groupId, member)).isTrue();
        assertThat(isOwnerOf(groupId, owner)).isFalse();
        assertThat(count("select count(*) from group_members where group_id = ? and is_owner and status = 'ACTIVE'",
            groupId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?", String.class, groupId, member))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("방장이 단지 탈퇴 유예 중이면 복구자는 방장이 되지 않고, 그 방장이 이후 파기되면 결정 15로 유효 멤버에게 위임된다")
    void graceOwnerKeepsOwnershipUntilPurgedThenDelegates() {
        UUID owner = newUser("방장O");
        UUID groupId = newGroup(owner);
        UUID member = addValidMember(groupId);
        accountService.withdraw(member, ContentChoice.KEEP);
        accountService.withdraw(owner, ContentChoice.KEEP);

        accountService.restore(member);

        // 방장은 유예 중(복구 가능)이므로 복귀 권리를 존중한다: 복구자는 방장이 되지 않는다.
        assertThat(isOwnerOf(groupId, owner)).isTrue();
        assertThat(isOwnerOf(groupId, member)).isFalse();
        assertThat(count("select count(*) from group_members where group_id = ? and is_owner and status = 'ACTIVE'",
            groupId)).isEqualTo(1);

        // 방장이 복귀하지 않고 파기되면 유효 멤버(M)에게 위임된다.
        jdbc.update("update users set deleted_at = now() - interval '35 days' where id = ?", owner);
        assertThat(purgeService.purgeOne(owner)).isEqualTo(Outcome.PURGED);

        assertThat(isOwnerOf(groupId, member)).isTrue();
        assertThat(isOwnerOf(groupId, owner)).isFalse();
        assertThat(count("select count(*) from group_members where group_id = ? and is_owner and status = 'ACTIVE'",
            groupId)).isEqualTo(1);
    }

    @Test
    @DisplayName("방장이 파기될 때 유효 멤버가 없고 파기되지 않은 멤버도 없으면 기존 해산 규칙대로 그룹이 해산된다")
    void purgedOwnerWithNoValidMemberDissolvesGroup() {
        UUID owner = withdrawnUser(35, "KEEP");
        UUID groupId = newGroup(owner);

        assertThat(purgeService.purgeOne(owner)).isEqualTo(Outcome.PURGED);

        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ARCHIVED");
        assertThat(count("select count(*) from group_members where group_id = ? and (status = 'ACTIVE' or is_owner)",
            groupId)).isZero();
    }

    private boolean isOwnerOf(UUID groupId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select is_owner from group_members where group_id = ? and user_id = ?", Boolean.class, groupId, userId));
    }

    @Test
    @DisplayName("마지막 미파기 멤버가 파기될 때 그룹이 해산되고, 먼저 파기된 방장의 ACTIVE 행까지 LEFT·방장 해제로 정리된다(Agy 검수 반영)")
    void lastPurgeDissolvesGroupAndCleansEveryActiveRow() {
        UUID owner = withdrawnUser(40, "KEEP");
        UUID groupId = newGroup(owner);
        UUID member = withdrawnUser(32, "KEEP");
        addMember(groupId, member, "ACTIVE", false);

        assertThat(purgeService.purgeOne(owner)).isEqualTo(Outcome.PURGED); // 멤버가 아직 파기 전이라 해산하지 않는다
        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ACTIVE");

        assertThat(purgeService.purgeOne(member)).isEqualTo(Outcome.PURGED);  // 마지막 미파기 멤버

        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ARCHIVED");
        assertThat(count("select count(*) from group_members where group_id = ? and (status = 'ACTIVE' or is_owner)",
            groupId)).isZero();
    }

    // ---------- 수용 기준: Apple 해지 큐 ----------

    @Test
    @DisplayName("Apple 연결이 있던 사용자만 해지 큐에 PENDING으로 기록되고, 해지 실패가 파기를 막지 않는다")
    void appleRevocationFailureDoesNotBlockPurge() {
        UUID appleUser = withdrawnUser(32, "KEEP");
        jdbc.update("insert into user_identities (user_id, provider, subject) values (?, 'APPLE', ?)",
            appleUser, "a-" + UUID.randomUUID());
        UUID kakaoOnly = withdrawnUser(32, "KEEP");
        when(revoker.revoke(any())).thenThrow(new IllegalStateException("apple down"));

        assertThat(purgeService.purgeOne(appleUser)).isEqualTo(Outcome.PURGED);
        assertThat(purgeService.purgeOne(kakaoOnly)).isEqualTo(Outcome.PURGED);

        assertThat(purged(appleUser)).isTrue(); // 파기는 큐에 넣기만 하므로 해지 실패와 무관하다
        assertThat(count("select count(*) from apple_revocations where user_id = ? and status = 'PENDING'", appleUser))
            .isEqualTo(1);
        assertThat(count("select count(*) from apple_revocations where user_id = ?", kakaoOnly)).isZero();
    }

    @Test
    @DisplayName("이미 해지 큐 행이 있는 사용자를 파기해도 유니크 충돌 없이 끝나고 큐 행은 1개다(ON CONFLICT DO NOTHING)")
    void existingQueueRowDoesNotBreakPurge() {
        UUID appleUser = withdrawnUser(32, "KEEP");
        jdbc.update("insert into user_identities (user_id, provider, subject) values (?, 'APPLE', ?)",
            appleUser, "a-" + UUID.randomUUID());
        jdbc.update("insert into apple_revocations (user_id, attempts) values (?, 2)", appleUser);

        assertThat(purgeService.purgeOne(appleUser)).isEqualTo(Outcome.PURGED);

        assertThat(count("select count(*) from apple_revocations where user_id = ?", appleUser)).isEqualTo(1);
        assertThat(count("select attempts from apple_revocations where user_id = ?", appleUser)).isEqualTo(2); // 덮어쓰지 않는다
    }

    @Test
    @DisplayName("임대가 만료돼 다른 실행이 먼저 처리한 행은 늦게 끝난 실행이 횟수를 되감지 않고 집계하지 않는다(Agy 검수 반영)")
    void staleApplyDoesNotRewindAttempts() {
        UUID user = newUser("지연");
        jdbc.update("insert into apple_revocations (user_id) values (?)", user);
        when(revoker.revoke(any())).thenAnswer(inv -> {
            // 호출이 지연되는 사이 다른 실행이 이 행을 먼저 처리해 attempts를 5로 올렸다고 가정한다.
            jdbc.update("update apple_revocations set attempts = 5 where user_id = ?", user);
            throw new IllegalStateException("apple down");
        });

        AppleRevocationService.Summary summary = appleService.runDue();

        assertThat(count("select attempts from apple_revocations where user_id = ?", user)).isEqualTo(5);
        assertThat(summary.retry()).isZero();
        assertThat(summary.failed()).isZero();
    }

    @Test
    @DisplayName("해지 실패는 횟수를 올리고 10분 ±20% 뒤로 미루며, 8번째 실패는 FAILED가 된다")
    void appleRevocationFailureBacksOffThenFails() {
        UUID user = newUser("애플");
        jdbc.update("insert into apple_revocations (user_id) values (?)", user);
        when(revoker.revoke(any())).thenThrow(new IllegalStateException("apple down"));

        AppleRevocationService.Summary first = appleService.runDue();

        assertThat(first.retry()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select status from apple_revocations where user_id = ?", String.class, user))
            .isEqualTo("PENDING");
        assertThat(count("select attempts from apple_revocations where user_id = ?", user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_error from apple_revocations where user_id = ?", String.class, user))
            .isEqualTo("IllegalStateException");
        Double secondsUntilNext = jdbc.queryForObject(
            "select extract(epoch from (next_attempt_at - now())) from apple_revocations where user_id = ?",
            Double.class, user);
        assertThat(secondsUntilNext).isBetween(8 * 60.0 - 5, 12 * 60.0 + 5); // 10분 ±20%

        // 아직 시각이 안 됐으므로 다음 실행은 건드리지 않는다.
        assertThat(appleService.runDue().claimed()).isZero();

        // 7번 실패한 행이 due이면 8번째 실패로 FAILED가 되어 더는 집히지 않는다.
        jdbc.update("update apple_revocations set attempts = 7, next_attempt_at = now() where user_id = ?", user);
        appleService.runDue();
        assertThat(jdbc.queryForObject("select status from apple_revocations where user_id = ?", String.class, user))
            .isEqualTo("FAILED");
        assertThat(count("select attempts from apple_revocations where user_id = ?", user)).isEqualTo(8);
        jdbc.update("update apple_revocations set next_attempt_at = now() where user_id = ?", user);
        assertThat(appleService.runDue().claimed()).isZero();
    }

    @Test
    @DisplayName("해지 수단이 설정되지 않은 구현체(SKIPPED)는 재시도 없이 건너뜀으로 기록되고, 성공(DONE)은 DONE으로 기록된다")
    void appleSkippedAndDoneAreRecorded() {
        UUID skippedUser = newUser("건너뜀");
        UUID doneUser = newUser("완료");
        jdbc.update("insert into apple_revocations (user_id) values (?)", skippedUser);
        jdbc.update("insert into apple_revocations (user_id) values (?)", doneUser);
        when(revoker.revoke(skippedUser)).thenReturn(AppleTokenRevoker.Result.SKIPPED);
        when(revoker.revoke(doneUser)).thenReturn(AppleTokenRevoker.Result.DONE);

        appleService.runDue();

        assertThat(jdbc.queryForObject("select status from apple_revocations where user_id = ?", String.class, skippedUser))
            .isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("select status from apple_revocations where user_id = ?", String.class, doneUser))
            .isEqualTo("DONE");
        assertThat(count("select attempts from apple_revocations where user_id = ?", skippedUser)).isZero();
    }

    // ---------- util ----------

    private List<Outcome> runConcurrently(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Outcome> a = pool.submit(() -> {
                barrier.await();
                return first.call();
            });
            Future<Outcome> b = pool.submit(() -> {
                barrier.await();
                return second.call();
            });
            return List.of(a.get(), b.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
