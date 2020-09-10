/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.backup.encryption.tasks;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertThrows;

import android.annotation.Nullable;
import android.app.backup.BackupTransport;
import android.platform.test.annotations.Presubmit;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.backup.encryption.FullBackupDataProcessor;
import com.android.server.backup.encryption.chunking.ProtoStore;
import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.TertiaryKeyManager;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.testing.QueuingNonAutomaticExecutorService;

import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Presubmit
@Config(
        shadows = {
            EncryptedFullBackupDataProcessorTest.ShadowEncryptedFullBackupTask.class,
        })
public class EncryptedFullBackupDataProcessorTest {

    private static final String KEY_GENERATOR_ALGORITHM = "AES";

    private static final String TEST_PACKAGE = "com.example.app1";
    private static final byte[] TEST_DATA_1 = {1, 2, 3, 4};
    private static final byte[] TEST_DATA_2 = {5, 6, 7, 8};

    private final RecoverableKeyStoreSecondaryKey mTestSecondaryKey =
            new RecoverableKeyStoreSecondaryKey(
                    /*alias=*/ "test_key",
                    new SecretKeySpec(
                            new byte[] {
                                1, 2, 3,
                            },
                            KEY_GENERATOR_ALGORITHM));

    private QueuingNonAutomaticExecutorService mExecutorService;
    private FullBackupDataProcessor mFullBackupDataProcessor;
    @Mock private FullBackupDataProcessor.FullBackupCallbacks mFullBackupCallbacks;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mExecutorService = new QueuingNonAutomaticExecutorService();
        mFullBackupDataProcessor =
                new EncryptedFullBackupDataProcessor(
                        ApplicationProvider.getApplicationContext(),
                        mExecutorService,
                        mock(CryptoBackupServer.class),
                        new SecureRandom(),
                        mTestSecondaryKey,
                        TEST_PACKAGE);
    }

    @After
    public void tearDown() {
        ShadowEncryptedFullBackupTask.reset();
    }

    @Test
    public void initiate_callTwice_throws() throws Exception {
        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(new byte[10]));

        assertThrows(
                IllegalStateException.class,
                () -> mFullBackupDataProcessor.initiate(new ByteArrayInputStream(new byte[10])));
    }

    @Test
    public void pushData_writesDataToTask() throws Exception {
        byte[] inputData = Bytes.concat(TEST_DATA_1, TEST_DATA_2);

        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(inputData));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        mFullBackupDataProcessor.pushData(TEST_DATA_2.length);
        finishBackupTask();
        mFullBackupDataProcessor.finish();

        byte[] result = ByteStreams.toByteArray(ShadowEncryptedFullBackupTask.sInputStream);
        assertThat(result).isEqualTo(Bytes.concat(TEST_DATA_1, TEST_DATA_2));
    }

    @Test
    public void pushData_noError_returnsOk() throws Exception {
        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(TEST_DATA_1));
        mFullBackupDataProcessor.start();
        int result = mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        finishBackupTask();
        mFullBackupDataProcessor.finish();

        assertThat(result).isEqualTo(BackupTransport.TRANSPORT_OK);
    }

    @Test
    public void pushData_ioExceptionOnCopy_returnsError() throws Exception {
        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(TEST_DATA_1));
        mFullBackupDataProcessor.start();

        // Close the stream so there's an IO error when the processor tries to write to it.
        ShadowEncryptedFullBackupTask.sInputStream.close();
        int result = mFullBackupDataProcessor.pushData(TEST_DATA_1.length);

        finishBackupTask();
        mFullBackupDataProcessor.finish();

        assertThat(result).isEqualTo(BackupTransport.TRANSPORT_ERROR);
    }

    @Test
    public void pushData_exceptionDuringUpload_returnsError() throws Exception {
        byte[] inputData = Bytes.concat(TEST_DATA_1, TEST_DATA_2);

        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(inputData));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        finishBackupTaskWithException(new IOException("Test exception"));
        int result = mFullBackupDataProcessor.pushData(TEST_DATA_2.length);

        assertThat(result).isEqualTo(BackupTransport.TRANSPORT_ERROR);
    }

    @Test
    public void pushData_quotaExceptionDuringUpload_doesNotLogAndReturnsQuotaExceeded()
            throws Exception {
        mFullBackupDataProcessor.attachCallbacks(mFullBackupCallbacks);
        byte[] inputData = Bytes.concat(TEST_DATA_1, TEST_DATA_2);

        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(inputData));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        finishBackupTaskWithException(new SizeQuotaExceededException());
        int result = mFullBackupDataProcessor.pushData(TEST_DATA_2.length);

        assertThat(result).isEqualTo(BackupTransport.TRANSPORT_QUOTA_EXCEEDED);

        verify(mFullBackupCallbacks, never()).onSuccess();
        verify(mFullBackupCallbacks, never())
                .onTransferFailed(); // FullBackupSession will handle this.
    }

    @Test
    public void pushData_unexpectedEncryptedBackup_logs() throws Exception {
        byte[] inputData = Bytes.concat(TEST_DATA_1, TEST_DATA_2);

        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(inputData));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        finishBackupTaskWithException(new GeneralSecurityException());
        int result = mFullBackupDataProcessor.pushData(TEST_DATA_2.length);

        assertThat(result).isEqualTo(BackupTransport.TRANSPORT_ERROR);
    }

    @Test
    public void pushData_permanentExceptionDuringUpload_callsErrorCallback() throws Exception {
        mFullBackupDataProcessor.attachCallbacks(mFullBackupCallbacks);
        byte[] inputData = Bytes.concat(TEST_DATA_1, TEST_DATA_2);

        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(inputData));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        finishBackupTaskWithException(new IOException());
        mFullBackupDataProcessor.pushData(TEST_DATA_2.length);

        verify(mFullBackupCallbacks, never()).onSuccess();
        verify(mFullBackupCallbacks).onTransferFailed();
    }

    @Test
    public void pushData_beforeInitiate_throws() {
        assertThrows(
                IllegalStateException.class,
                () -> mFullBackupDataProcessor.pushData(/*numBytes=*/ 10));
    }

    @Test
    public void cancel_cancelsTask() throws Exception {
        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(TEST_DATA_1));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        mFullBackupDataProcessor.cancel();

        assertThat(ShadowEncryptedFullBackupTask.sCancelled).isTrue();
    }

    @Test
    public void cancel_beforeInitiate_throws() {
        assertThrows(IllegalStateException.class, () -> mFullBackupDataProcessor.cancel());
    }

    @Test
    public void finish_noException_returnsTransportOk() throws Exception {
        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(TEST_DATA_1));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        finishBackupTask();
        int result = mFullBackupDataProcessor.finish();

        assertThat(result).isEqualTo(BackupTransport.TRANSPORT_OK);
    }

    @Test
    public void finish_exceptionDuringUpload_returnsTransportError() throws Exception {
        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(TEST_DATA_1));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        finishBackupTaskWithException(new IOException("Test exception"));
        int result = mFullBackupDataProcessor.finish();

        assertThat(result).isEqualTo(BackupTransport.TRANSPORT_ERROR);
    }

    @Test
    public void finish_successfulBackup_callsSuccessCallback() throws Exception {
        mFullBackupDataProcessor.attachCallbacks(mFullBackupCallbacks);

        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(TEST_DATA_1));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        finishBackupTask();
        mFullBackupDataProcessor.finish();

        verify(mFullBackupCallbacks).onSuccess();
        verify(mFullBackupCallbacks, never()).onTransferFailed();
    }

    @Test
    public void finish_backupFailedWithPermanentError_callsErrorCallback() throws Exception {
        mFullBackupDataProcessor.attachCallbacks(mFullBackupCallbacks);

        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(TEST_DATA_1));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        finishBackupTaskWithException(new IOException());
        mFullBackupDataProcessor.finish();

        verify(mFullBackupCallbacks, never()).onSuccess();
        verify(mFullBackupCallbacks).onTransferFailed();
    }

    @Test
    public void finish_backupFailedWithQuotaException_doesNotCallbackAndReturnsQuotaExceeded()
            throws Exception {
        mFullBackupDataProcessor.attachCallbacks(mFullBackupCallbacks);

        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(TEST_DATA_1));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        finishBackupTaskWithException(new SizeQuotaExceededException());
        int result = mFullBackupDataProcessor.finish();

        assertThat(result).isEqualTo(BackupTransport.TRANSPORT_QUOTA_EXCEEDED);
        verify(mFullBackupCallbacks, never()).onSuccess();
        verify(mFullBackupCallbacks, never())
                .onTransferFailed(); // FullBackupSession will handle this.
    }

    @Test
    public void finish_beforeInitiate_throws() {
        assertThrows(IllegalStateException.class, () -> mFullBackupDataProcessor.finish());
    }

    @Test
    public void handleCheckSizeRejectionZeroBytes_cancelsTask() throws Exception {
        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(new byte[10]));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.handleCheckSizeRejectionZeroBytes();

        assertThat(ShadowEncryptedFullBackupTask.sCancelled).isTrue();
    }

    @Test
    public void handleCheckSizeRejectionQuotaExceeded_cancelsTask() throws Exception {
        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(TEST_DATA_1));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        mFullBackupDataProcessor.handleCheckSizeRejectionQuotaExceeded();

        assertThat(ShadowEncryptedFullBackupTask.sCancelled).isTrue();
    }

    @Test
    public void handleSendBytesQuotaExceeded_cancelsTask() throws Exception {
        mFullBackupDataProcessor.initiate(new ByteArrayInputStream(TEST_DATA_1));
        mFullBackupDataProcessor.start();
        mFullBackupDataProcessor.pushData(TEST_DATA_1.length);
        mFullBackupDataProcessor.handleSendBytesQuotaExceeded();

        assertThat(ShadowEncryptedFullBackupTask.sCancelled).isTrue();
    }

    private void finishBackupTask() {
        mExecutorService.runNext();
    }

    private void finishBackupTaskWithException(Exception exception) {
        ShadowEncryptedFullBackupTask.sOnCallException = exception;
        finishBackupTask();
    }

    @Implements(EncryptedFullBackupTask.class)
    public static class ShadowEncryptedFullBackupTask {

        private static InputStream sInputStream;
        @Nullable private static Exception sOnCallException;
        private static boolean sCancelled;

        public void __constructor__(
                ProtoStore<ChunksMetadataProto.ChunkListing> chunkListingStore,
                TertiaryKeyManager tertiaryKeyManager,
                EncryptedBackupTask task,
                InputStream inputStream,
                String packageName,
                SecureRandom secureRandom) {
            sInputStream = inputStream;
        }

        @Implementation
        public Void call() throws Exception {
            if (sOnCallException != null) {
                throw sOnCallException;
            }

            return null;
        }

        @Implementation
        public void cancel() {
            sCancelled = true;
        }

        public static void reset() {
            sOnCallException = null;
            sCancelled = false;
        }
    }
}
