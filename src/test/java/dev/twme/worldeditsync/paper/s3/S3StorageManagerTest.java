package dev.twme.worldeditsync.paper.s3;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.logging.Logger;

import org.junit.Test;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import io.minio.MinioClient;

public class S3StorageManagerTest {

    @Test
    public void closesClientWhenInitializationFails() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenThrow(new IllegalStateException("unavailable"));
        S3StorageManager storage = new S3StorageManager(
                "http://127.0.0.1:9000", "access", "secret", "bucket", "",
                new MessageCipher("token"), 1024, mock(Logger.class)) {
            @Override
            protected MinioClient createClient() {
                return client;
            }
        };

        assertNull(storage.initialize());

        verify(client).close();
    }
}
