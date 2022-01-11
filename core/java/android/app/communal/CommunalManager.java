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
import android.annotation.TestApi;
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
@SystemService(Context.COMMUNAL_SERVICE)
@RequiresFeature(PackageManager.FEATURE_COMMUNAL_MODE)
public final class CommunalManager {
    private final ICommunalManager mService;

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
    @TestApi
    @RequiresPermission(Manifest.permission.WRITE_COMMUNAL_STATE)
    public void setCommunalViewShowing(boolean isShowing) {
        try {
            mService.setCommunalViewShowing(isShowing);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
