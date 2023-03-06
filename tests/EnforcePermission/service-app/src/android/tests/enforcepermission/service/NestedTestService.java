/**
 * Copyright (C) 2023 The Android Open Source Project
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

package android.tests.enforcepermission.service;

import android.annotation.EnforcePermission;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PermissionEnforcer;
import android.tests.enforcepermission.INested;
import android.util.Log;

public class NestedTestService extends Service {
    private static final String TAG = "EnforcePermission.NestedTestService";
    private INested.Stub mBinder;

    @Override
    public void onCreate() {
        mBinder = new Stub(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind");
        return mBinder;
    }

    private static class Stub extends INested.Stub {

        Stub(Context context) {
            super(PermissionEnforcer.fromContext(context));
        }

        @Override
        @EnforcePermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
        public void ProtectedByAccessNetworkState() {
            ProtectedByAccessNetworkState_enforcePermission();
        }

        @Override
        @EnforcePermission(android.Manifest.permission.READ_SYNC_SETTINGS)
        public void ProtectedByReadSyncSettings() {
            ProtectedByReadSyncSettings_enforcePermission();
        }
    }
}
