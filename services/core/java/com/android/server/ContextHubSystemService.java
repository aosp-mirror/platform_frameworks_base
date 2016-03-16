/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.hardware.location.ContextHubService;
import android.content.Context;
import android.util.Log;

class ContextHubSystemService extends SystemService {
    private static final String TAG = "ContextHubSystemService";
    private final ContextHubService mContextHubService;

    public ContextHubSystemService(Context context) {
        super(context);
        mContextHubService = new ContextHubService(context);
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Log.d(TAG, "onBootPhase: PHASE_SYSTEM_SERVICES_READY");
            publishBinderService(ContextHubService.CONTEXTHUB_SERVICE, mContextHubService);
        }
    }
}
