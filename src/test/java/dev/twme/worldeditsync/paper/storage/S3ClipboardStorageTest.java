package dev.twme.worldeditsync.paper.storage;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;

import dev.twme.worldeditsync.paper.s3.S3StorageManager;
import io.minio.MinioClient;

public class S3ClipboardStorageTest {

    @Test
    public void closeReleasesMinioHttpResources() throws Exception {
        S3StorageManager manager = mock(S3StorageManager.class);
        MinioClient client = mock(MinioClient.class);
        when(manager.initialize()).thenReturn(client);
        S3ClipboardStorage storage = new S3ClipboardStorage(manager);

        assertTrue(storage.initialize());
        storage.close();

        verify(client).close();
    }
}
