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

import android.annotation.Nullable;
import android.app.backup.BackupTransport;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.infra.AndroidFuture;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Client to {@link com.android.internal.backup.IBackupTransport}. Manages the call to the remote
 * transport service and delivers the results.
 */
public class BackupTransportClient {
    private static final String TAG = "BackupTransportClient";

    private final IBackupTransport mTransportBinder;
    private final TransportStatusCallbackPool mCallbackPool;

    BackupTransportClient(IBackupTransport transportBinder) {
        mTransportBinder = transportBinder;
        mCallbackPool = new TransportStatusCallbackPool();
    }

    /**
     * See {@link IBackupTransport#name()}.
     */
    public String name() throws RemoteException {
        AndroidFuture<String> resultFuture = new AndroidFuture<>();
        mTransportBinder.name(resultFuture);
        return getFutureResult(resultFuture);
    }

    /**
     * See {@link IBackupTransport#configurationIntent()}
     */
    public Intent configurationIntent() throws RemoteException {
        AndroidFuture<Intent> resultFuture = new AndroidFuture<>();
        mTransportBinder.configurationIntent(resultFuture);
        return getFutureResult(resultFuture);
    }

    /**
     * See {@link IBackupTransport#currentDestinationString()}
     */
    public String currentDestinationString() throws RemoteException {
        AndroidFuture<String> resultFuture = new AndroidFuture<>();
        mTransportBinder.currentDestinationString(resultFuture);
        return getFutureResult(resultFuture);
    }

    /**
     * See {@link IBackupTransport#dataManagementIntent()}
     */
    public Intent dataManagementIntent() throws RemoteException {
        AndroidFuture<Intent> resultFuture = new AndroidFuture<>();
        mTransportBinder.dataManagementIntent(resultFuture);
        return getFutureResult(resultFuture);
    }

    /**
     * See {@link IBackupTransport#dataManagementIntentLabel()}
     */
    @Nullable
    public CharSequence dataManagementIntentLabel() throws RemoteException {
        AndroidFuture<CharSequence> resultFuture = new AndroidFuture<>();
        mTransportBinder.dataManagementIntentLabel(resultFuture);
        return getFutureResult(resultFuture);
    }

    /**
     * See {@link IBackupTransport#transportDirName()}
     */
    public String transportDirName() throws RemoteException {
        AndroidFuture<String> resultFuture = new AndroidFuture<>();
        mTransportBinder.transportDirName(resultFuture);
        return getFutureResult(resultFuture);
    }

    /**
     * See {@link IBackupTransport#initializeDevice()}
     */
    public int initializeDevice() throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.initializeDevice(callback);
            return callback.getOperationStatus();
        } finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#clearBackupData(PackageInfo)}
     */
    public int clearBackupData(PackageInfo packageInfo) throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.clearBackupData(packageInfo, callback);
            return callback.getOperationStatus();
        } finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#finishBackup()}
     */
    public int finishBackup() throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.finishBackup(callback);
            return callback.getOperationStatus();
        }  finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#requestBackupTime()}
     */
    public long requestBackupTime() throws RemoteException {
        AndroidFuture<Long> resultFuture = new AndroidFuture<>();
        mTransportBinder.requestBackupTime(resultFuture);
        Long result = getFutureResult(resultFuture);
        return result == null ? BackupTransport.TRANSPORT_ERROR : result;
    }

    /**
     * See {@link IBackupTransport#performBackup(PackageInfo, ParcelFileDescriptor, int)}
     */
    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor inFd, int flags)
            throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.performBackup(packageInfo, inFd, flags, callback);
            return callback.getOperationStatus();
        }  finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#getAvailableRestoreSets()}
     */
    public RestoreSet[] getAvailableRestoreSets() throws RemoteException {
        AndroidFuture<List<RestoreSet>> resultFuture = new AndroidFuture<>();
        mTransportBinder.getAvailableRestoreSets(resultFuture);
        List<RestoreSet> result = getFutureResult(resultFuture);
        return result == null ? null : result.toArray(new RestoreSet[] {});
    }

    /**
     * See {@link IBackupTransport#getCurrentRestoreSet()}
     */
    public long getCurrentRestoreSet() throws RemoteException {
        AndroidFuture<Long> resultFuture = new AndroidFuture<>();
        mTransportBinder.getCurrentRestoreSet(resultFuture);
        Long result = getFutureResult(resultFuture);
        return result == null ? BackupTransport.TRANSPORT_ERROR : result;
    }

    /**
     * See {@link IBackupTransport#startRestore(long, PackageInfo[])}
     */
    public int startRestore(long token, PackageInfo[] packages) throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.startRestore(token, packages, callback);
            return callback.getOperationStatus();
        }  finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#nextRestorePackage()}
     */
    public RestoreDescription nextRestorePackage() throws RemoteException {
        AndroidFuture<RestoreDescription> resultFuture = new AndroidFuture<>();
        mTransportBinder.nextRestorePackage(resultFuture);
        return getFutureResult(resultFuture);
    }

    /**
     * See {@link IBackupTransport#getRestoreData(ParcelFileDescriptor)}
     */
    public int getRestoreData(ParcelFileDescriptor outFd) throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.getRestoreData(outFd, callback);
            return callback.getOperationStatus();
        }  finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#finishRestore()}
     */
    public void finishRestore() throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.finishRestore(callback);
            callback.getOperationStatus();
        }  finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#requestFullBackupTime()}
     */
    public long requestFullBackupTime() throws RemoteException {
        AndroidFuture<Long> resultFuture = new AndroidFuture<>();
        mTransportBinder.requestFullBackupTime(resultFuture);
        Long result = getFutureResult(resultFuture);
        return result == null ? BackupTransport.TRANSPORT_ERROR : result;
    }

    /**
     * See {@link IBackupTransport#performFullBackup(PackageInfo, ParcelFileDescriptor, int)}
     */
    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor socket,
            int flags) throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.performFullBackup(targetPackage, socket, flags, callback);
            return callback.getOperationStatus();
        }  finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#checkFullBackupSize(long)}
     */
    public int checkFullBackupSize(long size) throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.checkFullBackupSize(size, callback);
            return callback.getOperationStatus();
        }  finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#sendBackupData(int)}
     */
    public int sendBackupData(int numBytes) throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        mTransportBinder.sendBackupData(numBytes, callback);
        try {
            return callback.getOperationStatus();
        } finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#cancelFullBackup()}
     */
    public void cancelFullBackup() throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.cancelFullBackup(callback);
            callback.getOperationStatus();
        } finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#isAppEligibleForBackup(PackageInfo, boolean)}
     */
    public boolean isAppEligibleForBackup(PackageInfo targetPackage, boolean isFullBackup)
            throws RemoteException {
        AndroidFuture<Boolean> resultFuture = new AndroidFuture<>();
        mTransportBinder.isAppEligibleForBackup(targetPackage, isFullBackup, resultFuture);
        Boolean result = getFutureResult(resultFuture);
        return result != null && result;
    }

    /**
     * See {@link IBackupTransport#getBackupQuota(String, boolean)}
     */
    public long getBackupQuota(String packageName, boolean isFullBackup) throws RemoteException {
        AndroidFuture<Long> resultFuture = new AndroidFuture<>();
        mTransportBinder.getBackupQuota(packageName, isFullBackup, resultFuture);
        Long result = getFutureResult(resultFuture);
        return result == null ? BackupTransport.TRANSPORT_ERROR : result;
    }

    /**
     * See {@link IBackupTransport#getNextFullRestoreDataChunk(ParcelFileDescriptor)}
     */
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.getNextFullRestoreDataChunk(socket, callback);
            return callback.getOperationStatus();
        } finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#abortFullRestore()}
     */
    public int abortFullRestore() throws RemoteException {
        TransportStatusCallback callback = mCallbackPool.acquire();
        try {
            mTransportBinder.abortFullRestore(callback);
            return callback.getOperationStatus();
        } finally {
            mCallbackPool.recycle(callback);
        }
    }

    /**
     * See {@link IBackupTransport#getTransportFlags()}
     */
    public int getTransportFlags() throws RemoteException {
        AndroidFuture<Integer> resultFuture = new AndroidFuture<>();
        mTransportBinder.getTransportFlags(resultFuture);
        Integer result = getFutureResult(resultFuture);
        return result == null ? BackupTransport.TRANSPORT_ERROR : result;
    }

    private <T> T getFutureResult(AndroidFuture<T> future) {
        try {
            return future.get(600, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Slog.w(TAG, "Failed to get result from transport:", e);
            return null;
        }
    }

    private static class TransportStatusCallbackPool {
        private static final int MAX_POOL_SIZE = 100;

        private final Object mPoolLock = new Object();
        private final Queue<TransportStatusCallback> mCallbackPool = new ArrayDeque<>();

        TransportStatusCallback acquire() {
            synchronized (mPoolLock) {
                if (mCallbackPool.isEmpty()) {
                    return new TransportStatusCallback();
                } else {
                    return mCallbackPool.poll();
                }
            }
        }

        void recycle(TransportStatusCallback callback) {
            synchronized (mPoolLock) {
                if (mCallbackPool.size() > MAX_POOL_SIZE) {
                    Slog.d(TAG, "TransportStatusCallback pool size exceeded");
                    return;
                }

                callback.reset();
                mCallbackPool.add(callback);
            }
        }
    }
}
