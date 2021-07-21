/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.injector;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.Context;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;

import com.android.server.FgThread;

/**
 * Provides accessors and listeners for location permissions, including appops.
 */
public class SystemLocationPermissionsHelper extends LocationPermissionsHelper {

    private final Context mContext;

    private boolean mInited;

    public SystemLocationPermissionsHelper(Context context, AppOpsHelper appOps) {
        super(appOps);
        mContext = context;
    }

    /** Called when system is ready. */
    public void onSystemReady() {
        if (mInited) {
            return;
        }

        mContext.getPackageManager().addOnPermissionsChangeListener(
                uid -> {
                    // invoked on ui thread, move to fg thread so ui thread isn't blocked
                    FgThread.getHandler().post(() -> notifyLocationPermissionsChanged(uid));
                });
        mInited = true;
    }

    @Override
    protected boolean hasPermission(String permission, CallerIdentity callerIdentity) {
        final long identity = Binder.clearCallingIdentity();
        try {
            return mContext.checkPermission(permission, callerIdentity.getPid(),
                    callerIdentity.getUid()) == PERMISSION_GRANTED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
