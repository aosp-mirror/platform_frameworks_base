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
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IVold;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.provider.MediaStore;
import android.service.storage.ExternalStorageService;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/**
 * Controls storage sessions for users initiated by the {@link StorageManagerService}.
 * Each user on the device will be represented by a {@link StorageUserConnection}.
 */
public final class StorageSessionController {
    private static final String TAG = "StorageSessionController";

    private final Object mLock = new Object();
    private final Context mContext;
    @GuardedBy("mLock")
    private final SparseArray<StorageUserConnection> mConnections = new SparseArray<>();

    private volatile ComponentName mExternalStorageServiceComponent;
    private volatile String mExternalStorageServicePackageName;
    private volatile int mExternalStorageServiceAppId;
    private volatile boolean mIsResetting;

    public StorageSessionController(Context context) {
        mContext = Objects.requireNonNull(context);
    }

    /**
     * Creates and starts a storage session associated with {@code deviceFd} for {@code vol}.
     * Sessions can be started with {@link #onVolumeReady} and removed with {@link #onVolumeUnmount}
     * or {@link #onVolumeRemove}.
     *
     * Throws an {@link IllegalStateException} if a session for {@code vol} has already been created
     *
     * Does nothing if {@link #shouldHandle} is {@code false}
     *
     * Blocks until the session is started or fails
     *
     * @throws ExternalStorageServiceException if the session fails to start
     * @throws IllegalStateException if a session has already been created for {@code vol}
     */
    public void onVolumeMount(ParcelFileDescriptor deviceFd, VolumeInfo vol)
            throws ExternalStorageServiceException {
        if (!shouldHandle(vol)) {
            return;
        }

        Slog.i(TAG, "On volume mount " + vol);

        String sessionId = vol.getId();
        int userId = vol.getMountUserId();

        StorageUserConnection connection = null;
        synchronized (mLock) {
            connection = mConnections.get(userId);
            if (connection == null) {
                Slog.i(TAG, "Creating connection for user: " + userId);
                connection = new StorageUserConnection(mContext, userId, this);
                mConnections.put(userId, connection);
            }
            Slog.i(TAG, "Creating and starting session with id: " + sessionId);
            connection.startSession(sessionId, deviceFd, vol.getPath().getPath(),
                    vol.getInternalPath().getPath());
        }
    }

    /**
     * Notifies the Storage Service that volume state for {@code vol} is changed.
     * A session may already be created for this volume if it is mounted before or the volume state
     * has changed to mounted.
     *
     * Does nothing if {@link #shouldHandle} is {@code false}
     *
     * Blocks until the Storage Service processes/scans the volume or fails in doing so.
     *
     * @throws ExternalStorageServiceException if it fails to connect to ExternalStorageService
     */
    public void notifyVolumeStateChanged(VolumeInfo vol) throws ExternalStorageServiceException {
        if (!shouldHandle(vol)) {
            return;
        }
        String sessionId = vol.getId();
        int userId = vol.getMountUserId();

        StorageUserConnection connection = null;
        synchronized (mLock) {
            connection = mConnections.get(userId);
            if (connection != null) {
                Slog.i(TAG, "Notifying volume state changed for session with id: " + sessionId);
                connection.notifyVolumeStateChanged(sessionId,
                        vol.buildStorageVolume(mContext, userId, false));
            } else {
                Slog.w(TAG, "No available storage user connection for userId : " + userId);
            }
        }
    }

    /**
     * Frees any cache held by ExternalStorageService.
     *
     * <p> Blocks until the service frees the cache or fails in doing so.
     *
     * @param volumeUuid uuid of the {@link StorageVolume} from which cache needs to be freed
     * @param bytes number of bytes which need to be freed
     * @throws ExternalStorageServiceException if it fails to connect to ExternalStorageService
     */
    public void freeCache(String volumeUuid, long bytes)
            throws ExternalStorageServiceException {
        synchronized (mLock) {
            int size = mConnections.size();
            for (int i = 0; i < size; i++) {
                int key = mConnections.keyAt(i);
                StorageUserConnection connection = mConnections.get(key);
                if (connection != null) {
                    connection.freeCache(volumeUuid, bytes);
                }
            }
        }
    }

    /**
     * Called when {@code packageName} is about to ANR
     *
     * @return ANR dialog delay in milliseconds
     */
    public void notifyAnrDelayStarted(String packageName, int uid, int tid, int reason)
            throws ExternalStorageServiceException {
        final int userId = UserHandle.getUserId(uid);
        final StorageUserConnection connection;
        synchronized (mLock) {
            connection = mConnections.get(userId);
        }

        if (connection != null) {
            connection.notifyAnrDelayStarted(packageName, uid, tid, reason);
        }
    }

    /**
     * Removes and returns the {@link StorageUserConnection} for {@code vol}.
     *
     * Does nothing if {@link #shouldHandle} is {@code false}
     *
     * @return the connection that was removed or {@code null} if nothing was removed
     */
    @Nullable
    public StorageUserConnection onVolumeRemove(VolumeInfo vol) {
        if (!shouldHandle(vol)) {
            return null;
        }

        Slog.i(TAG, "On volume remove " + vol);
        String sessionId = vol.getId();
        int userId = vol.getMountUserId();

        synchronized (mLock) {
            StorageUserConnection connection = mConnections.get(userId);
            if (connection != null) {
                Slog.i(TAG, "Removed session for vol with id: " + sessionId);
                connection.removeSession(sessionId);
                return connection;
            } else {
                Slog.w(TAG, "Session already removed for vol with id: " + sessionId);
                return null;
            }
        }
    }


    /**
     * Removes a storage session for {@code vol} and waits for exit.
     *
     * Does nothing if {@link #shouldHandle} is {@code false}
     *
     * Any errors are ignored
     *
     * Call {@link #onVolumeRemove} to remove the connection without waiting for exit
     */
    public void onVolumeUnmount(VolumeInfo vol) {
        StorageUserConnection connection = onVolumeRemove(vol);

        Slog.i(TAG, "On volume unmount " + vol);
        if (connection != null) {
            String sessionId = vol.getId();

            try {
                connection.removeSessionAndWait(sessionId);
            } catch (ExternalStorageServiceException e) {
                Slog.e(TAG, "Failed to end session for vol with id: " + sessionId, e);
            }
        }
    }

    /**
     * Restarts all sessions for {@code userId}.
     *
     * Does nothing if {@link #shouldHandle} is {@code false}
     *
     * This call blocks and waits for all sessions to be started, however any failures when starting
     * a session will be ignored.
     */
    public void onUnlockUser(int userId) throws ExternalStorageServiceException {
        Slog.i(TAG, "On user unlock " + userId);
        if (shouldHandle(null) && userId == 0) {
            initExternalStorageServiceComponent();
        }
    }

    /**
     * Called when a user is in the process is being stopped.
     *
     * Does nothing if {@link #shouldHandle} is {@code false}
     *
     * This call removes all sessions for the user that is being stopped;
     * this will make sure that we don't rebind to the service needlessly.
     */
    public void onUserStopping(int userId) {
        if (!shouldHandle(null)) {
            return;
        }
        StorageUserConnection connection = null;
        synchronized (mLock) {
            connection = mConnections.get(userId);
        }

        if (connection != null) {
            Slog.i(TAG, "Removing all sessions for user: " + userId);
            connection.removeAllSessions();
        } else {
            Slog.w(TAG, "No connection found for user: " + userId);
        }
    }

    /**
     * Resets all sessions for all users and waits for exit. This may kill the
     * {@link ExternalStorageservice} for a user if necessary to ensure all state has been reset.
     *
     * Does nothing if {@link #shouldHandle} is {@code false}
     **/
    public void onReset(IVold vold, Runnable resetHandlerRunnable) {
        if (!shouldHandle(null)) {
            return;
        }

        SparseArray<StorageUserConnection> connections = new SparseArray();
        synchronized (mLock) {
            mIsResetting = true;
            Slog.i(TAG, "Started resetting external storage service...");
            for (int i = 0; i < mConnections.size(); i++) {
                connections.put(mConnections.keyAt(i), mConnections.valueAt(i));
            }
        }

        for (int i = 0; i < connections.size(); i++) {
            StorageUserConnection connection = connections.valueAt(i);
            for (String sessionId : connection.getAllSessionIds()) {
                try {
                    Slog.i(TAG, "Unmounting " + sessionId);
                    vold.unmount(sessionId);
                    Slog.i(TAG, "Unmounted " + sessionId);
                } catch (ServiceSpecificException | RemoteException e) {
                    // TODO(b/140025078): Hard reset vold?
                    Slog.e(TAG, "Failed to unmount volume: " + sessionId, e);
                }

                try {
                    Slog.i(TAG, "Exiting " + sessionId);
                    connection.removeSessionAndWait(sessionId);
                    Slog.i(TAG, "Exited " + sessionId);
                } catch (IllegalStateException | ExternalStorageServiceException e) {
                    Slog.e(TAG, "Failed to exit session: " + sessionId
                            + ". Killing MediaProvider...", e);
                    // If we failed to confirm the session exited, it is risky to proceed
                    // We kill the ExternalStorageService as a last resort
                    killExternalStorageService(connections.keyAt(i));
                    break;
                }
            }
            connection.close();
        }

        resetHandlerRunnable.run();
        synchronized (mLock) {
            mConnections.clear();
            mIsResetting = false;
            Slog.i(TAG, "Finished resetting external storage service");
        }
    }

    private void initExternalStorageServiceComponent() throws ExternalStorageServiceException {
        Slog.i(TAG, "Initialialising...");
        ProviderInfo provider = mContext.getPackageManager().resolveContentProvider(
                MediaStore.AUTHORITY, PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_SYSTEM_ONLY);
        if (provider == null) {
            throw new ExternalStorageServiceException("No valid MediaStore provider found");
        }

        mExternalStorageServicePackageName = provider.applicationInfo.packageName;
        mExternalStorageServiceAppId = UserHandle.getAppId(provider.applicationInfo.uid);

        Intent intent = new Intent(ExternalStorageService.SERVICE_INTERFACE);
        intent.setPackage(mExternalStorageServicePackageName);
        ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            throw new ExternalStorageServiceException(
                    "No valid ExternalStorageService component found");
        }

        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        ComponentName name = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        if (!Manifest.permission.BIND_EXTERNAL_STORAGE_SERVICE
                .equals(serviceInfo.permission)) {
            throw new ExternalStorageServiceException(name.flattenToShortString()
                    + " does not require permission "
                    + Manifest.permission.BIND_EXTERNAL_STORAGE_SERVICE);
        }

        mExternalStorageServiceComponent = name;
    }

    /** Returns the {@link ExternalStorageService} component name. */
    @Nullable
    public ComponentName getExternalStorageServiceComponentName() {
        return mExternalStorageServiceComponent;
    }

    /**
     * Notify the controller that an app with {@code uid} and {@code tid} is blocked on an IO
     * request on {@code volumeUuid} for {@code reason}.
     *
     * This blocked state can be queried with {@link #isAppIoBlocked}
     *
     * @hide
     */
    public void notifyAppIoBlocked(String volumeUuid, int uid, int tid,
            @StorageManager.AppIoBlockedReason int reason) {
        final int userId = UserHandle.getUserId(uid);
        final StorageUserConnection connection;
        synchronized (mLock) {
            connection = mConnections.get(userId);
        }

        if (connection != null) {
            connection.notifyAppIoBlocked(volumeUuid, uid, tid, reason);
        }
    }

    /**
     * Notify the controller that an app with {@code uid} and {@code tid} has resmed a previously
     * blocked IO request on {@code volumeUuid} for {@code reason}.
     *
     * All app IO will be automatically marked as unblocked if {@code volumeUuid} is unmounted.
     */
    public void notifyAppIoResumed(String volumeUuid, int uid, int tid,
            @StorageManager.AppIoBlockedReason int reason) {
        final int userId = UserHandle.getUserId(uid);
        final StorageUserConnection connection;
        synchronized (mLock) {
            connection = mConnections.get(userId);
        }

        if (connection != null) {
            connection.notifyAppIoResumed(volumeUuid, uid, tid, reason);
        }
    }

    /** Returns {@code true} if {@code uid} is blocked on IO, {@code false} otherwise */
    public boolean isAppIoBlocked(int uid) {
        final int userId = UserHandle.getUserId(uid);
        final StorageUserConnection connection;
        synchronized (mLock) {
            connection = mConnections.get(userId);
        }

        if (connection != null) {
            return connection.isAppIoBlocked(uid);
        }
        return false;
    }

    private void killExternalStorageService(int userId) {
        IActivityManager am = ActivityManager.getService();
        try {
            am.killApplication(mExternalStorageServicePackageName, mExternalStorageServiceAppId,
                    userId, "storage_session_controller reset");
        } catch (RemoteException e) {
            Slog.i(TAG, "Failed to kill the ExtenalStorageService for user " + userId);
        }
    }

    /**
     * Returns {@code true} if {@code vol} is an emulated or visible public volume,
     * {@code false} otherwise
     **/
    public static boolean isEmulatedOrPublic(VolumeInfo vol) {
        return vol.type == VolumeInfo.TYPE_EMULATED
                || (vol.type == VolumeInfo.TYPE_PUBLIC && vol.isVisible());
    }

    /** Exception thrown when communication with the {@link ExternalStorageService} fails. */
    public static class ExternalStorageServiceException extends Exception {
        public ExternalStorageServiceException(Throwable cause) {
            super(cause);
        }

        public ExternalStorageServiceException(String message) {
            super(message);
        }

        public ExternalStorageServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static boolean isSupportedVolume(VolumeInfo vol) {
        return isEmulatedOrPublic(vol) || vol.type == VolumeInfo.TYPE_STUB;
    }

    private boolean shouldHandle(@Nullable VolumeInfo vol) {
        return !mIsResetting && (vol == null || isSupportedVolume(vol));
    }
}
