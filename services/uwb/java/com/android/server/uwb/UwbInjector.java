/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.uwb;

import static android.Manifest.permission.UWB_RANGING;
import static android.content.PermissionChecker.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.PermissionChecker;
import android.os.IBinder;
import android.os.ServiceManager;
import android.provider.Settings;
import android.uwb.AdapterState;
import android.uwb.IUwbAdapter;


/**
 * To be used for dependency injection (especially helps mocking static dependencies).
 */
public class UwbInjector {
    private static final String TAG = "UwbInjector";

    private static final String VENDOR_SERVICE_NAME = "uwb_vendor";

    private final Context mContext;

    public UwbInjector(@NonNull Context context) {
        mContext = context;
    }

    /**
     * @return Returns the vendor service handle.
     */
    public IUwbAdapter getVendorService() {
        IBinder b = ServiceManager.getService(VENDOR_SERVICE_NAME);
        if (b == null) return null;
        return IUwbAdapter.Stub.asInterface(b);
    }

    /**
     * Throws security exception if the UWB_RANGING permission is not granted for the calling app.
     *
     * <p>Should be used in situations where the app op should not be noted.
     */
    public void enforceUwbRangingPermissionForPreflight(
            @NonNull AttributionSource attributionSource) {
        if (!attributionSource.checkCallingUid()) {
            throw new SecurityException("Invalid attribution source " + attributionSource);
        }
        int permissionCheckResult = PermissionChecker.checkPermissionForPreflight(
                mContext, UWB_RANGING, attributionSource);
        if (permissionCheckResult != PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold UWB_RANGING permission");
        }
    }

    /**
     * Returns true if the UWB_RANGING permission is granted for the calling app.
     *
     * <p>Should be used in situations where data will be delivered and hence the app op should
     * be noted.
     */
    public boolean checkUwbRangingPermissionForDataDelivery(
            @NonNull AttributionSource attributionSource, @NonNull String message) {
        int permissionCheckResult = PermissionChecker.checkPermissionForDataDelivery(
                mContext, UWB_RANGING, -1, attributionSource, message);
        return permissionCheckResult == PERMISSION_GRANTED;
    }

    /** Returns true if UWB state saved in Settings is enabled. */
    public boolean isPersistedUwbStateEnabled() {
        final ContentResolver cr = mContext.getContentResolver();
        try {
            return Settings.Global.getInt(cr, Settings.Global.UWB_ENABLED)
                    == AdapterState.STATE_ENABLED_ACTIVE;
        } catch (Settings.SettingNotFoundException e) {
            Settings.Global.putInt(cr, Settings.Global.UWB_ENABLED,
                AdapterState.STATE_ENABLED_ACTIVE);
            return true;
        }
    }
}
