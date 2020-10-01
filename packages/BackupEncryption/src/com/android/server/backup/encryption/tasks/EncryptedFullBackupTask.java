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

import android.content.Context;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.encryption.StreamUtils;
import com.android.server.backup.encryption.chunking.ProtoStore;
import com.android.server.backup.encryption.chunking.cdc.FingerprintMixer;
import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.TertiaryKeyManager;
import com.android.server.backup.encryption.keys.TertiaryKeyRotationScheduler;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.ChunkListing;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Callable;

import javax.crypto.SecretKey;

/**
 * Task which reads a stream of plaintext full backup data, chunks it, encrypts it and uploads it to
 * the server.
 *
 * <p>Once the backup completes or fails, closes the input stream.
 */
public class EncryptedFullBackupTask implements Callable<Void> {
    private static final String TAG = "EncryptedFullBackupTask";

    private static final int MIN_CHUNK_SIZE_BYTES = 2 * 1024;
    private static final int MAX_CHUNK_SIZE_BYTES = 64 * 1024;
    private static final int AVERAGE_CHUNK_SIZE_BYTES = 4 * 1024;

    // TODO(b/69350270): Remove this hard-coded salt and related logic once we feel confident that
    // incremental backup has happened at least once for all existing packages/users since we moved
    // to
    // using a randomly generated salt.
    //
    // The hard-coded fingerprint mixer salt was used for a short time period before replaced by one
    // that is randomly generated on initial non-incremental backup and stored in ChunkListing to be
    // reused for succeeding incremental backups. If an old ChunkListing does not have a
    // fingerprint_mixer_salt, we assume that it was last backed up before a randomly generated salt
    // is used so we use the hardcoded salt and set ChunkListing#fingerprint_mixer_salt to this
    // value.
    // Eventually all backup ChunkListings will have this field set and then we can remove the
    // default
    // value in the code.
    static final byte[] DEFAULT_FINGERPRINT_MIXER_SALT =
            Arrays.copyOf(new byte[] {20, 23}, FingerprintMixer.SALT_LENGTH_BYTES);

    private final ProtoStore<ChunkListing> mChunkListingStore;
    private final TertiaryKeyManager mTertiaryKeyManager;
    private final InputStream mInputStream;
    private final EncryptedBackupTask mTask;
    private final String mPackageName;
    private final SecureRandom mSecureRandom;

    /** Creates a new instance with the default min, max and average chunk sizes. */
    public static EncryptedFullBackupTask newInstance(
            Context context,
            CryptoBackupServer cryptoBackupServer,
            SecureRandom secureRandom,
            RecoverableKeyStoreSecondaryKey secondaryKey,
            String packageName,
            InputStream inputStream)
            throws IOException {
        EncryptedBackupTask encryptedBackupTask =
                new EncryptedBackupTask(
                        cryptoBackupServer,
                        secureRandom,
                        packageName,
                        new BackupStreamEncrypter(
                                inputStream,
                                MIN_CHUNK_SIZE_BYTES,
                                MAX_CHUNK_SIZE_BYTES,
                                AVERAGE_CHUNK_SIZE_BYTES));
        TertiaryKeyManager tertiaryKeyManager =
                new TertiaryKeyManager(
                        context,
                        secureRandom,
                        TertiaryKeyRotationScheduler.getInstance(context),
                        secondaryKey,
                        packageName);

        return new EncryptedFullBackupTask(
                ProtoStore.createChunkListingStore(context),
                tertiaryKeyManager,
                encryptedBackupTask,
                inputStream,
                packageName,
                new SecureRandom());
    }

    @VisibleForTesting
    EncryptedFullBackupTask(
            ProtoStore<ChunkListing> chunkListingStore,
            TertiaryKeyManager tertiaryKeyManager,
            EncryptedBackupTask task,
            InputStream inputStream,
            String packageName,
            SecureRandom secureRandom) {
        mChunkListingStore = chunkListingStore;
        mTertiaryKeyManager = tertiaryKeyManager;
        mInputStream = inputStream;
        mTask = task;
        mPackageName = packageName;
        mSecureRandom = secureRandom;
    }

    @Override
    public Void call() throws Exception {
        try {
            Optional<ChunkListing> maybeOldChunkListing =
                    mChunkListingStore.loadProto(mPackageName);

            if (maybeOldChunkListing.isPresent()) {
                Slog.i(TAG, "Found previous chunk listing for " + mPackageName);
            }

            // If the key has been rotated then we must re-encrypt all of the backup data.
            if (mTertiaryKeyManager.wasKeyRotated()) {
                Slog.i(
                        TAG,
                        "Key was rotated or newly generated for "
                                + mPackageName
                                + ", so performing a full backup.");
                maybeOldChunkListing = Optional.empty();
                mChunkListingStore.deleteProto(mPackageName);
            }

            SecretKey tertiaryKey = mTertiaryKeyManager.getKey();
            WrappedKeyProto.WrappedKey wrappedTertiaryKey = mTertiaryKeyManager.getWrappedKey();

            ChunkListing newChunkListing;
            if (!maybeOldChunkListing.isPresent()) {
                byte[] fingerprintMixerSalt = new byte[FingerprintMixer.SALT_LENGTH_BYTES];
                mSecureRandom.nextBytes(fingerprintMixerSalt);
                newChunkListing =
                        mTask.performNonIncrementalBackup(
                                tertiaryKey, wrappedTertiaryKey, fingerprintMixerSalt);
            } else {
                ChunkListing oldChunkListing = maybeOldChunkListing.get();

                if (oldChunkListing.fingerprintMixerSalt == null
                        || oldChunkListing.fingerprintMixerSalt.length == 0) {
                    oldChunkListing.fingerprintMixerSalt = DEFAULT_FINGERPRINT_MIXER_SALT;
                }

                newChunkListing =
                        mTask.performIncrementalBackup(
                                tertiaryKey, wrappedTertiaryKey, oldChunkListing);
            }

            mChunkListingStore.saveProto(mPackageName, newChunkListing);
            Slog.v(TAG, "Saved chunk listing for " + mPackageName);
        } catch (IOException e) {
            Slog.e(TAG, "Storage exception, wiping state");
            mChunkListingStore.deleteProto(mPackageName);
            throw e;
        } finally {
            StreamUtils.closeQuietly(mInputStream);
        }

        return null;
    }

    /**
     * Signals to the task that the backup has been cancelled. If the upload has not yet started
     * then the task will not upload any data to the server or save the new chunk listing.
     *
     * <p>You must then terminate the input stream.
     */
    public void cancel() {
        mTask.cancel();
    }
}
