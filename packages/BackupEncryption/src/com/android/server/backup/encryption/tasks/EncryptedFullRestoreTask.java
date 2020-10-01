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

import android.annotation.Nullable;
import android.content.Context;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.encryption.FullRestoreDataProcessor;
import com.android.server.backup.encryption.FullRestoreDownloader;
import com.android.server.backup.encryption.StreamUtils;
import com.android.server.backup.encryption.chunking.DecryptedChunkFileOutput;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;

/** Downloads the encrypted backup file, decrypts it and passes the data to backup manager. */
public class EncryptedFullRestoreTask implements FullRestoreDataProcessor {
    private static final String DEFAULT_TEMPORARY_FOLDER = "encrypted_restore_temp";
    private static final String ENCRYPTED_FILE_NAME = "encrypted_restore";
    private static final String DECRYPTED_FILE_NAME = "decrypted_restore";

    private final FullRestoreToFileTask mFullRestoreToFileTask;
    private final BackupFileDecryptorTask mBackupFileDecryptorTask;
    private final File mEncryptedFile;
    private final File mDecryptedFile;
    @Nullable private InputStream mDecryptedFileInputStream;

    /**
     * Creates a new task which stores temporary files in the files directory.
     *
     * @param fullRestoreDownloader which will download the backup file
     * @param tertiaryKey which the backup file is encrypted with
     */
    public static EncryptedFullRestoreTask newInstance(
            Context context, FullRestoreDownloader fullRestoreDownloader, SecretKey tertiaryKey)
            throws NoSuchAlgorithmException, NoSuchPaddingException {
        File temporaryFolder = new File(context.getFilesDir(), DEFAULT_TEMPORARY_FOLDER);
        temporaryFolder.mkdirs();
        return new EncryptedFullRestoreTask(
                temporaryFolder, fullRestoreDownloader, new BackupFileDecryptorTask(tertiaryKey));
    }

    @VisibleForTesting
    EncryptedFullRestoreTask(
            File temporaryFolder,
            FullRestoreDownloader fullRestoreDownloader,
            BackupFileDecryptorTask backupFileDecryptorTask) {
        checkArgument(temporaryFolder.isDirectory(), "Temporary folder must be existing directory");

        mEncryptedFile = new File(temporaryFolder, ENCRYPTED_FILE_NAME);
        mDecryptedFile = new File(temporaryFolder, DECRYPTED_FILE_NAME);

        mFullRestoreToFileTask = new FullRestoreToFileTask(fullRestoreDownloader);
        mBackupFileDecryptorTask = backupFileDecryptorTask;
    }

    /**
     * Reads the next decrypted bytes into the given buffer.
     *
     * <p>During the first call this method will download the backup file from the server, decrypt
     * it and save it to disk. It will then read the bytes from the file on disk.
     *
     * <p>Once this method has read all the bytes of the file, the caller must call {@link #finish}
     * to clean up.
     *
     * @return the number of bytes read, or {@code -1} on reaching the end of the file
     */
    @Override
    public int readNextChunk(byte[] buffer) throws IOException {
        if (mDecryptedFileInputStream == null) {
            try {
                mDecryptedFileInputStream = downloadAndDecryptBackup();
            } catch (BadPaddingException
                    | InvalidKeyException
                    | NoSuchAlgorithmException
                    | IllegalBlockSizeException
                    | ShortBufferException
                    | EncryptedRestoreException
                    | InvalidAlgorithmParameterException e) {
                throw new IOException("Encryption issue", e);
            }
        }

        return mDecryptedFileInputStream.read(buffer);
    }

    private InputStream downloadAndDecryptBackup()
            throws IOException, BadPaddingException, InvalidKeyException, NoSuchAlgorithmException,
                    IllegalBlockSizeException, ShortBufferException, EncryptedRestoreException,
                    InvalidAlgorithmParameterException {
        mFullRestoreToFileTask.restoreToFile(mEncryptedFile);
        mBackupFileDecryptorTask.decryptFile(
                mEncryptedFile, new DecryptedChunkFileOutput(mDecryptedFile));
        mEncryptedFile.delete();
        return new BufferedInputStream(new FileInputStream(mDecryptedFile));
    }

    /** Cleans up temporary files. */
    @Override
    public void finish(FullRestoreDownloader.FinishType unusedFinishType) {
        // The download is finished and log sent during RestoreToFileTask#restoreToFile(), so we
        // don't need to do either of those things here.

        StreamUtils.closeQuietly(mDecryptedFileInputStream);
        mEncryptedFile.delete();
        mDecryptedFile.delete();
    }
}
