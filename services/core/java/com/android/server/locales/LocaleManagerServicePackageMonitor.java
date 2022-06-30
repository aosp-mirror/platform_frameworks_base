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

    LocaleManagerServicePackageMonitor(LocaleManagerBackupHelper localeManagerBackupHelper,
            SystemAppUpdateTracker systemAppUpdateTracker) {
        mBackupHelper = localeManagerBackupHelper;
        mSystemAppUpdateTracker = systemAppUpdateTracker;
    }

    @Override
    public void onPackageAdded(String packageName, int uid) {
        mBackupHelper.onPackageAdded(packageName, uid);
    }

    @Override
    public void onPackageDataCleared(String packageName, int uid) {
        mBackupHelper.onPackageDataCleared();
    }

    @Override
    public void onPackageRemoved(String packageName, int uid) {
        mBackupHelper.onPackageRemoved();
    }

    @Override
    public void onPackageUpdateFinished(String packageName, int uid) {
        mSystemAppUpdateTracker.onPackageUpdateFinished(packageName, uid);
    }
}
