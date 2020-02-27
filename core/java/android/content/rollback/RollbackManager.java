/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.content.rollback;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.ParceledListSlice;
import android.content.pm.VersionedPackage;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Offers the ability to rollback packages after upgrade.
 * <p>
 * For packages installed with rollbacks enabled, the RollbackManager can be
 * used to initiate rollback of those packages for a limited time period after
 * upgrade.
 *
 * @see PackageInstaller.SessionParams#setEnableRollback(boolean)
 * @hide
 */
@SystemApi @TestApi
@SystemService(Context.ROLLBACK_SERVICE)
public final class RollbackManager {
    private final String mCallerPackageName;
    private final IRollbackManager mBinder;

    /**
     * Lifetime duration of rollback packages in millis. A rollback will be available for
     * at most that duration of time after a package is installed with
     * {@link PackageInstaller.SessionParams#setEnableRollback(boolean)}.
     *
     * <p>If flag value is negative, the default value will be assigned.
     *
     * @see RollbackManager
     *
     * Flag type: {@code long}
     * Namespace: NAMESPACE_ROLLBACK_BOOT
     *
     * @hide
     */
    @TestApi
    public static final String PROPERTY_ROLLBACK_LIFETIME_MILLIS =
            "rollback_lifetime_in_millis";

    /** {@hide} */
    public RollbackManager(Context context, IRollbackManager binder) {
        mCallerPackageName = context.getPackageName();
        mBinder = binder;
    }

    /**
     * Returns a list of all currently available rollbacks.
     *
     * @throws SecurityException if the caller does not have appropriate permissions.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_ROLLBACKS,
            android.Manifest.permission.TEST_MANAGE_ROLLBACKS
    })
    @NonNull
    public List<RollbackInfo> getAvailableRollbacks() {
        try {
            return mBinder.getAvailableRollbacks().getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the list of all recently committed rollbacks.
     * This is for the purposes of preventing re-install of a bad version of a
     * package and monitoring the status of a staged rollback.
     * <p>
     * Returns an empty list if there are no recently committed rollbacks.
     * <p>
     * To avoid having to keep around complete rollback history forever on a
     * device, the returned list of rollbacks is only guaranteed to include
     * rollbacks that are still relevant. A rollback is no longer considered
     * relevant if the package is subsequently uninstalled or upgraded
     * (without the possibility of rollback) to a higher version code than was
     * rolled back from.
     *
     * @return the recently committed rollbacks
     * @throws SecurityException if the caller does not have appropriate permissions.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_ROLLBACKS,
            android.Manifest.permission.TEST_MANAGE_ROLLBACKS
    })
    public @NonNull List<RollbackInfo> getRecentlyCommittedRollbacks() {
        try {
            return mBinder.getRecentlyCommittedRollbacks().getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Status of a rollback commit. Will be one of
     * {@link #STATUS_SUCCESS}, {@link #STATUS_FAILURE},
     * {@link #STATUS_FAILURE_ROLLBACK_UNAVAILABLE}, {@link #STATUS_FAILURE_INSTALL}
     *
     * @see Intent#getIntExtra(String, int)
     */
    public static final String EXTRA_STATUS = "android.content.rollback.extra.STATUS";

    /**
     * Detailed string representation of the status, including raw details that
     * are useful for debugging.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_STATUS_MESSAGE =
            "android.content.rollback.extra.STATUS_MESSAGE";

    /**
     * Status result of committing a rollback.
     *
     * @hide
     */
    @IntDef(prefix = "STATUS_", value = {
            STATUS_SUCCESS,
            STATUS_FAILURE,
            STATUS_FAILURE_ROLLBACK_UNAVAILABLE,
            STATUS_FAILURE_INSTALL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {};

    /**
     * The rollback was successfully committed.
     */
    public static final int STATUS_SUCCESS = 0;

    /**
     * The rollback could not be committed due to some generic failure.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE = 1;

    /**
     * The rollback could not be committed because it was no longer available.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_ROLLBACK_UNAVAILABLE = 2;

    /**
     * The rollback failed to install successfully.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_INSTALL = 3;

    /**
     * Commit the rollback with given id, rolling back all versions of the
     * packages to the last good versions previously installed on the device
     * as specified in the corresponding RollbackInfo object. The
     * rollback will fail if any of the installed packages or available
     * rollbacks are inconsistent with the versions specified in the given
     * rollback object, which can happen if a package has been updated or a
     * rollback expired since the rollback object was retrieved from
     * {@link #getAvailableRollbacks()}.
     *
     * @param rollbackId ID of the rollback to commit
     * @param causePackages package versions to record as the motivation for this
     *                      rollback.
     * @param statusReceiver where to deliver the results. Intents sent to
     *                       this receiver contain {@link #EXTRA_STATUS}
     *                       and {@link #EXTRA_STATUS_MESSAGE}.
     * @throws SecurityException if the caller does not have appropriate permissions.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_ROLLBACKS,
            android.Manifest.permission.TEST_MANAGE_ROLLBACKS
    })
    public void commitRollback(int rollbackId, @NonNull List<VersionedPackage> causePackages,
            @NonNull IntentSender statusReceiver) {
        try {
            mBinder.commitRollback(rollbackId, new ParceledListSlice(causePackages),
                    mCallerPackageName, statusReceiver);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reload all persisted rollback data from device storage.
     * This API is meant to test that rollback state is properly preserved
     * across device reboot, by simulating what happens on reboot without
     * actually rebooting the device.
     *
     * Note rollbacks in the process of enabling will be lost after calling
     * this method since they are not persisted yet. Don't call this method
     * in the middle of the install process.
     *
     * @throws SecurityException if the caller does not have appropriate permissions.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.TEST_MANAGE_ROLLBACKS)
    @TestApi
    public void reloadPersistedData() {
        try {
            mBinder.reloadPersistedData();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Expire the rollback data for a given package.
     * This API is meant to facilitate testing of rollback logic for
     * expiring rollback data. Removes rollback data for available and
     * recently committed rollbacks that contain the given package.
     *
     * @param packageName the name of the package to expire data for.
     * @throws SecurityException if the caller does not have appropriate permissions.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.TEST_MANAGE_ROLLBACKS)
    @TestApi
    public void expireRollbackForPackage(@NonNull String packageName) {
        try {
            mBinder.expireRollbackForPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Block the RollbackManager for a specified amount of time.
     * This API is meant to facilitate testing of race conditions in
     * RollbackManager. Blocks RollbackManager from processing anything for
     * the given number of milliseconds.
     *
     * @param millis number of milliseconds to block the RollbackManager for
     * @throws SecurityException if the caller does not have appropriate permissions.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.TEST_MANAGE_ROLLBACKS)
    @TestApi
    public void blockRollbackManager(long millis) {
        try {
            mBinder.blockRollbackManager(millis);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
