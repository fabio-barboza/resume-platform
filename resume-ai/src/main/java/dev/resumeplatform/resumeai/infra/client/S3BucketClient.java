package dev.resumeplatform.resumeai.infra.client;

import java.net.URI;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import dev.resumeplatform.resumeai.config.ResumeAiProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Cliente S3 criado no primeiro uso, não na subida: a API sobe mesmo com o MinIO fora do ar. */
@Component
public class S3BucketClient {
    private final ResumeAiProperties.Storage config;
    private volatile S3Client client;

    public S3BucketClient(ResumeAiProperties properties) {
        this.config = properties.storage();
    }

    public String bucket() {
        return config.bucket();
    }

    public S3Client client() {
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
}
