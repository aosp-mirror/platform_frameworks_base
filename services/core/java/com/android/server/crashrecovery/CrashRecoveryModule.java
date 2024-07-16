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

package com.android.server.crashrecovery;

import android.content.Context;

import com.android.server.PackageWatchdog;
import com.android.server.RescueParty;
import com.android.server.SystemService;


/** This class encapsulate the lifecycle methods of CrashRecovery module. */
public class CrashRecoveryModule {
    private static final String TAG = "CrashRecoveryModule";

    /** Lifecycle definition for CrashRecovery module. */
    public static class Lifecycle extends SystemService {
        private Context mSystemContext;
        private PackageWatchdog mPackageWatchdog;

        public Lifecycle(Context context) {
            super(context);
            mSystemContext = context;
            mPackageWatchdog = PackageWatchdog.getInstance(context);
        }

        @Override
        public void onStart() {
            RescueParty.registerHealthObserver(mSystemContext);
            mPackageWatchdog.registerShutdownBroadcastReceiver();
            mPackageWatchdog.noteBoot();
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                mPackageWatchdog.onPackagesReady();
            }
        }
    }
}
