package com.ieum.backend.global.security;

import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;

@Aspect
@Component
@RequiredArgsConstructor
public class GroupMemberAccessAspect {

    private final GroupAccessValidator groupAccessValidator;

    @Before("@annotation(groupMemberOnly)")
    public void verifyGroupMemberAccess(JoinPoint joinPoint, GroupMemberOnly groupMemberOnly) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser currentUser)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        UUID groupId = extractGroupId(joinPoint, groupMemberOnly.paramName());
        groupAccessValidator.validateActiveMember(groupId, currentUser.userId());
    }

    private UUID extractGroupId(JoinPoint joinPoint, String targetParamName) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
            String pathVarName = null;
            if (pathVariable != null) {
                if (!pathVariable.value().isBlank()) {
                    pathVarName = pathVariable.value();
                } else if (!pathVariable.name().isBlank()) {
                    pathVarName = pathVariable.name();
                }
            }
            String candidateName = pathVarName != null
                ? pathVarName
                : (parameterNames != null && i < parameterNames.length ? parameterNames[i] : parameter.getName());

            if (targetParamName.equals(candidateName) && args[i] instanceof UUID uuidArg) {
                return uuidArg;
            }
        }

        throw new IllegalStateException(
            "@GroupMemberOnly 대상 메서드에서 UUID 타입의 '" + targetParamName + "' 파라미터를 찾을 수 없습니다: " + method.getName()
        );
    }
}
