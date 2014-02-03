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
import android.util.Log;

// Should never be invoked because its manifest declaration does not
// require the necessary permission.
public class UnpermissionedTestService extends IdleService {
    private static final String TAG = "UnpermissionedTestService";

    @Override
    public boolean onIdleStart() {
        Log.e(TAG, "onIdleStart() for this service should never be called!");
        return false;
    }

    @Override
    public void onIdleStop() {
        Log.e(TAG, "onIdleStop() for this service should never be called!");
    }

}
