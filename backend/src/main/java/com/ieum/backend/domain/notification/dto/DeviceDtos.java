package com.ieum.backend.domain.notification.dto;

import com.ieum.backend.domain.common.enums.DeviceOs;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    public record RegisterDeviceRequest(
        @NotBlank @Size(max = 512) String fcmToken,
        @NotNull DeviceOs deviceOs
    ) {
    }
}
