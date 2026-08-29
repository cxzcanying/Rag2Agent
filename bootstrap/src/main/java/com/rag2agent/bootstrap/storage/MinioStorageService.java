package com.rag2agent.bootstrap.storage;

import com.rag2agent.framework.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient client;
    private final MinioProperties properties;

    public MinioStorageService(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
                log.info("MinIO bucket created: {}", properties.getBucket());
            }
        } catch (Exception e) {
            throw new IllegalStateException("MinIO bucket 初始化失败: " + e.getMessage(), e);
        }
    }

    public void upload(String objectKey, InputStream input, long size, String contentType) throws Exception {
        client.putObject(PutObjectArgs.builder()
                .bucket(properties.getBucket())//桶是逻辑隔离单位，对象存储，权限等按桶生效，一个MinIO不能有同名桶
                .object(objectKey) //对象键：目录
                .stream(input, size, -1) //input 文件输入流， size对象总大小 -1分片大小，让sdk根据对象大小自动分片
                .contentType(contentType)
                .build());
    }

    public String presignGet(String objectKey, int expiresInSeconds) throws Exception {
        return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(properties.getBucket())
                .object(objectKey)
                .expiry(expiresInSeconds)
                .build());
    }

    public byte[] download(String objectKey) throws Exception {
        try (InputStream input = client.getObject(GetObjectArgs.builder()
                .bucket(properties.getBucket())
                .object(objectKey)
                .build())) {
            return input.readAllBytes();
        }
    }

    public void downloadTo(String objectKey, Path target) throws Exception {
        try (InputStream input = client.getObject(GetObjectArgs.builder()
                .bucket(properties.getBucket())
                .object(objectKey)
                .build());
                OutputStream output = Files.newOutputStream(target)) {
            input.transferTo(output);
        }
    }
}
