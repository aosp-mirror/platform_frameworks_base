/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app.usage;

import android.annotation.WorkerThread;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.internal.util.Preconditions;

import java.io.File;

/**
 * Provides access to detailed storage statistics.
 * <p class="note">
 * Note: this API requires the permission
 * {@code android.permission.PACKAGE_USAGE_STATS}, which is a system-level
 * permission that will not be granted to normal apps. However, declaring the
 * permission expresses your intention to use this API and an end user can then
 * choose to grant this permission through the Settings application.
 * </p>
 */
public class StorageStatsManager {
    private final Context mContext;
    private final IStorageStatsManager mService;

    /** {@hide} */
    public StorageStatsManager(Context context, IStorageStatsManager service) {
        mContext = Preconditions.checkNotNull(context);
        mService = Preconditions.checkNotNull(service);
    }

    /**
     * Return the total space on the requested storage volume.
     * <p>
     * To reduce end user confusion, this value is the total storage size
     * advertised in a retail environment, which is typically larger than the
     * actual writable partition total size.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param volumeUuid the UUID of the storage volume you're interested in, or
     *            {@code null} to specify the default internal storage.
     */
    @WorkerThread
    public long getTotalBytes(String volumeUuid) {
        try {
            return mService.getTotalBytes(volumeUuid, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the free space on the requested storage volume.
     * <p>
     * The free space is equivalent to {@link File#getFreeSpace()} plus the size
     * of any cached data that can be automatically deleted by the system as
     * additional space is needed.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param volumeUuid the UUID of the storage volume you're interested in, or
     *            {@code null} to specify the default internal storage.
     */
    @WorkerThread
    public long getFreeBytes(String volumeUuid) {
        try {
            return mService.getFreeBytes(volumeUuid, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return storage statistics for a specific UID on the requested storage
     * volume.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param volumeUuid the UUID of the storage volume you're interested in, or
     *            {@code null} to specify the default internal storage.
     * @param uid the UID you're interested in.
     * @see ApplicationInfo#volumeUuid
     * @see ApplicationInfo#uid
     */
    @WorkerThread
    public StorageStats queryStatsForUid(String volumeUuid, int uid) {
        try {
            return mService.queryStatsForUid(volumeUuid, uid, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return storage statistics for a specific {@link UserHandle} on the
     * requested storage volume.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param volumeUuid the UUID of the storage volume you're interested in, or
     *            {@code null} to specify the default internal storage.
     * @param user the user you're interested in.
     * @see android.os.Process#myUserHandle()
     */
    @WorkerThread
    public StorageStats queryStatsForUser(String volumeUuid, UserHandle user) {
        try {
            return mService.queryStatsForUser(volumeUuid, user.getIdentifier(),
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return shared/external storage statistics for a specific
     * {@link UserHandle} on the requested storage volume.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param volumeUuid the UUID of the storage volume you're interested in, or
     *            {@code null} to specify the default internal storage.
     * @see android.os.Process#myUserHandle()
     */
    @WorkerThread
    public ExternalStorageStats queryExternalStatsForUser(String volumeUuid, UserHandle user) {
        try {
            return mService.queryExternalStatsForUser(volumeUuid, user.getIdentifier(),
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
