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

import android.app.backup.BackupTransport;

import java.io.IOException;
import java.io.InputStream;

/** Accepts the full backup data stream and sends it to the server. */
public interface FullBackupDataProcessor {
    /**
     * Prepares the upload.
     *
     * <p>After this, call {@link #start()} to establish the connection.
     *
     * @param inputStream to read the backup data from, calling {@link #finish} or {@link #cancel}
     *     will close the stream
     * @return {@code true} if the connection was set up successfully, otherwise {@code false}
     */
    boolean initiate(InputStream inputStream) throws IOException;

    /**
     * Starts the upload, establishing the connection to the server.
     *
     * <p>After this, call {@link #pushData(int)} to request that the processor reads data from the
     * socket, and uploads it to the server.
     *
     * <p>After this you must call one of {@link #cancel()}, {@link #finish()}, {@link
     * #handleCheckSizeRejectionZeroBytes()}, {@link #handleCheckSizeRejectionQuotaExceeded()} or
     * {@link #handleSendBytesQuotaExceeded()} to close the upload.
     */
    void start();

    /**
     * Requests that the processor read {@code numBytes} from the input stream passed in {@link
     * #initiate(InputStream)} and upload them to the server.
     *
     * @return {@link BackupTransport#TRANSPORT_OK} if the upload succeeds, or {@link
     *     BackupTransport#TRANSPORT_QUOTA_EXCEEDED} if the upload exceeded the server-side app size
     *     quota, or {@link BackupTransport#TRANSPORT_PACKAGE_REJECTED} for other errors.
     */
    int pushData(int numBytes);

    /** Cancels the upload and tears down the connection. */
    void cancel();

    /**
     * Finish the upload and tear down the connection.
     *
     * <p>Call this after there is no more data to push with {@link #pushData(int)}.
     *
     * @return One of {@link BackupTransport#TRANSPORT_OK} if the app upload succeeds, {@link
     *     BackupTransport#TRANSPORT_QUOTA_EXCEEDED} if the upload exceeded the server-side app size
     *     quota, {@link BackupTransport#TRANSPORT_ERROR} for server 500s, or {@link
     *     BackupTransport#TRANSPORT_PACKAGE_REJECTED} for other errors.
     */
    int finish();

    /**
     * Notifies the processor that the current upload should be terminated because the estimated
     * size is zero.
     */
    void handleCheckSizeRejectionZeroBytes();

    /**
     * Notifies the processor that the current upload should be terminated because the estimated
     * size exceeds the quota.
     */
    void handleCheckSizeRejectionQuotaExceeded();

    /**
     * Notifies this class that the current upload should be terminated because the quota was
     * exceeded during upload.
     */
    void handleSendBytesQuotaExceeded();

    /**
     * Attaches {@link FullBackupCallbacks} which the processor will notify when the backup
     * succeeds.
     */
    void attachCallbacks(FullBackupCallbacks fullBackupCallbacks);

    /**
     * Implemented by the caller of the processor to receive notification of when the backup
     * succeeds.
     */
    interface FullBackupCallbacks {
        /** The processor calls this to indicate that the current backup has succeeded. */
        void onSuccess();

        /** The processor calls this if the upload failed for a non-transient reason. */
        void onTransferFailed();
    }
}
