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

import static org.testng.Assert.assertThrows;

import android.app.backup.BackupDataInput;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.ChunkHasher;
import com.android.server.backup.encryption.chunking.EncryptedChunk;
import com.android.server.backup.encryption.kv.KeyValueListingBuilder;
import com.android.server.backup.encryption.protos.nano.KeyValueListingProto.KeyValueListing;
import com.android.server.backup.encryption.protos.nano.KeyValuePairProto.KeyValuePair;
import com.android.server.backup.encryption.tasks.BackupEncrypter.Result;
import com.android.server.testing.shadows.DataEntity;
import com.android.server.testing.shadows.ShadowBackupDataInput;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Presubmit
@Config(shadows = {ShadowBackupDataInput.class})
public class KvBackupEncrypterTest {
    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BYTES = 16;

    private static final byte[] TEST_TERTIARY_KEY = Arrays.copyOf(new byte[0], 256 / Byte.SIZE);
    private static final String TEST_KEY_1 = "test_key_1";
    private static final String TEST_KEY_2 = "test_key_2";
    private static final String TEST_KEY_3 = "test_key_3";
    private static final byte[] TEST_VALUE_1 = {10, 11, 12};
    private static final byte[] TEST_VALUE_2 = {13, 14, 15};
    private static final byte[] TEST_VALUE_2B = {13, 14, 15, 16};
    private static final byte[] TEST_VALUE_3 = {16, 17, 18};

    private SecretKey mSecretKey;
    private ChunkHasher mChunkHasher;

    @Before
    public void setUp() {
        mSecretKey = new SecretKeySpec(TEST_TERTIARY_KEY, KEY_ALGORITHM);
        mChunkHasher = new ChunkHasher(mSecretKey);

        ShadowBackupDataInput.reset();
    }

    private KvBackupEncrypter createEncrypter(KeyValueListing keyValueListing) {
        KvBackupEncrypter encrypter = new KvBackupEncrypter(new BackupDataInput(null));
        encrypter.setOldKeyValueListing(keyValueListing);
        return encrypter;
    }

    @Test
    public void backup_noExistingBackup_encryptsAllPairs() throws Exception {
        ShadowBackupDataInput.addEntity(TEST_KEY_1, TEST_VALUE_1);
        ShadowBackupDataInput.addEntity(TEST_KEY_2, TEST_VALUE_2);

        KeyValueListing emptyKeyValueListing = new KeyValueListingBuilder().build();
        ImmutableSet<ChunkHash> emptyExistingChunks = ImmutableSet.of();
        KvBackupEncrypter encrypter = createEncrypter(emptyKeyValueListing);

        Result result =
                encrypter.backup(
                        mSecretKey, /*unusedFingerprintMixerSalt=*/ null, emptyExistingChunks);

        assertThat(result.getAllChunks()).hasSize(2);
        EncryptedChunk chunk1 = result.getNewChunks().get(0);
        EncryptedChunk chunk2 = result.getNewChunks().get(1);
        assertThat(chunk1.key()).isEqualTo(getChunkHash(TEST_KEY_1, TEST_VALUE_1));
        KeyValuePair pair1 = decryptChunk(chunk1);
        assertThat(pair1.key).isEqualTo(TEST_KEY_1);
        assertThat(pair1.value).isEqualTo(TEST_VALUE_1);
        assertThat(chunk2.key()).isEqualTo(getChunkHash(TEST_KEY_2, TEST_VALUE_2));
        KeyValuePair pair2 = decryptChunk(chunk2);
        assertThat(pair2.key).isEqualTo(TEST_KEY_2);
        assertThat(pair2.value).isEqualTo(TEST_VALUE_2);
    }

    @Test
    public void backup_existingBackup_encryptsNewAndUpdatedPairs() throws Exception {
        Pair<KeyValueListing, Set<ChunkHash>> initialResult = runInitialBackupOfPairs1And2();

        // Update key 2 and add the new key 3.
        ShadowBackupDataInput.reset();
        ShadowBackupDataInput.addEntity(TEST_KEY_2, TEST_VALUE_2B);
        ShadowBackupDataInput.addEntity(TEST_KEY_3, TEST_VALUE_3);

        KvBackupEncrypter encrypter = createEncrypter(initialResult.first);
        BackupEncrypter.Result secondResult =
                encrypter.backup(
                        mSecretKey, /*unusedFingerprintMixerSalt=*/ null, initialResult.second);

        assertThat(secondResult.getAllChunks()).hasSize(3);
        assertThat(secondResult.getNewChunks()).hasSize(2);
        EncryptedChunk newChunk2 = secondResult.getNewChunks().get(0);
        EncryptedChunk newChunk3 = secondResult.getNewChunks().get(1);
        assertThat(newChunk2.key()).isEqualTo(getChunkHash(TEST_KEY_2, TEST_VALUE_2B));
        assertThat(decryptChunk(newChunk2).value).isEqualTo(TEST_VALUE_2B);
        assertThat(newChunk3.key()).isEqualTo(getChunkHash(TEST_KEY_3, TEST_VALUE_3));
        assertThat(decryptChunk(newChunk3).value).isEqualTo(TEST_VALUE_3);
    }

    @Test
    public void backup_allChunksContainsHashesOfAllChunks() throws Exception {
        Pair<KeyValueListing, Set<ChunkHash>> initialResult = runInitialBackupOfPairs1And2();

        ShadowBackupDataInput.reset();
        ShadowBackupDataInput.addEntity(TEST_KEY_3, TEST_VALUE_3);

        KvBackupEncrypter encrypter = createEncrypter(initialResult.first);
        BackupEncrypter.Result secondResult =
                encrypter.backup(
                        mSecretKey, /*unusedFingerprintMixerSalt=*/ null, initialResult.second);

        assertThat(secondResult.getAllChunks())
                .containsExactly(
                        getChunkHash(TEST_KEY_1, TEST_VALUE_1),
                        getChunkHash(TEST_KEY_2, TEST_VALUE_2),
                        getChunkHash(TEST_KEY_3, TEST_VALUE_3));
    }

    @Test
    public void backup_negativeSize_deletesKeyFromExistingBackup() throws Exception {
        Pair<KeyValueListing, Set<ChunkHash>> initialResult = runInitialBackupOfPairs1And2();

        ShadowBackupDataInput.reset();
        ShadowBackupDataInput.addEntity(new DataEntity(TEST_KEY_2));

        KvBackupEncrypter encrypter = createEncrypter(initialResult.first);
        Result secondResult =
                encrypter.backup(
                        mSecretKey, /*unusedFingerprintMixerSalt=*/ null, initialResult.second);

        assertThat(secondResult.getAllChunks())
                .containsExactly(getChunkHash(TEST_KEY_1, TEST_VALUE_1));
        assertThat(secondResult.getNewChunks()).isEmpty();
    }

    @Test
    public void backup_returnsMessageDigestOverChunkHashes() throws Exception {
        Pair<KeyValueListing, Set<ChunkHash>> initialResult = runInitialBackupOfPairs1And2();

        ShadowBackupDataInput.reset();
        ShadowBackupDataInput.addEntity(TEST_KEY_3, TEST_VALUE_3);

        KvBackupEncrypter encrypter = createEncrypter(initialResult.first);
        Result secondResult =
                encrypter.backup(
                        mSecretKey, /*unusedFingerprintMixerSalt=*/ null, initialResult.second);

        MessageDigest messageDigest =
                MessageDigest.getInstance(BackupEncrypter.MESSAGE_DIGEST_ALGORITHM);
        ImmutableList<ChunkHash> sortedHashes =
                Ordering.natural()
                        .immutableSortedCopy(
                                ImmutableList.of(
                                        getChunkHash(TEST_KEY_1, TEST_VALUE_1),
                                        getChunkHash(TEST_KEY_2, TEST_VALUE_2),
                                        getChunkHash(TEST_KEY_3, TEST_VALUE_3)));
        messageDigest.update(sortedHashes.get(0).getHash());
        messageDigest.update(sortedHashes.get(1).getHash());
        messageDigest.update(sortedHashes.get(2).getHash());
        assertThat(secondResult.getDigest()).isEqualTo(messageDigest.digest());
    }

    @Test
    public void getNewKeyValueListing_noExistingBackup_returnsCorrectListing() throws Exception {
        KeyValueListing keyValueListing = runInitialBackupOfPairs1And2().first;

        assertThat(keyValueListing.entries.length).isEqualTo(2);
        assertThat(keyValueListing.entries[0].key).isEqualTo(TEST_KEY_1);
        assertThat(keyValueListing.entries[0].hash)
                .isEqualTo(getChunkHash(TEST_KEY_1, TEST_VALUE_1).getHash());
        assertThat(keyValueListing.entries[1].key).isEqualTo(TEST_KEY_2);
        assertThat(keyValueListing.entries[1].hash)
                .isEqualTo(getChunkHash(TEST_KEY_2, TEST_VALUE_2).getHash());
    }

    @Test
    public void getNewKeyValueListing_existingBackup_returnsCorrectListing() throws Exception {
        Pair<KeyValueListing, Set<ChunkHash>> initialResult = runInitialBackupOfPairs1And2();

        ShadowBackupDataInput.reset();
        ShadowBackupDataInput.addEntity(TEST_KEY_2, TEST_VALUE_2B);
        ShadowBackupDataInput.addEntity(TEST_KEY_3, TEST_VALUE_3);

        KvBackupEncrypter encrypter = createEncrypter(initialResult.first);
        encrypter.backup(mSecretKey, /*unusedFingerprintMixerSalt=*/ null, initialResult.second);

        ImmutableMap<String, ChunkHash> keyValueListing =
                listingToMap(encrypter.getNewKeyValueListing());
        assertThat(keyValueListing).hasSize(3);
        assertThat(keyValueListing)
                .containsEntry(TEST_KEY_1, getChunkHash(TEST_KEY_1, TEST_VALUE_1));
        assertThat(keyValueListing)
                .containsEntry(TEST_KEY_2, getChunkHash(TEST_KEY_2, TEST_VALUE_2B));
        assertThat(keyValueListing)
                .containsEntry(TEST_KEY_3, getChunkHash(TEST_KEY_3, TEST_VALUE_3));
    }

    @Test
    public void getNewKeyValueChunkListing_beforeBackup_throws() throws Exception {
        KvBackupEncrypter encrypter = createEncrypter(new KeyValueListing());
        assertThrows(IllegalStateException.class, encrypter::getNewKeyValueListing);
    }

    private ImmutableMap<String, ChunkHash> listingToMap(KeyValueListing listing) {
        // We can't use the ImmutableMap collector directly because it isn't supported in Android
        // guava.
        return ImmutableMap.copyOf(
                Arrays.stream(listing.entries)
                        .collect(
                                Collectors.toMap(
                                        entry -> entry.key, entry -> new ChunkHash(entry.hash))));
    }

    private Pair<KeyValueListing, Set<ChunkHash>> runInitialBackupOfPairs1And2() throws Exception {
        ShadowBackupDataInput.addEntity(TEST_KEY_1, TEST_VALUE_1);
        ShadowBackupDataInput.addEntity(TEST_KEY_2, TEST_VALUE_2);

        KeyValueListing initialKeyValueListing = new KeyValueListingBuilder().build();
        ImmutableSet<ChunkHash> initialExistingChunks = ImmutableSet.of();
        KvBackupEncrypter encrypter = createEncrypter(initialKeyValueListing);
        Result firstResult =
                encrypter.backup(
                        mSecretKey, /*unusedFingerprintMixerSalt=*/ null, initialExistingChunks);

        return Pair.create(
                encrypter.getNewKeyValueListing(), ImmutableSet.copyOf(firstResult.getAllChunks()));
    }

    private ChunkHash getChunkHash(String key, byte[] value) throws Exception {
        KeyValuePair pair = new KeyValuePair();
        pair.key = key;
        pair.value = Arrays.copyOf(value, value.length);
        return mChunkHasher.computeHash(KeyValuePair.toByteArray(pair));
    }

    private KeyValuePair decryptChunk(EncryptedChunk encryptedChunk) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(
                Cipher.DECRYPT_MODE,
                mSecretKey,
                new GCMParameterSpec(GCM_TAG_LENGTH_BYTES * Byte.SIZE, encryptedChunk.nonce()));
        byte[] decryptedBytes = cipher.doFinal(encryptedChunk.encryptedBytes());
        return KeyValuePair.parseFrom(decryptedBytes);
    }
}
