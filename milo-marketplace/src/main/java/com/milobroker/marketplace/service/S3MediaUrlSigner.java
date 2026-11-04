package com.milobroker.marketplace.service;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
/**
 * S3 presigner using the default credential chain (env, then EC2 instance role).
 * Signing is local; no S3 calls are made until the browser uses the URL.
 */
@Service
public class S3MediaUrlSigner implements MediaUrlSigner {
  private final S3Presigner presigner;
  public S3MediaUrlSigner(
      @Value("${milobroker.media.region:ap-southeast-2}") String region,
      @Value("${milobroker.media.endpoint:}") String endpoint) {
    var builder = S3Presigner.builder()
      .region(Region.of(region))
      .credentialsProvider(DefaultCredentialsProvider.create());
    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    this.presigner = builder.build();
  }
  @Override
  public String presignPut(String bucket, String key, String contentType, long contentLength, Duration ttl) {
    PutObjectRequest put = PutObjectRequest.builder()
      .bucket(bucket).key(key).contentType(contentType).contentLength(contentLength).build();
    return presigner.presignPutObject(PutObjectPresignRequest.builder()
      .putObjectRequest(put).signatureDuration(ttl).build()).url().toString();
  }
  @Override
  public String presignGet(String bucket, String key, Duration ttl) {
    GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
    return presigner.presignGetObject(GetObjectPresignRequest.builder()
      .getObjectRequest(get).signatureDuration(ttl).build()).url().toString();
  }
}
