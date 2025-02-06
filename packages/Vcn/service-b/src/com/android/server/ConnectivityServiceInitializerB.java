/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.android.tools.r8.keepanno.annotations.KeepItemKind;
import com.android.tools.r8.keepanno.annotations.UsedByReflection;

/**
 * Service initializer for VCN. This is called by system server to create a new instance of
 * VcnManagementService.
 */
// This class is reflectively invoked from SystemServer and ConnectivityServiceInitializer.
// Without this annotation, this class will be treated as unused class and be removed during build
// time.
@UsedByReflection(kind = KeepItemKind.CLASS_AND_METHODS)
@TargetApi(Build.VERSION_CODES.BAKLAVA)
public final class ConnectivityServiceInitializerB extends SystemService {
    private static final String TAG = ConnectivityServiceInitializerB.class.getSimpleName();
    private final VcnManagementService mVcnManagementService;

    public ConnectivityServiceInitializerB(Context context) {
        super(context);
        mVcnManagementService = VcnManagementService.create(context);
    }

    @Override
    public void onStart() {
        if (mVcnManagementService != null) {
            Log.i(TAG, "Registering " + Context.VCN_MANAGEMENT_SERVICE);
            publishBinderService(
                    Context.VCN_MANAGEMENT_SERVICE,
                    mVcnManagementService,
                    /* allowIsolated= */ false);
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (mVcnManagementService != null && phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
            Log.i(TAG, "Starting " + Context.VCN_MANAGEMENT_SERVICE);
            mVcnManagementService.systemReady();
        }
    }
}
