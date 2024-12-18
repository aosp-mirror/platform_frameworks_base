/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.locales;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.internal.content.PackageMonitor;

/**
 * Helper to monitor package states inside {@link LocaleManagerService}.
 *
 * <p> These listeners forward the call to different aspects of locale service that
 * handle the business logic.
 * <p> We're interested in the following events:
 * <ul>
 * <li> Package added
 * <li> Package data cleared
 * <li> Package removed
 * <li> Package Updated
 * </ul>
 */
final class LocaleManagerServicePackageMonitor extends PackageMonitor {
    private LocaleManagerBackupHelper mBackupHelper;
    private SystemAppUpdateTracker mSystemAppUpdateTracker;
    private LocaleManagerService mLocaleManagerService;

    LocaleManagerServicePackageMonitor(@NonNull LocaleManagerBackupHelper localeManagerBackupHelper,
            @NonNull SystemAppUpdateTracker systemAppUpdateTracker,
            @NonNull LocaleManagerService localeManagerService) {
        mBackupHelper = localeManagerBackupHelper;
        mSystemAppUpdateTracker = systemAppUpdateTracker;
        mLocaleManagerService = localeManagerService;
    }

    @Override
    public void onPackageAddedWithExtras(String packageName, int uid, Bundle extras) {
        mBackupHelper.onPackageAddedWithExtras(packageName, uid, extras);
    }

    @Override
    public void onPackageDataCleared(String packageName, int uid) {
        mBackupHelper.onPackageDataCleared(packageName, uid);
    }

    @Override
    public void onPackageRemoved(String packageName, int uid) {
        mBackupHelper.onPackageRemoved(packageName, uid);
        mLocaleManagerService.deleteOverrideLocaleConfig(packageName, UserHandle.getUserId(uid));
    }

    @Override
    public void onPackageUpdateFinished(String packageName, int uid) {
        mBackupHelper.onPackageUpdateFinished(packageName, uid);
        mSystemAppUpdateTracker.onPackageUpdateFinished(packageName, uid);
    }
}
