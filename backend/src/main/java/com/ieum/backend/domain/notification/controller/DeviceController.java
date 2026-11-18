package com.ieum.backend.domain.notification.controller;

import com.ieum.backend.domain.notification.dto.DeviceDtos.RegisterDeviceRequest;
import com.ieum.backend.domain.notification.service.DeviceService;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    public ApiResponse<Void> register(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody RegisterDeviceRequest request
    ) {
        deviceService.register(currentUser.userId(), request.fcmToken(), request.deviceOs());
        return ApiResponse.ok(null);
    }
}
