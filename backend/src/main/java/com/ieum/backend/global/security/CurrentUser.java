package com.ieum.backend.global.security;

import com.ieum.backend.domain.common.enums.MembershipTier;
import java.util.UUID;

public record CurrentUser(
    UUID userId,
    MembershipTier membershipTier
) {
}
