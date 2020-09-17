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

import static com.android.internal.util.Preconditions.checkArgument;

import android.app.backup.BackupDataOutput;
import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.encryption.FullRestoreDownloader;
import com.android.server.backup.encryption.chunking.ChunkHasher;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKeyManager;
import com.android.server.backup.encryption.keys.RestoreKeyFetcher;
import com.android.server.backup.encryption.kv.DecryptedChunkKvOutput;
import com.android.server.backup.encryption.protos.nano.KeyValuePairProto;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import java.io.File;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;

/**
 * Performs a key value restore by downloading the backup set, decrypting it and writing it to the
 * file provided by backup manager.
 */
public class EncryptedKvRestoreTask {
    private static final String ENCRYPTED_FILE_NAME = "encrypted_kv";

    private final File mTemporaryFolder;
    private final ChunkHasher mChunkHasher;
    private final FullRestoreToFileTask mFullRestoreToFileTask;
    private final BackupFileDecryptorTask mBackupFileDecryptorTask;

    /** Constructs new instances of the task. */
    public static class EncryptedKvRestoreTaskFactory {
        /**
         * Constructs a new instance.
         *
         * <p>Fetches the appropriate secondary key and uses this to unwrap the tertiary key. Stores
         * temporary files in {@link Context#getFilesDir()}.
         */
        public EncryptedKvRestoreTask newInstance(
                Context context,
                RecoverableKeyStoreSecondaryKeyManager
                                .RecoverableKeyStoreSecondaryKeyManagerProvider
                        recoverableSecondaryKeyManagerProvider,
                FullRestoreDownloader fullRestoreDownloader,
                String secondaryKeyAlias,
                WrappedKeyProto.WrappedKey wrappedTertiaryKey)
                throws EncryptedRestoreException, NoSuchAlgorithmException, NoSuchPaddingException,
                        KeyException, InvalidAlgorithmParameterException {
            SecretKey tertiaryKey =
                    RestoreKeyFetcher.unwrapTertiaryKey(
                            recoverableSecondaryKeyManagerProvider,
                            secondaryKeyAlias,
                            wrappedTertiaryKey);

            return new EncryptedKvRestoreTask(
                    context.getFilesDir(),
                    new ChunkHasher(tertiaryKey),
                    new FullRestoreToFileTask(fullRestoreDownloader),
                    new BackupFileDecryptorTask(tertiaryKey));
        }
    }

    @VisibleForTesting
    EncryptedKvRestoreTask(
            File temporaryFolder,
            ChunkHasher chunkHasher,
            FullRestoreToFileTask fullRestoreToFileTask,
            BackupFileDecryptorTask backupFileDecryptorTask) {
        checkArgument(
                temporaryFolder.isDirectory(), "Temporary folder must be an existing directory");

        mTemporaryFolder = temporaryFolder;
        mChunkHasher = chunkHasher;
        mFullRestoreToFileTask = fullRestoreToFileTask;
        mBackupFileDecryptorTask = backupFileDecryptorTask;
    }

    /**
     * Runs the restore, writing the pairs in lexicographical order to the given file descriptor.
     *
     * <p>This will block for the duration of the restore.
     *
     * @throws EncryptedRestoreException if there is a problem decrypting or verifying the backup
     */
    public void getRestoreData(ParcelFileDescriptor output)
            throws IOException, EncryptedRestoreException, BadPaddingException,
                    InvalidAlgorithmParameterException, NoSuchAlgorithmException,
                    IllegalBlockSizeException, ShortBufferException, InvalidKeyException {
        File encryptedFile = new File(mTemporaryFolder, ENCRYPTED_FILE_NAME);
        try {
            downloadDecryptAndWriteBackup(encryptedFile, output);
        } finally {
            encryptedFile.delete();
        }
    }

    private void downloadDecryptAndWriteBackup(File encryptedFile, ParcelFileDescriptor output)
            throws EncryptedRestoreException, IOException, BadPaddingException, InvalidKeyException,
                    NoSuchAlgorithmException, IllegalBlockSizeException, ShortBufferException,
                    InvalidAlgorithmParameterException {
        mFullRestoreToFileTask.restoreToFile(encryptedFile);
        DecryptedChunkKvOutput decryptedChunkKvOutput = new DecryptedChunkKvOutput(mChunkHasher);
        mBackupFileDecryptorTask.decryptFile(encryptedFile, decryptedChunkKvOutput);

        BackupDataOutput backupDataOutput = new BackupDataOutput(output.getFileDescriptor());
        for (KeyValuePairProto.KeyValuePair pair : decryptedChunkKvOutput.getPairs()) {
            backupDataOutput.writeEntityHeader(pair.key, pair.value.length);
            backupDataOutput.writeEntityData(pair.value, pair.value.length);
        }
    }
}
