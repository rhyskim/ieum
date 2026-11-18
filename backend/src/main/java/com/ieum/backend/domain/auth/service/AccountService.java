package com.ieum.backend.domain.auth.service;

import com.ieum.backend.domain.auth.repository.RefreshTokenRepository;
import com.ieum.backend.domain.common.enums.ContentChoice;
import com.ieum.backend.domain.group.service.GroupMembershipService;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.domain.user.repository.UserDeviceRepository;
import com.ieum.backend.domain.user.repository.UserRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 요청과 복구의 DB 부분(ADR-0002). 소셜 토큰 검증 같은 외부 호출은 호출자(AuthService)가
 * 트랜잭션 밖에서 하고, 여기서는 한 트랜잭션으로 상태를 바꾼다.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    /** 탈퇴 후 복구할 수 있는 기간. 파기 시작은 이보다 1일 늦다(31일 이후, PR 2). */
    public static final Duration RESTORE_GRACE = Duration.ofDays(30);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserDeviceRepository deviceRepository;
    private final GroupMembershipService membershipService;

    /**
     * 탈퇴 요청: deleted_at·콘텐츠 선택 저장, 방장 위임, 기기 토큰 삭제, Refresh 전체 폐기를 한 트랜잭션으로 처리한다.
     * 락 순서는 users 행 → 내가 방장인 그룹(id 오름차순)이다. 이미 탈퇴한 계정이면 아무것도 바꾸지 않는다(멱등).
     */
    @Transactional
    public void withdraw(UUID userId, ContentChoice contentChoice) {
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (user.getDeletedAt() != null) {
            return;
        }
        Instant now = Instant.now();
        user.markDeleted(now, contentChoice);
        userRepository.saveAndFlush(user); // 방장 후보·인원 수 쿼리가 나를 유예 중으로 보도록 먼저 반영한다.

        membershipService.delegateOwnershipsOnWithdrawal(userId);
        deviceRepository.deleteAllByUserId(userId);
        refreshTokenRepository.revokeAllByUser(userId, now);
        // 탈퇴로 분모가 줄어 "전원 답변"이 성립할 수 있다(ADR-0007 결정 8). 락 순서의 마지막 단계라 맨 끝에 둔다.
        membershipService.reevaluateRevealsOnWithdrawal(userId);
    }

    /**
     * 복구: 조건부 UPDATE가 성공해야만 진행한다(같은 행의 파기 시작과 한쪽만 성공). 성공하면 같은 트랜잭션에서
     * 그룹별 인원 상한을 확인해 이미 가득 찬 그룹은 LEFT로 바꾼다. 방장은 돌아오지 않는다.
     * 이미 복구돼 있으면(동시 복구) 성공으로 본다. 만료되었거나 파기가 시작됐으면 ACCOUNT_RESTORE_EXPIRED.
     */
    @Transactional
    public void restore(UUID userId) {
        if (userRepository.restoreIfEligible(userId, (int) RESTORE_GRACE.toDays()) == 0) {
            User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (user.getDeletedAt() != null) {
                throw new BusinessException(ErrorCode.ACCOUNT_RESTORE_EXPIRED);
            }
            return;
        }
        membershipService.reconcileOnRestore(userId);
    }
}
