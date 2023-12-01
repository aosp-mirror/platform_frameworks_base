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

import android.annotation.BytesLong;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.WorkerThread;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.CrateInfo;
import android.os.storage.StorageManager;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Access to detailed storage statistics. This provides a summary of how apps,
 * users, and external/shared storage is utilizing disk space.
 * <p class="note">
 * Note: no permissions are required when calling these APIs for your own
 * package or UID. However, requesting details for any other package requires
 * the {@code android.Manifest.permission#PACKAGE_USAGE_STATS} permission, which
 * is a system-level permission that will not be granted to normal apps.
 * Declaring that permission expresses your intention to use this API and an end
 * user can then choose to grant this permission through the Settings
 * application.
 * </p>
 */
@SystemService(Context.STORAGE_STATS_SERVICE)
public class StorageStatsManager {
    private final Context mContext;
    private final IStorageStatsManager mService;

    /** {@hide} */
    public StorageStatsManager(Context context, IStorageStatsManager service) {
        mContext = Objects.requireNonNull(context);
        mService = Objects.requireNonNull(service);
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

    /** {@hide} */
    @Deprecated
    public boolean isQuotaSupported(String uuid) {
        return isQuotaSupported(convert(uuid));
    }

    /** {@hide} */
    @TestApi
    public boolean isReservedSupported(@NonNull UUID storageUuid) {
        try {
            return mService.isReservedSupported(convert(storageUuid), mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the total size of the underlying physical media that is hosting
     * this storage volume.
     * <p>
     * This value is best suited for visual display to end users, since it's
     * designed to reflect the total storage size advertised in a retail
     * environment.
     * <p>
     * Apps making logical decisions about disk space should always use
     * {@link File#getTotalSpace()} instead of this value.
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @throws IOException when the storage device isn't present.
     */
    @WorkerThread
    public @BytesLong long getTotalBytes(@NonNull UUID storageUuid) throws IOException {
        try {
            return mService.getTotalBytes(convert(storageUuid), mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @Deprecated
    public long getTotalBytes(String uuid) throws IOException {
        return getTotalBytes(convert(uuid));
    }

    /**
     * Return the free space on the requested storage volume.
     * <p>
     * This value is best suited for visual display to end users, since it's
     * designed to reflect both unused space <em>and</em> and cached space that
     * could be reclaimed by the system.
     * <p>
     * Apps making logical decisions about disk space should always use
     * {@link StorageManager#getAllocatableBytes(UUID)} instead of this value.
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @throws IOException when the storage device isn't present.
     */
    @WorkerThread
    public @BytesLong long getFreeBytes(@NonNull UUID storageUuid) throws IOException {
        try {
            return mService.getFreeBytes(convert(storageUuid), mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @Deprecated
    public long getFreeBytes(String uuid) throws IOException {
        return getFreeBytes(convert(uuid));
    }

    /** {@hide} */
    public @BytesLong long getCacheBytes(@NonNull UUID storageUuid) throws IOException {
        try {
            return mService.getCacheBytes(convert(storageUuid), mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @Deprecated
    public long getCacheBytes(String uuid) throws IOException {
        return getCacheBytes(convert(uuid));
    }

    /**
     * Return storage statistics for a specific package on the requested storage
     * volume.
     * <p class="note">
     * Note: no permissions are required when calling this API for your own
     * package. However, requesting details for any other package requires the
     * {@code android.Manifest.permission#PACKAGE_USAGE_STATS} permission, which
     * is a system-level permission that will not be granted to normal apps.
     * Declaring that permission expresses your intention to use this API and an
     * end user can then choose to grant this permission through the Settings
     * application.
     * </p>
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
    public @NonNull StorageStats queryStatsForPackage(@NonNull UUID storageUuid,
            @NonNull String packageName, @NonNull UserHandle user)
            throws PackageManager.NameNotFoundException, IOException {
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

    /** {@hide} */
    @Deprecated
    public StorageStats queryStatsForPackage(String uuid, String packageName,
            UserHandle user) throws PackageManager.NameNotFoundException, IOException {
        return queryStatsForPackage(convert(uuid), packageName, user);
    }

    /**
     * Return storage statistics for a specific UID on the requested storage
     * volume.
     * <p class="note">
     * Note: no permissions are required when calling this API for your own UID.
     * However, requesting details for any other UID requires the
     * {@code android.Manifest.permission#PACKAGE_USAGE_STATS} permission, which
     * is a system-level permission that will not be granted to normal apps.
     * Declaring that permission expresses your intention to use this API and an
     * end user can then choose to grant this permission through the Settings
     * application.
     * </p>
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @param uid the UID you're interested in.
     * @throws IOException when the storage device isn't present.
     * @see ApplicationInfo#storageUuid
     * @see ApplicationInfo#uid
     */
    @WorkerThread
    public @NonNull StorageStats queryStatsForUid(@NonNull UUID storageUuid, int uid)
            throws IOException {
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

    /** {@hide} */
    @Deprecated
    public StorageStats queryStatsForUid(String uuid, int uid) throws IOException {
        return queryStatsForUid(convert(uuid), uid);
    }

    /**
     * Return storage statistics for a specific {@link UserHandle} on the
     * requested storage volume.
     * <p class="note">
     * Note: this API requires the
     * {@code android.Manifest.permission#PACKAGE_USAGE_STATS} permission, which
     * is a system-level permission that will not be granted to normal apps.
     * Declaring that permission expresses your intention to use this API and an
     * end user can then choose to grant this permission through the Settings
     * application.
     * </p>
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @param user the user you're interested in.
     * @throws IOException when the storage device isn't present.
     * @see android.os.Process#myUserHandle()
     */
    @WorkerThread
    public @NonNull StorageStats queryStatsForUser(@NonNull UUID storageUuid,
            @NonNull UserHandle user) throws IOException {
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

    /** {@hide} */
    @Deprecated
    public StorageStats queryStatsForUser(String uuid, UserHandle user) throws IOException {
        return queryStatsForUser(convert(uuid), user);
    }

    /**
     * Return shared/external storage statistics for a specific
     * {@link UserHandle} on the requested storage volume.
     * <p class="note">
     * Note: this API requires the
     * {@code android.Manifest.permission#PACKAGE_USAGE_STATS} permission, which
     * is a system-level permission that will not be granted to normal apps.
     * Declaring that permission expresses your intention to use this API and an
     * end user can then choose to grant this permission through the Settings
     * application.
     * </p>
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @throws IOException when the storage device isn't present.
     * @see android.os.Process#myUserHandle()
     */
    @WorkerThread
    public @NonNull ExternalStorageStats queryExternalStatsForUser(@NonNull UUID storageUuid,
            @NonNull UserHandle user) throws IOException {
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

    /** {@hide} */
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

    /**
     * Return all of crate information for the specified storageUuid, packageName, and
     * userHandle.
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @param uid the uid you're interested in.
     * @return the collection of crate information.
     * @throws PackageManager.NameNotFoundException when the package name is not found.
     * @throws IOException cause by IO, not support, or the other reasons.
     * @hide
     */
    @TestApi
    @WorkerThread
    @NonNull
    public Collection<CrateInfo> queryCratesForUid(@NonNull UUID storageUuid,
            int uid) throws IOException, PackageManager.NameNotFoundException {
        try {
            ParceledListSlice<CrateInfo> crateInfoList =
                    mService.queryCratesForUid(convert(storageUuid), uid,
                            mContext.getOpPackageName());
            return Objects.requireNonNull(crateInfoList).getList();
        } catch (ParcelableException e) {
            e.maybeRethrow(PackageManager.NameNotFoundException.class);
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all of crates information for the specified storageUuid, packageName, and
     * userHandle.
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @param packageName the package name you're interested in.
     * @param user the user you're interested in.
     * @return the collection of crate information.
     * @throws PackageManager.NameNotFoundException when the package name is not found.
     * @throws IOException cause by IO, not support, or the other reasons.
     * @hide
     */
    @WorkerThread
    @TestApi
    @NonNull
    public Collection<CrateInfo> queryCratesForPackage(@NonNull UUID storageUuid,
            @NonNull String packageName, @NonNull UserHandle user)
            throws PackageManager.NameNotFoundException, IOException {
        try {
            ParceledListSlice<CrateInfo> crateInfoList =
                    mService.queryCratesForPackage(convert(storageUuid), packageName,
                            user.getIdentifier(), mContext.getOpPackageName());
            return Objects.requireNonNull(crateInfoList).getList();
        } catch (ParcelableException e) {
            e.maybeRethrow(PackageManager.NameNotFoundException.class);
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all of crate information for the specified storageUuid, packageName, and
     * userHandle.
     *
     * @param storageUuid the UUID of the storage volume you're interested in,
     *            such as {@link StorageManager#UUID_DEFAULT}.
     * @param user the user you're interested in.
     * @return the collection of crate information.
     * @throws PackageManager.NameNotFoundException when the package name is not found.
     * @throws IOException cause by IO, not support, or the other reasons.
     * @hide
     */
    @WorkerThread
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_CRATES)
    @NonNull
    public Collection<CrateInfo> queryCratesForUser(@NonNull UUID storageUuid,
            @NonNull UserHandle user) throws PackageManager.NameNotFoundException, IOException {
        try {
            ParceledListSlice<CrateInfo> crateInfoList =
                    mService.queryCratesForUser(convert(storageUuid), user.getIdentifier(),
                            mContext.getOpPackageName());
            return Objects.requireNonNull(crateInfoList).getList();
        } catch (ParcelableException e) {
            e.maybeRethrow(PackageManager.NameNotFoundException.class);
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
