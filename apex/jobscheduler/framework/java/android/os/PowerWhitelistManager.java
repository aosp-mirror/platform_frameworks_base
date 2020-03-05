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

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;

/**
 * Interface to access and modify the power save whitelist.
 *
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.POWER_WHITELIST_MANAGER)
public class PowerWhitelistManager {
    private final Context mContext;
    // Proxy to DeviceIdleController for now
    // TODO: migrate to PowerWhitelistController
    private final IDeviceIdleController mService;

    /**
     * Indicates that an unforeseen event has occurred and the app should be whitelisted to handle
     * it.
     */
    public static final int EVENT_UNSPECIFIED = 0;

    /**
     * Indicates that an SMS event has occurred and the app should be whitelisted to handle it.
     */
    public static final int EVENT_SMS = 1;

    /**
     * Indicates that an MMS event has occurred and the app should be whitelisted to handle it.
     */
    public static final int EVENT_MMS = 2;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"EVENT_"}, value = {
            EVENT_UNSPECIFIED,
            EVENT_SMS,
            EVENT_MMS,
    })
    public @interface WhitelistEvent {
    }

    /**
     * @hide
     */
    public PowerWhitelistManager(@NonNull Context context) {
        mContext = context;
        mService = context.getSystemService(DeviceIdleManager.class).getService();
    }

    /**
     * Add the specified package to the permanent power save whitelist.
     */
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void addToWhitelist(@NonNull String packageName) {
        addToWhitelist(Collections.singletonList(packageName));
    }

    /**
     * Add the specified packages to the permanent power save whitelist.
     */
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void addToWhitelist(@NonNull List<String> packageNames) {
        try {
            mService.addPowerSaveWhitelistApps(packageNames);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a list of app IDs of app that are whitelisted. This does not include temporarily
     * whitelisted apps.
     *
     * @param includingIdle Set to true if the app should be whitelisted from device idle as well
     *                      as other power save restrictions
     * @hide
     */
    @NonNull
    public int[] getWhitelistedAppIds(boolean includingIdle) {
        try {
            if (includingIdle) {
                return mService.getAppIdWhitelist();
            } else {
                return mService.getAppIdWhitelistExceptIdle();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the app is whitelisted from power save restrictions. This does not include
     * temporarily whitelisted apps.
     *
     * @param includingIdle Set to true if the app should be whitelisted from device
     *                      idle as well as other power save restrictions
     * @hide
     */
    public boolean isWhitelisted(@NonNull String packageName, boolean includingIdle) {
        try {
            if (includingIdle) {
                return mService.isPowerSaveWhitelistApp(packageName);
            } else {
                return mService.isPowerSaveWhitelistExceptIdleApp(packageName);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add an app to the temporary whitelist for a short amount of time.
     *
     * @param packageName The package to add to the temp whitelist
     * @param durationMs How long to keep the app on the temp whitelist for (in milliseconds)
     */
    @RequiresPermission(android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST)
    public void whitelistAppTemporarily(@NonNull String packageName, long durationMs) {
        String reason = "from:" + UserHandle.formatUid(Binder.getCallingUid());
        try {
            mService.addPowerSaveTempWhitelistApp(packageName, durationMs, mContext.getUserId(),
                    reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add an app to the temporary whitelist for a short amount of time for a specific reason.
     *
     * @param packageName The package to add to the temp whitelist
     * @param event The reason to add the app to the temp whitelist
     * @param reason A human-readable reason explaining why the app is temp whitelisted. Only used
     *               for logging purposes
     * @return The duration (in milliseconds) that the app is whitelisted for
     */
    @RequiresPermission(android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST)
    public long whitelistAppTemporarilyForEvent(@NonNull String packageName,
            @WhitelistEvent int event, @NonNull String reason) {
        try {
            switch (event) {
                case EVENT_MMS:
                    return mService.addPowerSaveTempWhitelistAppForMms(
                            packageName, mContext.getUserId(), reason);
                case EVENT_SMS:
                    return mService.addPowerSaveTempWhitelistAppForSms(
                            packageName, mContext.getUserId(), reason);
                case EVENT_UNSPECIFIED:
                default:
                    return mService.whitelistAppTemporarily(
                            packageName, mContext.getUserId(), reason);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
