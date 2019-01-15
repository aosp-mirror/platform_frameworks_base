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

package android.os;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;

/**
 * Allows querying and posting system update information.
 *
 * {@hide}
 */
@SystemApi
@SystemService(Context.SYSTEM_UPDATE_SERVICE)
public class SystemUpdateManager {
    private static final String TAG = "SystemUpdateManager";

    /** The status key of the system update info, expecting an int value. */
    public static final String KEY_STATUS = "status";

    /** The title of the current update, expecting a String value. */
    public static final String KEY_TITLE = "title";

    /** Whether it is a security update, expecting a boolean value. */
    public static final String KEY_IS_SECURITY_UPDATE = "is_security_update";

    /** The build fingerprint after installing the current update, expecting a String value. */
    public static final String KEY_TARGET_BUILD_FINGERPRINT = "target_build_fingerprint";

    /** The security patch level after installing the current update, expecting a String value. */
    public static final String KEY_TARGET_SECURITY_PATCH_LEVEL = "target_security_patch_level";

    /**
     * The KEY_STATUS value that indicates there's no update status info available.
     */
    public static final int STATUS_UNKNOWN = 0;

    /**
     * The KEY_STATUS value that indicates there's no pending update.
     */
    public static final int STATUS_IDLE = 1;

    /**
     * The KEY_STATUS value that indicates an update is available for download, but pending user
     * approval to start.
     */
    public static final int STATUS_WAITING_DOWNLOAD = 2;

    /**
     * The KEY_STATUS value that indicates an update is in progress (i.e. downloading or installing
     * has started).
     */
    public static final int STATUS_IN_PROGRESS = 3;

    /**
     * The KEY_STATUS value that indicates an update is available for install.
     */
    public static final int STATUS_WAITING_INSTALL = 4;

    /**
     * The KEY_STATUS value that indicates an update will be installed after a reboot. This applies
     * to both of A/B and non-A/B OTAs.
     */
    public static final int STATUS_WAITING_REBOOT = 5;

    private final ISystemUpdateManager mService;

    /** @hide */
    public SystemUpdateManager(ISystemUpdateManager service) {
        mService = checkNotNull(service, "missing ISystemUpdateManager");
    }

    /**
     * Queries the current pending system update info.
     *
     * <p>Requires the {@link android.Manifest.permission#READ_SYSTEM_UPDATE_INFO} or
     * {@link android.Manifest.permission#RECOVERY} permission.
     *
     * @return A {@code Bundle} that contains the pending system update information in key-value
     * pairs.
     *
     * @throws SecurityException if the caller is not allowed to read the info.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_SYSTEM_UPDATE_INFO,
            android.Manifest.permission.RECOVERY,
    })
    public Bundle retrieveSystemUpdateInfo() {
        try {
            return mService.retrieveSystemUpdateInfo();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Allows a system updater to publish the pending update info.
     *
     * <p>The reported info will not persist across reboots. Because only the reporting updater
     * understands the criteria to determine a successful/failed update.
     *
     * <p>Requires the {@link android.Manifest.permission#RECOVERY} permission.
     *
     * @param infoBundle The {@code PersistableBundle} that contains the system update information,
     * such as the current update status. {@link #KEY_STATUS} is required in the bundle.
     *
     * @throws IllegalArgumentException if @link #KEY_STATUS} does not exist.
     * @throws SecurityException if the caller is not allowed to update the info.
     */
    @RequiresPermission(android.Manifest.permission.RECOVERY)
    public void updateSystemUpdateInfo(PersistableBundle infoBundle) {
        if (infoBundle == null || !infoBundle.containsKey(KEY_STATUS)) {
            throw new IllegalArgumentException("Missing status in the bundle");
        }
        try {
            mService.updateSystemUpdateInfo(infoBundle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }
}
