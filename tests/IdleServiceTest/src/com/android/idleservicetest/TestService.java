/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.idleservicetest;

import android.app.maintenance.IdleService;
import android.os.Handler;
import android.util.Log;

public class TestService extends IdleService {
    static final String TAG = "TestService";

    @Override
    public boolean onIdleStart() {
        Log.i(TAG, "Idle maintenance: onIdleStart()");

        Handler h = new Handler();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Explicitly finishing idle");
                finishIdle();
            }
        };
        Log.i(TAG, "Posting explicit finish in 15 seconds");
        h.postDelayed(r, 15 * 1000);
        return true;
    }

    @Override
    public void onIdleStop() {
        Log.i(TAG, "Idle maintenance: onIdleStop()");
    }

}
