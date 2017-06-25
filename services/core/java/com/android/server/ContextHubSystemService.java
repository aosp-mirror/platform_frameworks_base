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

import com.android.internal.util.ConcurrentUtils;
import com.android.server.location.ContextHubService;
import com.android.server.SystemServerInitThreadPool;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.Future;

class ContextHubSystemService extends SystemService {
    private static final String TAG = "ContextHubSystemService";
    private ContextHubService mContextHubService;

    private Future<?> mInit;

    public ContextHubSystemService(Context context) {
        super(context);
        mInit = SystemServerInitThreadPool.get().submit(() -> {
            mContextHubService = new ContextHubService(context);
        }, "Init ContextHubSystemService");
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Log.d(TAG, "onBootPhase: PHASE_SYSTEM_SERVICES_READY");
            ConcurrentUtils.waitForFutureNoInterrupt(mInit,
                    "Wait for ContextHubSystemService init");
            mInit = null;
            publishBinderService(Context.CONTEXTHUB_SERVICE, mContextHubService);
        }
    }
}
