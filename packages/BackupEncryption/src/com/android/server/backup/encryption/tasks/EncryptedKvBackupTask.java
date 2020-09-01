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

import android.annotation.Nullable;
import android.app.backup.BackupDataInput;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.LockScreenRequiredException;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.chunking.ProtoStore;
import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKeyManager;
import com.android.server.backup.encryption.keys.TertiaryKeyManager;
import com.android.server.backup.encryption.keys.TertiaryKeyRotationScheduler;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.protos.nano.KeyValueListingProto;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.util.Optional;

// TODO(b/141975695): Create a base class for EncryptedKvBackupTask and EncryptedFullBackupTask.
/** Performs encrypted key value backup, handling rotating the tertiary key as necessary. */
public class EncryptedKvBackupTask {
    private static final String TAG = "EncryptedKvBackupTask";

    private final TertiaryKeyManager mTertiaryKeyManager;
    private final RecoverableKeyStoreSecondaryKey mSecondaryKey;
    private final ProtoStore<KeyValueListingProto.KeyValueListing> mKeyValueListingStore;
    private final ProtoStore<ChunksMetadataProto.ChunkListing> mChunkListingStore;
    private final KvBackupEncrypter mKvBackupEncrypter;
    private final EncryptedBackupTask mEncryptedBackupTask;
    private final String mPackageName;

    /** Constructs new instances of {@link EncryptedKvBackupTask}. */
    public static class EncryptedKvBackupTaskFactory {
        /**
         * Creates a new instance.
         *
         * <p>Either initializes encrypted backup or loads an existing secondary key as necessary.
         *
         * @param cryptoSettings to load secondary key state from
         * @param fileDescriptor to read the backup data from
         */
        public EncryptedKvBackupTask newInstance(
                Context context,
                SecureRandom secureRandom,
                CryptoBackupServer cryptoBackupServer,
                CryptoSettings cryptoSettings,
                RecoverableKeyStoreSecondaryKeyManager
                                .RecoverableKeyStoreSecondaryKeyManagerProvider
                        recoverableSecondaryKeyManagerProvider,
                ParcelFileDescriptor fileDescriptor,
                String packageName)
                throws IOException, UnrecoverableKeyException, LockScreenRequiredException,
                        InternalRecoveryServiceException, InvalidKeyException {
            RecoverableKeyStoreSecondaryKey secondaryKey =
                    new InitializeRecoverableSecondaryKeyTask(
                                    context,
                                    cryptoSettings,
                                    recoverableSecondaryKeyManagerProvider.get(),
                                    cryptoBackupServer)
                            .run();
            KvBackupEncrypter backupEncrypter =
                    new KvBackupEncrypter(new BackupDataInput(fileDescriptor.getFileDescriptor()));
            TertiaryKeyManager tertiaryKeyManager =
                    new TertiaryKeyManager(
                            context,
                            secureRandom,
                            TertiaryKeyRotationScheduler.getInstance(context),
                            secondaryKey,
                            packageName);

            return new EncryptedKvBackupTask(
                    tertiaryKeyManager,
                    ProtoStore.createKeyValueListingStore(context),
                    secondaryKey,
                    ProtoStore.createChunkListingStore(context),
                    backupEncrypter,
                    new EncryptedBackupTask(
                            cryptoBackupServer, secureRandom, packageName, backupEncrypter),
                    packageName);
        }
    }

    @VisibleForTesting
    EncryptedKvBackupTask(
            TertiaryKeyManager tertiaryKeyManager,
            ProtoStore<KeyValueListingProto.KeyValueListing> keyValueListingStore,
            RecoverableKeyStoreSecondaryKey secondaryKey,
            ProtoStore<ChunksMetadataProto.ChunkListing> chunkListingStore,
            KvBackupEncrypter kvBackupEncrypter,
            EncryptedBackupTask encryptedBackupTask,
            String packageName) {
        mTertiaryKeyManager = tertiaryKeyManager;
        mSecondaryKey = secondaryKey;
        mKeyValueListingStore = keyValueListingStore;
        mChunkListingStore = chunkListingStore;
        mKvBackupEncrypter = kvBackupEncrypter;
        mEncryptedBackupTask = encryptedBackupTask;
        mPackageName = packageName;
    }

    /**
     * Reads backup data from the file descriptor provided in the construtor, encrypts it and
     * uploads it to the server.
     *
     * <p>The {@code incremental} flag indicates if the backup data provided is incremental or a
     * complete set. Incremental backup is not possible if no previous crypto state exists, or the
     * tertiary key must be rotated in the next backup. If the caller requests incremental backup
     * but it is not possible, then the backup will not start and this method will throw {@link
     * NonIncrementalBackupRequiredException}.
     *
     * <p>TODO(b/70704456): Update return code to indicate that we require non-incremental backup.
     *
     * @param incremental {@code true} if the data provided is a diff from the previous backup,
     *     {@code false} if it is a complete set
     * @throws NonIncrementalBackupRequiredException if the caller provides an incremental backup but the task
     *     requires non-incremental backup
     */
    public void performBackup(boolean incremental)
            throws GeneralSecurityException, IOException, NoSuchMethodException,
            InstantiationException, IllegalAccessException, InvocationTargetException,
            NonIncrementalBackupRequiredException {
        if (mTertiaryKeyManager.wasKeyRotated()) {
            Slog.d(TAG, "Tertiary key is new so clearing package state.");
            deleteListings(mPackageName);
        }

        Optional<Pair<KeyValueListingProto.KeyValueListing, ChunksMetadataProto.ChunkListing>>
                oldListings = getListingsAndEnsureConsistency(mPackageName);

        if (oldListings.isPresent() && !incremental) {
            Slog.d(
                    TAG,
                    "Non-incremental backup requested but incremental state existed, clearing it");
            deleteListings(mPackageName);
            oldListings = Optional.empty();
        }

        if (!oldListings.isPresent() && incremental) {
            // If we don't have any state then we require a non-incremental backup, but this backup
            // is incremental.
            throw new NonIncrementalBackupRequiredException();
        }

        if (oldListings.isPresent()) {
            mKvBackupEncrypter.setOldKeyValueListing(oldListings.get().first);
        }

        ChunksMetadataProto.ChunkListing newChunkListing;
        if (oldListings.isPresent()) {
            Slog.v(TAG, "Old listings existed, performing incremental backup");
            newChunkListing =
                    mEncryptedBackupTask.performIncrementalBackup(
                            mTertiaryKeyManager.getKey(),
                            mTertiaryKeyManager.getWrappedKey(),
                            oldListings.get().second);
        } else {
            Slog.v(TAG, "Old listings did not exist, performing non-incremental backup");
            // kv backups don't use this salt because they don't involve content-defined chunking.
            byte[] fingerprintMixerSalt = null;
            newChunkListing =
                    mEncryptedBackupTask.performNonIncrementalBackup(
                            mTertiaryKeyManager.getKey(),
                            mTertiaryKeyManager.getWrappedKey(),
                            fingerprintMixerSalt);
        }

        Slog.v(TAG, "Backup and upload succeeded, saving new listings");
        saveListings(mPackageName, mKvBackupEncrypter.getNewKeyValueListing(), newChunkListing);
    }

    private Optional<Pair<KeyValueListingProto.KeyValueListing, ChunksMetadataProto.ChunkListing>>
            getListingsAndEnsureConsistency(String packageName)
                    throws IOException, InvocationTargetException, NoSuchMethodException,
                            InstantiationException, IllegalAccessException {
        Optional<KeyValueListingProto.KeyValueListing> keyValueListing =
                mKeyValueListingStore.loadProto(packageName);
        Optional<ChunksMetadataProto.ChunkListing> chunkListing =
                mChunkListingStore.loadProto(packageName);

        // Normally either both protos exist or neither exist, but we correct this just in case.
        boolean bothPresent = keyValueListing.isPresent() && chunkListing.isPresent();
        if (!bothPresent) {
            Slog.d(
                    TAG,
                    "Both listing were not present, clearing state, key value="
                            + keyValueListing.isPresent()
                            + ", chunk="
                            + chunkListing.isPresent());
            deleteListings(packageName);
            return Optional.empty();
        }

        return Optional.of(Pair.create(keyValueListing.get(), chunkListing.get()));
    }

    private void saveListings(
            String packageName,
            KeyValueListingProto.KeyValueListing keyValueListing,
            ChunksMetadataProto.ChunkListing chunkListing) {
        try {
            mKeyValueListingStore.saveProto(packageName, keyValueListing);
            mChunkListingStore.saveProto(packageName, chunkListing);
        } catch (IOException e) {
            // If a problem occurred while saving either listing then they may be inconsistent, so
            // delete
            // both.
            Slog.w(TAG, "Unable to save listings, deleting both for consistency", e);
            deleteListings(packageName);
        }
    }

    private void deleteListings(String packageName) {
        mKeyValueListingStore.deleteProto(packageName);
        mChunkListingStore.deleteProto(packageName);
    }
}
