/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.os.storage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.IVold;

import java.util.List;
import java.util.Set;

/**
 * Mount service local interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class StorageManagerInternal {
    /**
     * Gets the mount mode to use for a given UID
     *
     * @param uid The UID for which to get mount mode.
     * @param packageName The package in the UID for making the call.
     * @return The mount mode.
     */
    public abstract int getExternalStorageMountMode(int uid, String packageName);

    /**
     * Checks whether the {@code packageName} with {@code uid} has full external storage access via
     * the {@link MANAGE_EXTERNAL_STORAGE} permission.
     *
     * @param uid the UID for which to check access.
     * @param packageName the package in the UID for making the call.
     * @return whether the {@code packageName} has full external storage access.
     * Returns {@code true} if it has access, {@code false} otherwise.
     */
    public abstract boolean hasExternalStorageAccess(int uid, String packageName);

    /**
     * A listener for reset events in the StorageManagerService.
     */
    public interface ResetListener {
        /**
         * A method that should be triggered internally by StorageManagerInternal
         * when StorageManagerService reset happens.
         *
         * @param vold The binder object to vold.
         */
        void onReset(IVold vold);
    }

    /**
     * Return true if fuse is mounted.
     */
    public abstract boolean isFuseMounted(int userId);

    /**
     * Create storage directories if it does not exist.
     * Return true if the directories were setup correctly, otherwise false.
     */
    public abstract boolean prepareStorageDirs(int userId, Set<String> packageList,
            String processName);

    /**
     * Add a listener to listen to reset event in StorageManagerService.
     *
     * @param listener The listener that will be notified on reset events.
     */
    public abstract void addResetListener(ResetListener listener);

    /**
     * Notified when any app op changes so that storage mount points can be updated if the app op
     * affects them.
     */
    public abstract void onAppOpsChanged(int code, int uid,
            @Nullable String packageName, int mode, int previousMode);

    /**
     * Asks the StorageManager to reset all state for the provided user; this will result
     * in the unmounting for all volumes of the user, and, if the user is still running, the
     * volumes will be re-mounted as well.
     *
     * @param userId the userId for which to reset storage
     */
    public abstract void resetUser(int userId);

    /**
     * Returns {@code true} if the immediate last installed version of an app with {@code uid} had
     * legacy storage, {@code false} otherwise.
     */
    public abstract boolean hasLegacyExternalStorage(int uid);

    /**
     * Makes sure app-private data directories on external storage are setup correctly
     * after an application is installed or upgraded. The main use for this is OBB dirs,
     * which can be created/modified by the installer.
     *
     * @param packageName the package name of the package
     * @param uid the uid of the package
     */
    public abstract void prepareAppDataAfterInstall(@NonNull String packageName, int uid);

    /**
     * Return true if uid is external storage service.
     */
    public abstract boolean isExternalStorageService(int uid);

    /**
     * Frees cache held by ExternalStorageService.
     *
     * <p> Blocks until the service frees the cache or fails in doing so.
     *
     * @param volumeUuid uuid of the {@link StorageVolume} from which cache needs to be freed,
     *                   null value indicates private internal volume.
     * @param bytes number of bytes which need to be freed
     */
    public abstract void freeCache(@Nullable String volumeUuid, long bytes);

    /**
     * Returns the {@link VolumeInfo#getId()} values for the volumes matching
     * {@link VolumeInfo#isPrimary()}
     */
    public abstract List<String> getPrimaryVolumeIds();

    /**
     * Tells StorageManager that CE storage for this user has been prepared.
     *
     * @param userId userId for which CE storage has been prepared
     */
    public abstract void markCeStoragePrepared(@UserIdInt int userId);

    /**
     * Returns true when CE storage for this user has been prepared.
     *
     * When the user key is unlocked and CE storage has been prepared,
     * it's ok to access and modify CE directories on volumes for this user.
     */
    public abstract boolean isCeStoragePrepared(@UserIdInt int userId);

    /**
     * A listener for changes to the cloud provider.
     */
    public interface CloudProviderChangeListener {
        /**
         * Triggered when the cloud provider changes. A {@code null} value means there's currently
         * no cloud provider.
         */
        void onCloudProviderChanged(int userId, @Nullable String authority);
    }

    /**
     * Register a {@link CloudProviderChangeListener} to be notified when a cloud media provider
     * changes. The listener will be called after registration with any currently set cloud media
     * providers.
     */
    public abstract void registerCloudProviderChangeListener(
            @NonNull CloudProviderChangeListener listener);
}
