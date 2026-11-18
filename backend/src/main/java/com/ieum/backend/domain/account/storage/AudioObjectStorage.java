package com.ieum.backend.domain.account.storage;

/** 음성 객체 저장소에서 삭제하는 지점. 파기 배치의 실패 주입·S3 없는 테스트를 위해 인터페이스로 둔다. */
public interface AudioObjectStorage {

    /** 객체를 삭제한다. 이미 없는 키여도 성공(멱등)이며, 실패하면 예외를 던진다. */
    void delete(String objectKey);
}
