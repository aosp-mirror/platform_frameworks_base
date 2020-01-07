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

package com.android.server.stats;

import android.content.Context;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;

/**
 * SystemService containing PullAtomCallbacks that are registered with statsd.
 *
 * @hide
 */
public class StatsPullAtomService extends SystemService {
    private static final String TAG = "StatsPullAtomService";
    private static final boolean DEBUG = true;

    public StatsPullAtomService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        // No op.
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            BackgroundThread.getHandler().post(() -> {
                registerAllPullers();
            });
        }
    }

    void registerAllPullers() {
        if (DEBUG) {
            Slog.d(TAG, "Registering all pullers with statsd");
        }
    }
}
