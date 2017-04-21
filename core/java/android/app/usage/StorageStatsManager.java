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

import static android.os.storage.StorageManager.convert;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.annotation.WorkerThread;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.StorageManager;

import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

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

    /** {@hide} */
    @TestApi
    public boolean isQuotaSupported(@NonNull UUID storageUuid) {
        try {
            return mService.isQuotaSupported(convert(storageUuid), mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @removed */
    @Deprecated
    public boolean isQuotaSupported(String uuid) {
        return isQuotaSupported(convert(uuid));
    }

    /**
     * Return the total size of the underlying media that is hosting this
     * storage volume.
     * <p>
     * To reduce end user confusion, this value matches the total storage size
     * advertised in a retail environment, which is typically larger than the
     * actual usable partition space.
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @throws IOException when the storage device isn't present.
     */
    @WorkerThread
    public long getTotalBytes(@NonNull UUID storageUuid) throws IOException {
        try {
            return mService.getTotalBytes(convert(storageUuid), mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @removed */
    @Deprecated
    public long getTotalBytes(String uuid) throws IOException {
        return getTotalBytes(convert(uuid));
    }

    /**
     * Return the free space on the requested storage volume.
     * <p>
     * The free space is equivalent to {@link File#getUsableSpace()} plus the
     * size of any cached data that can be automatically deleted by the system
     * as additional space is needed.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @throws IOException when the storage device isn't present.
     */
    @WorkerThread
    public long getFreeBytes(@NonNull UUID storageUuid) throws IOException {
        try {
            return mService.getFreeBytes(convert(storageUuid), mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @removed */
    @Deprecated
    public long getFreeBytes(String uuid) throws IOException {
        return getFreeBytes(convert(uuid));
    }

    /**
     * Return storage statistics for a specific package on the requested storage
     * volume.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     * <p class="note">
     * Note: if the requested package uses the {@code android:sharedUserId}
     * manifest feature, this call will be forced into a slower manual
     * calculation path. If possible, consider always using
     * {@link #queryStatsForUid(UUID, int)}, which is typically faster.
     * </p>
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @param packageName the package name you're interested in.
     * @param user the user you're interested in.
     * @throws PackageManager.NameNotFoundException when the requested package
     *             name isn't installed for the requested user.
     * @throws IOException when the storage device isn't present.
     * @see ApplicationInfo#storageUuid
     * @see PackageInfo#packageName
     */
    @WorkerThread
    public @NonNull StorageStats queryStatsForPackage(@NonNull UUID storageUuid, String packageName,
            UserHandle user) throws PackageManager.NameNotFoundException, IOException {
        try {
            return mService.queryStatsForPackage(convert(storageUuid), packageName,
                    user.getIdentifier(), mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(PackageManager.NameNotFoundException.class);
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @removed */
    @Deprecated
    public StorageStats queryStatsForPackage(String uuid, String packageName,
            UserHandle user) throws PackageManager.NameNotFoundException, IOException {
        return queryStatsForPackage(convert(uuid), packageName, user);
    }

    /**
     * Return storage statistics for a specific UID on the requested storage
     * volume.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @param uid the UID you're interested in.
     * @throws IOException when the storage device isn't present.
     * @see ApplicationInfo#storageUuid
     * @see ApplicationInfo#uid
     */
    @WorkerThread
    public StorageStats queryStatsForUid(@NonNull UUID storageUuid, int uid) throws IOException {
        try {
            return mService.queryStatsForUid(convert(storageUuid), uid,
                    mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @removed */
    @Deprecated
    public StorageStats queryStatsForUid(String uuid, int uid) throws IOException {
        return queryStatsForUid(convert(uuid), uid);
    }

    /**
     * Return storage statistics for a specific {@link UserHandle} on the
     * requested storage volume.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @param user the user you're interested in.
     * @throws IOException when the storage device isn't present.
     * @see android.os.Process#myUserHandle()
     */
    @WorkerThread
    public StorageStats queryStatsForUser(@NonNull UUID storageUuid, UserHandle user)
            throws IOException {
        try {
            return mService.queryStatsForUser(convert(storageUuid), user.getIdentifier(),
                    mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @removed */
    @Deprecated
    public StorageStats queryStatsForUser(String uuid, UserHandle user) throws IOException {
        return queryStatsForUser(convert(uuid), user);
    }

    /**
     * Return shared/external storage statistics for a specific
     * {@link UserHandle} on the requested storage volume.
     * <p>
     * This method may take several seconds to calculate the requested values,
     * so it should only be called from a worker thread.
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @throws IOException when the storage device isn't present.
     * @see android.os.Process#myUserHandle()
     */
    @WorkerThread
    public ExternalStorageStats queryExternalStatsForUser(@NonNull UUID storageUuid,
            UserHandle user) throws IOException {
        try {
            return mService.queryExternalStatsForUser(convert(storageUuid), user.getIdentifier(),
                    mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @removed */
    @Deprecated
    public ExternalStorageStats queryExternalStatsForUser(String uuid, UserHandle user)
            throws IOException {
        return queryExternalStatsForUser(convert(uuid), user);
    }

    /** {@hide} */
    public long getCacheQuotaBytes(String volumeUuid, int uid) {
        try {
            return mService.getCacheQuotaBytes(volumeUuid, uid, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
