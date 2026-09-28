package dev.resumeplatform.resumeai.infra.gateway;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.gateway.StorageGateway;
import dev.resumeplatform.resumeai.infra.client.S3BucketClient;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class StorageGatewayImpl implements StorageGateway {
    private static final Logger log = LoggerFactory.getLogger(StorageGatewayImpl.class);
    private static final String HASH_META_KEY = "sha256";

    private final S3BucketClient s3;

    public StorageGatewayImpl(S3BucketClient s3) {
        this.s3 = s3;
    }

    static String slug(String filename) {
        String stem = filename.substring(filename.lastIndexOf('/') + 1);
        if (stem.endsWith(".pdf")) {
            stem = stem.substring(0, stem.length() - 4);
        }
        String ascii = Normalizer.normalize(stem, Normalizer.Form.NFKD).replaceAll("[^\\x00-\\x7F]", "");
        String slug = ascii.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "curriculo" : slug;
    }

    private static List<String> candidateKeys(String filename, String digest) {
        String slug = slug(filename);
        return List.of(slug + ".pdf", slug + "__" + digest.substring(0, 12) + ".pdf");
    }

    private boolean matchesDigest(S3Client client, String key, String digest) {
        try {
            var head = client.headObject(b -> b.bucket(s3.bucket()).key(key));
            return digest.equals(head.metadata().get(HASH_META_KEY));
        } catch (S3Exception ex) {
            return false;
        }
    }

    private Optional<String> storedKey(S3Client client, String filename, String digest) {
        return candidateKeys(filename, digest).stream().filter(key -> matchesDigest(client, key, digest)).findFirst();
    }

    @Override
    public void store(String filename, String digest, byte[] content) {
        try {
            S3Client client = s3.client();
            if (storedKey(client, filename, digest).isPresent()) {
                return;
            }
            List<String> keys = candidateKeys(filename, digest);
            String target;
            try {
                client.headObject(b -> b.bucket(s3.bucket()).key(keys.getFirst()));
                target = keys.getLast();
            } catch (NoSuchKeyException ex) {
                target = keys.getFirst();
            } catch (S3Exception ex) {
                if (ex.statusCode() != 404) {
                    throw ex;
                }
                target = keys.getFirst();
            }
            String key = target;
            client.putObject(b -> b.bucket(s3.bucket()).key(key).contentType("application/pdf")
                    .metadata(Map.of(HASH_META_KEY, digest)), RequestBody.fromBytes(content));
        } catch (SdkException ex) {
            log.warn("Não foi possível guardar '{}' no bucket {}", filename, s3.bucket(), ex);
        }
    }

    @Override
    public Optional<byte[]> fetch(String filename, String digest) {
        try {
            S3Client client = s3.client();
            return storedKey(client, filename, digest)
                    .map(key -> client.getObjectAsBytes(b -> b.bucket(s3.bucket()).key(key)).asByteArray());
        } catch (SdkException ex) {
            log.warn("Não foi possível baixar o arquivo de '{}'", filename, ex);
            return Optional.empty();
        }
    }

    @Override
    public void discard(String filename, String digest) {
        try {
            S3Client client = s3.client();
            storedKey(client, filename, digest).ifPresent(key -> client.deleteObject(b -> b.bucket(s3.bucket()).key(key)));
        } catch (SdkException ex) {
            log.warn("Não foi possível remover o arquivo de '{}'", filename, ex);
        }
    }
}
