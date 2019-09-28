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

package com.android.server.backup.encryption;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.TertiaryKeyManager;
import com.android.server.backup.encryption.keys.TertiaryKeyRotationScheduler;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;
import com.android.server.backup.encryption.tasks.EncryptedFullBackupTask;
import com.android.server.backup.encryption.tasks.EncryptedFullRestoreTask;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;

import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

@RunWith(RobolectricTestRunner.class)
public class RoundTripTest {
    /** Amount of data we want to round trip in this test */
    private static final int TEST_DATA_SIZE = 1024 * 1024; // 1MB

    /** Buffer size used when reading data from the restore task */
    private static final int READ_BUFFER_SIZE = 1024; // 1024 byte buffer.

    /** Key parameters used for the secondary encryption key */
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 256;

    /** Package name for our test package */
    private static final String TEST_PACKAGE_NAME = "com.android.backup.test";

    /** The name we use to refer to our secondary key */
    private static final String TEST_KEY_ALIAS = "test/backup/KEY_ALIAS";

    /** Original data used for comparison after round trip */
    private final byte[] mOriginalData = new byte[TEST_DATA_SIZE];

    /** App context, used to store the key data and chunk listings */
    private Context mContext;

    /** The secondary key we're using for the test */
    private RecoverableKeyStoreSecondaryKey mSecondaryKey;

    /** Source of random material which is considered non-predictable in its' generation */
    private SecureRandom mSecureRandom = new SecureRandom();

    @Before
    public void setUp() throws NoSuchAlgorithmException {
        mContext = ApplicationProvider.getApplicationContext();
        mSecondaryKey = new RecoverableKeyStoreSecondaryKey(TEST_KEY_ALIAS, generateAesKey());
        fillBuffer(mOriginalData);
    }

    @Test
    public void testRoundTrip() throws Exception {
        byte[] backupData = performBackup(mOriginalData);
        assertThat(backupData).isNotEqualTo(mOriginalData);
        byte[] restoredData = performRestore(backupData);
        assertThat(restoredData).isEqualTo(mOriginalData);
    }

    /** Perform a backup and return the backed-up representation of the data */
    private byte[] performBackup(byte[] backupData) throws Exception {
        DummyServer dummyServer = new DummyServer();
        EncryptedFullBackupTask backupTask =
                EncryptedFullBackupTask.newInstance(
                        mContext,
                        dummyServer,
                        mSecureRandom,
                        mSecondaryKey,
                        TEST_PACKAGE_NAME,
                        new ByteArrayInputStream(backupData));
        backupTask.call();
        return dummyServer.mStoredData;
    }

    /** Perform a restore and resturn the bytes obtained from the restore process */
    private byte[] performRestore(byte[] backupData)
            throws IOException, NoSuchAlgorithmException, NoSuchPaddingException,
                    InvalidAlgorithmParameterException, InvalidKeyException,
                    IllegalBlockSizeException {
        ByteArrayOutputStream decryptedOutput = new ByteArrayOutputStream();

        EncryptedFullRestoreTask restoreTask =
                EncryptedFullRestoreTask.newInstance(
                        mContext, new FakeFullRestoreDownloader(backupData), getTertiaryKey());

        byte[] buffer = new byte[READ_BUFFER_SIZE];
        int bytesRead = restoreTask.readNextChunk(buffer);
        while (bytesRead != -1) {
            decryptedOutput.write(buffer, 0, bytesRead);
            bytesRead = restoreTask.readNextChunk(buffer);
        }

        return decryptedOutput.toByteArray();
    }

    /** Get the tertiary key for our test package from the key manager */
    private SecretKey getTertiaryKey()
            throws IllegalBlockSizeException, InvalidAlgorithmParameterException,
                    NoSuchAlgorithmException, IOException, NoSuchPaddingException,
                    InvalidKeyException {
        TertiaryKeyManager tertiaryKeyManager =
                new TertiaryKeyManager(
                        mContext,
                        mSecureRandom,
                        TertiaryKeyRotationScheduler.getInstance(mContext),
                        mSecondaryKey,
                        TEST_PACKAGE_NAME);
        return tertiaryKeyManager.getKey();
    }

    /** Fill a buffer with data in a predictable way */
    private void fillBuffer(byte[] buffer) {
        byte loopingCounter = 0;
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = loopingCounter;
            loopingCounter++;
        }
    }

    /** Generate a new, random, AES key */
    public static SecretKey generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(KEY_SIZE_BITS);
        return keyGenerator.generateKey();
    }

    /**
     * Dummy backup data endpoint. This stores the data so we can use it
     * in subsequent test steps.
     */
    private static class DummyServer implements CryptoBackupServer {
        private static final String DUMMY_DOC_ID = "DummyDoc";

        byte[] mStoredData = null;

        @Override
        public String uploadIncrementalBackup(
                String packageName,
                String oldDocId,
                byte[] diffScript,
                WrappedKeyProto.WrappedKey tertiaryKey) {
            throw new RuntimeException("Not Implemented");
        }

        @Override
        public String uploadNonIncrementalBackup(
                String packageName, byte[] data, WrappedKeyProto.WrappedKey tertiaryKey) {
            assertThat(packageName).isEqualTo(TEST_PACKAGE_NAME);
            mStoredData = data;
            return DUMMY_DOC_ID;
        }

        @Override
        public void setActiveSecondaryKeyAlias(
                String keyAlias, Map<String, WrappedKeyProto.WrappedKey> tertiaryKeys) {
            throw new RuntimeException("Not Implemented");
        }
    }

    /** Fake package wrapper which returns data from a byte array. */
    private static class FakeFullRestoreDownloader extends FullRestoreDownloader {
        private final ByteArrayInputStream mData;

        FakeFullRestoreDownloader(byte[] data) {
            // We override all methods of the superclass, so it does not require any collaborators.
            super();
            mData = new ByteArrayInputStream(data);
        }

        @Override
        public int readNextChunk(byte[] buffer) throws IOException {
            return mData.read(buffer);
        }

        @Override
        public void finish(FinishType finishType) {
            // Do nothing.
        }
    }
}
