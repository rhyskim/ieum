package com.ieum.backend.domain.account.storage;

import com.ieum.backend.global.config.S3Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/** S3 DeleteObject는 없는 키에도 성공(204)을 돌려주므로 재실행해도 안전하다. */
@Component
@RequiredArgsConstructor
public class S3AudioObjectStorage implements AudioObjectStorage {

    private final S3Client s3Client;
    private final S3Properties s3Properties;

    @Override
    public void delete(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
            .bucket(s3Properties.bucketName())
            .key(objectKey)
            .build());
    }
}
