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
import com.android.server.SystemServiceManager;

import java.util.List;

/**
 * This class mediates calls to hidden APIs in CrashRecovery module.
 * This class is used when the CrashRecovery classes are moved to separate module.
 *
 * @hide
 */
public class CrashRecoveryAdaptor {
    private static final String TAG = "CrashRecoveryAdaptor";
    private static final String CRASHRECOVERY_MODULE_LIFECYCLE_CLASS =
            "com.android.server.crashrecovery.CrashRecoveryModule$Lifecycle";

    /**  Start CrashRecoveryModule LifeCycleService */
    public static void initializeCrashrecoveryModuleService(
            SystemServiceManager mSystemServiceManager) {
        mSystemServiceManager.startService(CRASHRECOVERY_MODULE_LIFECYCLE_CLASS);
    }

    /**  Does Nothing */
    public static void packageWatchdogNoteBoot(Context mSystemContext) {
        // do nothing
    }

    /**  Does Nothing */
    public static void packageWatchdogWriteNow(Context mContext) {
        // do nothing
    }

    /**  Does Nothing */
    public static void packageWatchdogOnPackagesReady(PackageWatchdog mPackageWatchdog) {
        // do nothing
    }

    /**  Does Nothing */
    public static void rescuePartyRegisterHealthObserver(Context mSystemContext) {
        // do nothing
    }

    /**  Does Nothing */
    public static void rescuePartyOnSettingsProviderPublished(Context mContext) {
        // do nothing
    }

    /**  Does Nothing */
    public static void rescuePartyResetDeviceConfigForPackages(List<String> packageNames) {
        // do nothing
    }
}
