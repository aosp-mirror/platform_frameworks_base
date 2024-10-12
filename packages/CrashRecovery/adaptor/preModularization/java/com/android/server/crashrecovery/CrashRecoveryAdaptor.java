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
import com.android.server.SystemServiceManager;

import java.util.List;

/**
 * This class mediates calls to hidden APIs in CrashRecovery module.
 * This class is used when CrashRecovery classes are still in platform.
 *
 * @hide
 */
public class CrashRecoveryAdaptor {
    private static final String TAG = "CrashRecoveryAdaptor";

    /**  Start CrashRecoveryModule LifeCycleService */
    public static void initializeCrashrecoveryModuleService(
            SystemServiceManager mSystemServiceManager) {
        mSystemServiceManager.startService(CrashRecoveryModule.Lifecycle.class);
    }

    /**  Forward calls to PackageWatchdog noteboot  */
    public static void packageWatchdogNoteBoot(Context mSystemContext) {
        PackageWatchdog.getInstance(mSystemContext).noteBoot();
    }

    /**  Forward calls to PackageWatchdog writeNow */
    public static void packageWatchdogWriteNow(Context mContext) {
        PackageWatchdog.getInstance(mContext).writeNow();
    }

    /**  Forward calls to PackageWatchdog OnPackagesReady */
    public static void packageWatchdogOnPackagesReady(PackageWatchdog mPackageWatchdog) {
        mPackageWatchdog.onPackagesReady();
    }

    /**  Forward calls to RescueParty RegisterHealthObserver */
    public static void rescuePartyRegisterHealthObserver(Context mSystemContext) {
        RescueParty.registerHealthObserver(mSystemContext);
    }

    /**  Forward calls to RescueParty OnSettingsProviderPublished */
    public static void rescuePartyOnSettingsProviderPublished(Context mContext) {
        RescueParty.onSettingsProviderPublished(mContext);
    }

    /**  Forward calls to RescueParty ResetDeviceConfigForPackages */
    public static void rescuePartyResetDeviceConfigForPackages(List<String> packageNames) {
        RescueParty.resetDeviceConfigForPackages(packageNames);
    }
}
