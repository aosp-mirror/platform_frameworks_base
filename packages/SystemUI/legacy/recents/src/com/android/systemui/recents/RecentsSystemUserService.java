/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.android.systemui.SysUiServiceProvider;

/**
 * A strictly system-user service that is started by the secondary user's Recents (with a limited
 * lifespan), to get the interface that the secondary user's Recents can call through to the system
 * user's Recents.
 */
public class RecentsSystemUserService extends Service {

    private static final String TAG = "RecentsSystemUserService";
    private static final boolean DEBUG = false;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        LegacyRecentsImpl recents = SysUiServiceProvider.getComponent(this, LegacyRecentsImpl.class);
        if (DEBUG) {
            Log.d(TAG, "onBind: " + recents);
        }
        if (recents != null) {
            return recents.getSystemUserCallbacks();
        }
        return null;
    }
}

