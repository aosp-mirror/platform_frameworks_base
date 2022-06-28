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

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.keys.KeyWrapUtils;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;
import com.android.server.backup.encryption.tasks.EncryptedKvBackupTask;
import com.android.server.backup.encryption.tasks.EncryptedKvRestoreTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Map;

public class KeyValueEncrypter {
    private static final String TAG = "KeyValueEncrypter";

    private final Context mContext;
    private final EncryptionKeyHelper mKeyHelper;

    public KeyValueEncrypter(Context context) {
        mContext = context;
        mKeyHelper = new EncryptionKeyHelper(mContext);
    }

    public void encryptKeyValueData(
            String packageName, ParcelFileDescriptor inputFd, OutputStream outputStream)
            throws Exception {
        EncryptedKvBackupTask.EncryptedKvBackupTaskFactory backupTaskFactory =
                new EncryptedKvBackupTask.EncryptedKvBackupTaskFactory();
        EncryptedKvBackupTask backupTask =
                backupTaskFactory.newInstance(
                        mContext,
                        new SecureRandom(),
                        new FileBackupServer(outputStream),
                        CryptoSettings.getInstance(mContext),
                        mKeyHelper.getKeyManagerProvider(),
                        inputFd,
                        packageName);
        backupTask.performBackup(/* incremental */ false);
    }

    public void decryptKeyValueData(String packageName,
            InputStream encryptedInputStream, ParcelFileDescriptor outputFd) throws Exception {
        RecoverableKeyStoreSecondaryKey secondaryKey = mKeyHelper.getActiveSecondaryKey();

        EncryptedKvRestoreTask.EncryptedKvRestoreTaskFactory restoreTaskFactory =
                new EncryptedKvRestoreTask.EncryptedKvRestoreTaskFactory();
        EncryptedKvRestoreTask restoreTask =
                restoreTaskFactory.newInstance(
                        mContext,
                        mKeyHelper.getKeyManagerProvider(),
                        new InputStreamFullRestoreDownloader(encryptedInputStream),
                        secondaryKey.getAlias(),
                        KeyWrapUtils.wrap(
                                secondaryKey.getSecretKey(),
                                mKeyHelper.getTertiaryKey(packageName, secondaryKey)));

        restoreTask.getRestoreData(outputFd);
    }

    // TODO(b/142455725): Extract into a commong class.
    private static class FileBackupServer implements CryptoBackupServer {
        private static final String EMPTY_DOC_ID = "";

        private final OutputStream mOutputStream;

        FileBackupServer(OutputStream outputStream) {
            mOutputStream = outputStream;
        }

        @Override
        public String uploadIncrementalBackup(
                String packageName,
                String oldDocId,
                byte[] diffScript,
                WrappedKeyProto.WrappedKey tertiaryKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String uploadNonIncrementalBackup(
                String packageName, byte[] data, WrappedKeyProto.WrappedKey tertiaryKey) {
            try {
                mOutputStream.write(data);
            } catch (IOException e) {
                Log.w(TAG, "Failed to write encrypted data to file: ", e);
            }

            return EMPTY_DOC_ID;
        }

        @Override
        public void setActiveSecondaryKeyAlias(
                String keyAlias, Map<String, WrappedKeyProto.WrappedKey> tertiaryKeys) {
            // Do nothing.
        }
    }

    // TODO(b/142455725): Extract into a commong class.
    private static class InputStreamFullRestoreDownloader extends FullRestoreDownloader {
        private final InputStream mInputStream;

        InputStreamFullRestoreDownloader(InputStream inputStream) {
            mInputStream = inputStream;
        }

        @Override
        public int readNextChunk(byte[] buffer) throws IOException {
            return mInputStream.read(buffer);
        }

        @Override
        public void finish(FinishType finishType) {
            try {
                mInputStream.close();
            } catch (IOException e) {
                Log.w(TAG, "Error while reading restore data");
            }
        }
    }
}
