package com.milobroker.marketplace.service;
import java.time.Duration;
/** Signs S3 URLs so browsers upload/download bytes directly. No bytes pass through the app. */
public interface MediaUrlSigner {
  String presignPut(String bucket, String key, String contentType, long contentLength, Duration ttl);
  String presignGet(String bucket, String key, Duration ttl);
}
