package dev.resumeplatform.resumeai.infra;

import java.net.URI;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import dev.resumeplatform.resumeai.config.ResumeAiProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class ResumeStorage {
    private static final Logger log = LoggerFactory.getLogger(ResumeStorage.class);
    private static final String HASH_META_KEY = "sha256";

    private final ResumeAiProperties.Storage config;
    private volatile S3Client client;

    public ResumeStorage(ResumeAiProperties properties) {
        this.config = properties.storage();
    }

    private S3Client client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    var builder = S3Client.builder()
                            .region(Region.of(config.region()))
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey())));

                    if (StringUtils.hasText(config.endpointUrl())) {
                        builder.endpointOverride(URI.create(config.endpointUrl())).forcePathStyle(true);
                    }
                    S3Client created = builder.build();
                    try {
                        created.headBucket(b -> b.bucket(config.bucket()));
                    } catch (S3Exception ex) {
                        created.createBucket(b -> b.bucket(config.bucket()));
                    }
                    client = created;
                }
            }
        }
        return client;
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

    private boolean matchesDigest(S3Client s3, String key, String digest) {
        try {
            var head = s3.headObject(b -> b.bucket(config.bucket()).key(key));
            return digest.equals(head.metadata().get(HASH_META_KEY));
        } catch (S3Exception ex) {
            return false;
        }
    }

    private Optional<String> storedKey(S3Client s3, String filename, String digest) {
        return candidateKeys(filename, digest).stream().filter(key -> matchesDigest(s3, key, digest)).findFirst();
    }

    public void store(String filename, String digest, byte[] content) {
        try {
            S3Client s3 = client();
            if (storedKey(s3, filename, digest).isPresent()) {
                return;
            }
            List<String> keys = candidateKeys(filename, digest);
            String target;
            try {
                s3.headObject(b -> b.bucket(config.bucket()).key(keys.getFirst()));
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
            s3.putObject(b -> b.bucket(config.bucket()).key(key).contentType("application/pdf")
                    .metadata(Map.of(HASH_META_KEY, digest)), RequestBody.fromBytes(content));
        } catch (SdkException ex) {
            log.warn("Não foi possível guardar '{}' no bucket {}", filename, config.bucket(), ex);
        }
    }

    public Optional<byte[]> fetch(String filename, String digest) {
        try {
            S3Client s3 = client();
            return storedKey(s3, filename, digest)
                    .map(key -> s3.getObjectAsBytes(b -> b.bucket(config.bucket()).key(key)).asByteArray());
        } catch (SdkException ex) {
            log.warn("Não foi possível baixar o arquivo de '{}'", filename, ex);
            return Optional.empty();
        }
    }

    public void discard(String filename, String digest) {
        try {
            S3Client s3 = client();
            storedKey(s3, filename, digest).ifPresent(key -> s3.deleteObject(b -> b.bucket(config.bucket()).key(key)));
        } catch (SdkException ex) {
            log.warn("Não foi possível remover o arquivo de '{}'", filename, ex);
        }
    }
}
