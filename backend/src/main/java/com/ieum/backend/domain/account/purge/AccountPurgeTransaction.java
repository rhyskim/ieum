package com.ieum.backend.domain.account.purge;

import com.ieum.backend.domain.group.service.GroupMembershipService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파기의 DB 단계(ADR-0002 결정 9): 외부 자원(S3) 삭제가 끝난 뒤, 한 트랜잭션에서 익명화와 완료 표시를 한다.
 * 모든 문장이 멱등이라 어디서 실패해도 전체가 롤백되고 다음 실행이 처음부터 다시 해도 같은 결과가 된다.
 * users 행은 삭제하지 않는다(family_groups.creator_id 등이 참조하므로 익명화한 tombstone으로 남긴다).
 *
 * <p>가정(PR 본문 참고): 답변 삭제는 answers → answer_reactions의 FK ON DELETE CASCADE에 의존하고,
 * 삭제 대상은 현재 스키마의 answers뿐이다. 본인이 남에게 단 answer_reactions는 선택과 무관하게 지우지 않는다(ADR 결정 14).
 */
@Component
@RequiredArgsConstructor
public class AccountPurgeTransaction {

    /** 익명화된 사용자의 표시 이름(NOT NULL 컬럼이라 NULL 대신 이 문자열을 쓴다). */
    static final String ANONYMOUS_NAME = "탈퇴한 사용자";

    private final JdbcTemplate jdbc;
    private final GroupMembershipService membershipService;

    @PersistenceContext
    private EntityManager em;

    /**
     * @return 이 호출이 users 행을 PURGED로 만들었으면 true. 임대 만료 경계에서 다른 실행이 먼저 끝냈으면 false
     *     (앞의 문장은 멱등이라 그대로 두어도 안전하며, 호출자는 이중 집계하지 않도록 건너뜀으로 센다).
     */
    @Transactional
    public boolean finalizeUser(UUID userId, boolean deleteContent) {
        // 1) 그룹 정리(JPA): 방장이면 유효 멤버에게 위임, 혼자 남은 그룹은 해산. 그룹을 id 오름차순으로 잠근 뒤
        //    users 행은 마지막에 갱신한다. 이 순서는 탈퇴·복구
        //    (users 행 → 그룹)와 반대지만, 파기 대상(31일 경과, purge_started_at 있음)은 복구 UPDATE가 행에 닿지 못하고
        //    탈퇴 요청은 이미 탈퇴한 계정에서 아무것도 하지 않으므로 같은 사용자를 두고 교착이 생기지 않는다.
        //    JPA가 읽은 group_members가 뒤의 JDBC 익명화를 덮어쓰지 않도록 변경을 반영하고 영속성 컨텍스트를 비운다.
        membershipService.settleGroupsOnPurge(userId);
        em.flush();
        em.clear();

        // 2) 영구 삭제 선택: 본인이 쓴 답변만 삭제한다. 그 답변에 달린 남의 리액션은 FK cascade로 함께 사라진다.
        if (deleteContent) {
            jdbc.update("delete from answers where user_id = ?", userId);
        }

        // 3) Apple 해지는 큐에 넣기만 한다(실제 호출·재시도는 별도 작업). 식별정보를 지우기 전에 Apple 연결 여부를 본다.
        jdbc.update(
            "insert into apple_revocations (user_id) "
                + "select cast(? as uuid) where exists "
                + "(select 1 from user_identities where user_id = ? and provider = 'APPLE') "
                + "on conflict (user_id) do nothing",
            userId, userId);

        // 4) 로그인 수단·세션·기기 토큰 삭제: 같은 소셜 계정으로 새로 가입해도 이전 데이터와 연결되지 않는다.
        jdbc.update("delete from user_identities where user_id = ?", userId);
        jdbc.update("delete from refresh_tokens where user_id = ?", userId);
        jdbc.update("delete from user_devices where user_id = ?", userId);

        // 5) 내가 보는 사람이었던 호칭은 삭제하고, 타인이 나를 부르던 호칭(target_id = 나)은 유지한다.
        jdbc.update("delete from member_aliases where viewer_id = ?", userId);

        // 6) 그룹 안의 개인정보 익명화.
        jdbc.update("update group_members set family_nickname = ?, target_call_phone = null where user_id = ?",
            ANONYMOUS_NAME, userId);

        // 7) 마지막: users 익명화와 완료 표시. 이미 다른 실행이 끝냈으면 0행이다.
        return jdbc.update(
            "update users set name = ?, email = null, phone_number = null, purged_at = now(), updated_at = now() "
                + "where id = ? and purged_at is null and deleted_at is not null",
            ANONYMOUS_NAME, userId) == 1;
    }
}
