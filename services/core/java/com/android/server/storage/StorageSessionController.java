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

package com.android.server.storage;

import android.Manifest;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.ParcelFileDescriptor;
import android.os.storage.VolumeInfo;
import android.provider.MediaStore;
import android.service.storage.ExternalStorageService;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * Controls storage sessions for users initiated by the {@link StorageManagerService}.
 * Each user on the device will be represented by a {@link StorageUserConnection}.
 */
public final class StorageSessionController {
    private static final String TAG = "StorageSessionController";

    private final Object mLock = new Object();
    private final Context mContext;
    private final Callback mCallback;
    @GuardedBy("mLock")
    private ComponentName mExternalStorageServiceComponent;
    @GuardedBy("mLock")
    private final SparseArray<StorageUserConnection> mConnections = new SparseArray<>();

    public StorageSessionController(Context context, Callback callback) {
        mContext = Preconditions.checkNotNull(context);
        mCallback = Preconditions.checkNotNull(callback);
    }

    /**
     * Starts a storage session associated with {@code deviceFd} for {@code vol}.
     * Does nothing if a session is already started or starting. If the user associated with
     * {@code vol} is not yet ready, the session will be retried {@link #onUserStarted}.
     *
     * A session must be ended with {@link #endSession} when no longer required.
     */
    public void onVolumeMounted(int userId, FileDescriptor deviceFd, VolumeInfo vol) {
        if (deviceFd == null) {
            Slog.w(TAG, "Null device fd. Session not started for " + vol);
            return;
        }

        // Get realpath for the fd, paths that are not /dev/null need additional
        // setup by the ExternalStorageService before they can be ready
        String realPath;
        try {
            realPath = ParcelFileDescriptor.getFile(deviceFd).getPath();
        } catch (IOException e) {
            Slog.wtf(TAG, "Could not get real path from fd: " + deviceFd, e);
            return;
        }

        if ("/dev/null".equals(realPath)) {
            Slog.i(TAG, "Volume ready for use: " + vol);
            return;
        }

        synchronized (mLock) {
            StorageUserConnection connection = mConnections.get(userId);
            if (connection == null) {
                Slog.i(TAG, "Creating new session for vol: " + vol);
                connection = new StorageUserConnection(mContext, userId, this);
                mConnections.put(userId, connection);
            }
            try {
                Slog.i(TAG, "Starting session for vol: " + vol);
                connection.startSession(deviceFd, vol);
            } catch (ExternalStorageServiceException e) {
                Slog.e(TAG, "Failed to start session for vol: " + vol, e);
            }
        }
    }

    /**
     * Ends a storage session for {@code vol}. Does nothing if the session is already
     * ended or ending. Ending a session discards all resources associated with that session.
     */
    public void onVolumeUnmounted(int userId, VolumeInfo vol) {
        synchronized (mLock) {
            StorageUserConnection connection = mConnections.get(userId);
            if (connection != null) {
                Slog.i(TAG, "Ending session for vol: " + vol);
                try {
                    if (connection.endSession(vol)) {
                        mConnections.remove(userId);
                    }
                } catch (ExternalStorageServiceException e) {
                    Slog.e(TAG, "Failed to end session for vol: " + vol, e);
                }
            } else {
                Slog.w(TAG, "Session already ended for vol: " + vol);
            }
        }
    }

    /** Restarts all sessions for {@code userId}. */
    public void onUserStarted(int userId) {
        synchronized (mLock) {
            StorageUserConnection connection = mConnections.get(userId);
            if (connection != null) {
                try {
                    Slog.i(TAG, "Restarting all sessions for user: " + userId);
                    connection.startAllSessions();
                } catch (ExternalStorageServiceException e) {
                    Slog.e(TAG, "Failed to start all sessions", e);
                }
            } else {
                // TODO(b/135341433): What does this mean in multi-user
            }
        }
    }

    /** Ends all sessions for {@code userId}. */
    public void onUserRemoved(int userId) {
        synchronized (mLock) {
            StorageUserConnection connection = mConnections.get(userId);
            if (connection != null) {
                try {
                    Slog.i(TAG, "Ending all sessions for user: " + userId);
                    connection.endAllSessions();
                    mConnections.remove(userId);
                } catch (ExternalStorageServiceException e) {
                    Slog.e(TAG, "Failed to end all sessions", e);
                }
            } else {
                // TODO(b/135341433): What does this mean in multi-user
            }
        }
    }

    /** Returns the {@link ExternalStorageService} component name. */
    @Nullable
    public ComponentName getExternalStorageServiceComponentName() {
        synchronized (mLock) {
            if (mExternalStorageServiceComponent == null) {
                ProviderInfo provider = mContext.getPackageManager().resolveContentProvider(
                        MediaStore.AUTHORITY, PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                        | PackageManager.MATCH_SYSTEM_ONLY);

                if (provider == null) {
                    Slog.e(TAG, "No valid MediaStore provider found.");
                }
                String packageName = provider.applicationInfo.packageName;

                Intent intent = new Intent(ExternalStorageService.SERVICE_INTERFACE);
                intent.setPackage(packageName);
                ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                        PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
                if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                    Slog.e(TAG, "No valid ExternalStorageService component found.");
                    return null;
                }

                ServiceInfo serviceInfo = resolveInfo.serviceInfo;
                ComponentName name = new ComponentName(serviceInfo.packageName, serviceInfo.name);
                if (!Manifest.permission.BIND_EXTERNAL_STORAGE_SERVICE
                        .equals(serviceInfo.permission)) {
                    Slog.e(TAG, name.flattenToShortString() + " does not require permission "
                            + Manifest.permission.BIND_EXTERNAL_STORAGE_SERVICE);
                    return null;
                }
                mExternalStorageServiceComponent = name;
            }
            return mExternalStorageServiceComponent;
        }
    }

    /** Returns the {@link StorageManagerService} callback. */
    public Callback getCallback() {
        return mCallback;
    }

    /** Callback to listen to session events from the {@link StorageSessionController}. */
    public interface Callback {
        /** Called when a {@link StorageUserConnection} is disconnected. */
        void onUserDisconnected(int userId);
    }

    /** Exception thrown when communication with the {@link ExternalStorageService}. */
    public static class ExternalStorageServiceException extends Exception {
        public ExternalStorageServiceException(Throwable cause) {
            super(cause);
        }
    }
}
