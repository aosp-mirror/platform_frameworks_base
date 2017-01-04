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
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

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
     * Return detailed statistics for the a specific UID on the requested
     * storage volume.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param volumeUuid the UUID of the storage volume you're interested in, or
     *            {@code null} to specify the default internal storage.
     * @param uid the UID you're interested in.
     */
    @WorkerThread
    public StorageStats queryStatsForUid(String volumeUuid, int uid) {
        try {
            return mService.queryStats(volumeUuid, uid, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return summary statistics for the requested storage volume.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param volumeUuid the UUID of the storage volume you're interested in, or
     *            {@code null} to specify the default internal storage.
     */
    @WorkerThread
    public StorageSummary querySummary(String volumeUuid) {
        try {
            return mService.querySummary(volumeUuid, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
