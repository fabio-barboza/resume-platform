package dev.resumeplatform.resumeai.core.gateway;

import java.util.Optional;

public interface StorageGateway {
    void store(String filename, String digest, byte[] content);

    Optional<byte[]> fetch(String filename, String digest);

    void discard(String filename, String digest);
}
