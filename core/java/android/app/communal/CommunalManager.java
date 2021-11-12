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

package android.app.communal;

import android.Manifest;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.Overridable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;

/**
 * System private class for talking with the
 * {@link com.android.server.communal.CommunalManagerService} that handles communal mode state.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
@SystemService(Context.COMMUNAL_MANAGER_SERVICE)
@RequiresFeature(PackageManager.FEATURE_COMMUNAL_MODE)
public final class CommunalManager {
    private final ICommunalManager mService;

    /**
     * This change id is used to annotate packages which can run in communal mode by default,
     * without requiring user opt-in.
     *
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    public static final long ALLOW_COMMUNAL_MODE_BY_DEFAULT = 203673428L;

    /**
     * This change id is used to annotate packages which are allowed to run in communal mode.
     *
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    public static final long ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT = 200324021L;

    /** @hide */
    public CommunalManager(ICommunalManager service) {
        mService = service;
    }

    /**
     * Updates whether or not the communal view is currently showing over the lockscreen.
     *
     * @param isShowing Whether communal view is showing.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_COMMUNAL_STATE)
    public void setCommunalViewShowing(boolean isShowing) {
        try {
            mService.setCommunalViewShowing(isShowing);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check whether or not the communal view is currently showing over the lockscreen.
     */
    @RequiresPermission(Manifest.permission.READ_COMMUNAL_STATE)
    public boolean isCommunalMode() {
        try {
            return mService.isCommunalMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
