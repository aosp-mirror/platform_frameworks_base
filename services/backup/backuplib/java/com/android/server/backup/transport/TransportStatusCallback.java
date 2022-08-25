/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.backup.transport;

import android.app.backup.BackupTransport;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.ITransportStatusCallback;

public class TransportStatusCallback extends ITransportStatusCallback.Stub {
    private static final String TAG = "TransportStatusCallback";
    private static final int TIMEOUT_MILLIS = 300 * 1000; // 5 minutes.
    private static final int OPERATION_STATUS_DEFAULT = 0;

    private final int mOperationTimeout;

    @GuardedBy("this")
    private int mOperationStatus = OPERATION_STATUS_DEFAULT;
    @GuardedBy("this")
    private boolean mHasCompletedOperation = false;

    public TransportStatusCallback() {
        mOperationTimeout = TIMEOUT_MILLIS;
    }

    @VisibleForTesting
    TransportStatusCallback(int operationTimeout) {
        mOperationTimeout = operationTimeout;
    }

    @Override
    public synchronized void onOperationCompleteWithStatus(int status) throws RemoteException {
        mHasCompletedOperation = true;
        mOperationStatus = status;

        notifyAll();
    }

    @Override
    public synchronized void onOperationComplete() throws RemoteException {
        onOperationCompleteWithStatus(OPERATION_STATUS_DEFAULT);
    }

    synchronized int getOperationStatus() {
        if (mHasCompletedOperation) {
            return mOperationStatus;
        }

        long timeoutLeft = mOperationTimeout;
        try {
            while (!mHasCompletedOperation && timeoutLeft > 0) {
                long waitStartTime = System.currentTimeMillis();
                wait(timeoutLeft);
                if (mHasCompletedOperation) {
                    return mOperationStatus;
                }
                timeoutLeft -= System.currentTimeMillis() - waitStartTime;
            }

            Slog.w(TAG, "Couldn't get operation status from transport");
        } catch (InterruptedException e) {
            Slog.w(TAG, "Couldn't get operation status from transport: ", e);
        }

        return BackupTransport.TRANSPORT_ERROR;
    }

    synchronized void reset() {
        mHasCompletedOperation = false;
        mOperationStatus = OPERATION_STATUS_DEFAULT;
    }
}
