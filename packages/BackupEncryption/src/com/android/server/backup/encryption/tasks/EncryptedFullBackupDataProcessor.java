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

import static com.android.internal.util.Preconditions.checkState;

import android.annotation.Nullable;
import android.app.backup.BackupTransport;
import android.content.Context;
import android.util.Slog;

import com.android.server.backup.encryption.FullBackupDataProcessor;
import com.android.server.backup.encryption.StreamUtils;
import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Accepts backup data from a {@link InputStream} and passes it to the encrypted full data backup
 * path.
 */
public class EncryptedFullBackupDataProcessor implements FullBackupDataProcessor {

    private static final String TAG = "EncryptedFullBackupDP";

    private final Context mContext;
    private final ExecutorService mExecutorService;
    private final CryptoBackupServer mCryptoBackupServer;
    private final SecureRandom mSecureRandom;
    private final RecoverableKeyStoreSecondaryKey mSecondaryKey;
    private final String mPackageName;

    @Nullable private InputStream mInputStream;
    @Nullable private PipedOutputStream mOutputStream;
    @Nullable private EncryptedFullBackupTask mBackupTask;
    @Nullable private Future<Void> mBackupTaskFuture;
    @Nullable private FullBackupCallbacks mFullBackupCallbacks;

    public EncryptedFullBackupDataProcessor(
            Context context,
            ExecutorService executorService,
            CryptoBackupServer cryptoBackupServer,
            SecureRandom secureRandom,
            RecoverableKeyStoreSecondaryKey secondaryKey,
            String packageName) {
        mContext = Objects.requireNonNull(context);
        mExecutorService = Objects.requireNonNull(executorService);
        mCryptoBackupServer = Objects.requireNonNull(cryptoBackupServer);
        mSecureRandom = Objects.requireNonNull(secureRandom);
        mSecondaryKey = Objects.requireNonNull(secondaryKey);
        mPackageName = Objects.requireNonNull(packageName);
    }

    @Override
    public boolean initiate(InputStream inputStream) throws IOException {
        checkState(mBackupTask == null, "initiate() twice");

        this.mInputStream = inputStream;
        mOutputStream = new PipedOutputStream();

        mBackupTask =
                EncryptedFullBackupTask.newInstance(
                        mContext,
                        mCryptoBackupServer,
                        mSecureRandom,
                        mSecondaryKey,
                        mPackageName,
                        new PipedInputStream(mOutputStream));

        return true;
    }

    @Override
    public void start() {
        checkState(mBackupTask != null, "start() before initiate()");
        mBackupTaskFuture = mExecutorService.submit(mBackupTask);
    }

    @Override
    public int pushData(int numBytes) {
        checkState(
                mBackupTaskFuture != null && mInputStream != null && mOutputStream != null,
                "pushData() before start()");

        // If the upload has failed then stop without pushing any more bytes.
        if (mBackupTaskFuture.isDone()) {
            Optional<Exception> exception = getTaskException();
            Slog.e(TAG, "Encrypted upload failed", exception.orElse(null));
            if (exception.isPresent()) {
                reportNetworkFailureIfNecessary(exception.get());

                if (exception.get().getCause() instanceof SizeQuotaExceededException) {
                    return BackupTransport.TRANSPORT_QUOTA_EXCEEDED;
                }
            }

            return BackupTransport.TRANSPORT_ERROR;
        }

        try {
            StreamUtils.copyStream(mInputStream, mOutputStream, numBytes);
        } catch (IOException e) {
            Slog.e(TAG, "IOException when processing backup", e);
            return BackupTransport.TRANSPORT_ERROR;
        }

        return BackupTransport.TRANSPORT_OK;
    }

    @Override
    public void cancel() {
        checkState(mBackupTaskFuture != null && mBackupTask != null, "cancel() before start()");
        mBackupTask.cancel();
        closeStreams();
    }

    @Override
    public int finish() {
        checkState(mBackupTaskFuture != null, "finish() before start()");

        // getTaskException() waits for the task to finish. We must close the streams first, which
        // causes the task to finish, otherwise it will block forever.
        closeStreams();
        Optional<Exception> exception = getTaskException();

        if (exception.isPresent()) {
            Slog.e(TAG, "Exception during encrypted full backup", exception.get());
            reportNetworkFailureIfNecessary(exception.get());

            if (exception.get().getCause() instanceof SizeQuotaExceededException) {
                return BackupTransport.TRANSPORT_QUOTA_EXCEEDED;
            }
            return BackupTransport.TRANSPORT_ERROR;

        } else {
            if (mFullBackupCallbacks != null) {
                mFullBackupCallbacks.onSuccess();
            }

            return BackupTransport.TRANSPORT_OK;
        }
    }

    private void closeStreams() {
        StreamUtils.closeQuietly(mInputStream);
        StreamUtils.closeQuietly(mOutputStream);
    }

    @Override
    public void handleCheckSizeRejectionZeroBytes() {
        cancel();
    }

    @Override
    public void handleCheckSizeRejectionQuotaExceeded() {
        cancel();
    }

    @Override
    public void handleSendBytesQuotaExceeded() {
        cancel();
    }

    @Override
    public void attachCallbacks(FullBackupCallbacks fullBackupCallbacks) {
        this.mFullBackupCallbacks = fullBackupCallbacks;
    }

    private void reportNetworkFailureIfNecessary(Exception exception) {
        if (!(exception.getCause() instanceof SizeQuotaExceededException)
                && mFullBackupCallbacks != null) {
            mFullBackupCallbacks.onTransferFailed();
        }
    }

    private Optional<Exception> getTaskException() {
        if (mBackupTaskFuture != null) {
            try {
                mBackupTaskFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }
}
