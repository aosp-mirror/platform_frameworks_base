/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.location.fused;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class FusedLocationService extends Service {
    private FusedLocationProvider mProvider;

    @Override
    public IBinder onBind(Intent intent) {
        if (mProvider == null) {
            mProvider = new FusedLocationProvider(getApplicationContext());
        }
        return mProvider.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // make sure to stop performing work
        if (mProvider != null) {
            mProvider.onDisable();
        }
      return false;
    }

    @Override
    public void onDestroy() {
        mProvider = null;
    }
}
