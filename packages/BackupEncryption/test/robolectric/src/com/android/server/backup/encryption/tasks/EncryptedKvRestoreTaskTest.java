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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.os.ParcelFileDescriptor;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.ChunkHasher;
import com.android.server.backup.testing.CryptoTestUtils;
import com.android.server.testing.shadows.DataEntity;
import com.android.server.testing.shadows.ShadowBackupDataOutput;

import com.google.protobuf.nano.MessageNano;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(shadows = {ShadowBackupDataOutput.class})
@RunWith(RobolectricTestRunner.class)
public class EncryptedKvRestoreTaskTest {
    private static final String TEST_KEY_1 = "test_key_1";
    private static final String TEST_KEY_2 = "test_key_2";
    private static final String TEST_KEY_3 = "test_key_3";
    private static final byte[] TEST_VALUE_1 = {1, 2, 3};
    private static final byte[] TEST_VALUE_2 = {4, 5, 6};
    private static final byte[] TEST_VALUE_3 = {20, 25, 30, 35};

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File temporaryDirectory;

    @Mock private ParcelFileDescriptor mParcelFileDescriptor;
    @Mock private ChunkHasher mChunkHasher;
    @Mock private FullRestoreToFileTask mFullRestoreToFileTask;
    @Mock private BackupFileDecryptorTask mBackupFileDecryptorTask;

    private EncryptedKvRestoreTask task;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mChunkHasher.computeHash(any()))
                .thenAnswer(invocation -> fakeHash(invocation.getArgument(0)));
        doAnswer(invocation -> writeTestPairsToFile(invocation.getArgument(0)))
                .when(mFullRestoreToFileTask)
                .restoreToFile(any());
        doAnswer(
                        invocation ->
                                readPairsFromFile(
                                        invocation.getArgument(0), invocation.getArgument(1)))
                .when(mBackupFileDecryptorTask)
                .decryptFile(any(), any());

        temporaryDirectory = temporaryFolder.newFolder();
        task =
                new EncryptedKvRestoreTask(
                        temporaryDirectory,
                        mChunkHasher,
                        mFullRestoreToFileTask,
                        mBackupFileDecryptorTask);
    }

    @Test
    public void testGetRestoreData_writesPairsToOutputInOrder() throws Exception {
        task.getRestoreData(mParcelFileDescriptor);

        assertThat(ShadowBackupDataOutput.getEntities())
                .containsExactly(
                        new DataEntity(TEST_KEY_1, TEST_VALUE_1),
                        new DataEntity(TEST_KEY_2, TEST_VALUE_2),
                        new DataEntity(TEST_KEY_3, TEST_VALUE_3))
                .inOrder();
    }

    @Test
    public void testGetRestoreData_exceptionDuringDecryption_throws() throws Exception {
        doThrow(IOException.class).when(mBackupFileDecryptorTask).decryptFile(any(), any());
        assertThrows(IOException.class, () -> task.getRestoreData(mParcelFileDescriptor));
    }

    @Test
    public void testGetRestoreData_exceptionDuringDownload_throws() throws Exception {
        doThrow(IOException.class).when(mFullRestoreToFileTask).restoreToFile(any());
        assertThrows(IOException.class, () -> task.getRestoreData(mParcelFileDescriptor));
    }

    @Test
    public void testGetRestoreData_exceptionDuringDecryption_deletesTemporaryFiles() throws Exception {
        doThrow(InvalidKeyException.class).when(mBackupFileDecryptorTask).decryptFile(any(), any());
        assertThrows(InvalidKeyException.class, () -> task.getRestoreData(mParcelFileDescriptor));
        assertThat(temporaryDirectory.listFiles()).isEmpty();
    }

    @Test
    public void testGetRestoreData_exceptionDuringDownload_deletesTemporaryFiles() throws Exception {
        doThrow(IOException.class).when(mFullRestoreToFileTask).restoreToFile(any());
        assertThrows(IOException.class, () -> task.getRestoreData(mParcelFileDescriptor));
        assertThat(temporaryDirectory.listFiles()).isEmpty();
    }

    private static Void writeTestPairsToFile(File file) throws IOException {
        // Write the pairs out of order to check the task sorts them.
        Set<byte[]> pairs =
                new HashSet<>(
                        Arrays.asList(
                                createPair(TEST_KEY_1, TEST_VALUE_1),
                                createPair(TEST_KEY_3, TEST_VALUE_3),
                                createPair(TEST_KEY_2, TEST_VALUE_2)));

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            oos.writeObject(pairs);
        }
        return null;
    }

    private static Void readPairsFromFile(File file, DecryptedChunkOutput decryptedChunkOutput)
            throws IOException, ClassNotFoundException, InvalidKeyException,
                    NoSuchAlgorithmException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file));
                DecryptedChunkOutput output = decryptedChunkOutput.open()) {
            Set<byte[]> pairs = readPairs(ois);
            for (byte[] pair : pairs) {
                output.processChunk(pair, pair.length);
            }
        }

        return null;
    }

    private static byte[] createPair(String key, byte[] value) {
        return MessageNano.toByteArray(CryptoTestUtils.newPair(key, value));
    }

    @SuppressWarnings("unchecked") // deserialization.
    private static Set<byte[]> readPairs(ObjectInputStream ois)
            throws IOException, ClassNotFoundException {
        return (Set<byte[]>) ois.readObject();
    }

    private static ChunkHash fakeHash(byte[] data) {
        return new ChunkHash(Arrays.copyOf(data, ChunkHash.HASH_LENGTH_BYTES));
    }
}
