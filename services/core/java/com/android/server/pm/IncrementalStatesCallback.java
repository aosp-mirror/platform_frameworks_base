/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

/**
 * Package states callback, used to listen for package state changes and send broadcasts
 */
final class IncrementalStatesCallback implements IncrementalStates.Callback {
    private final String mPackageName;
    private final PackageManagerService mPm;

    IncrementalStatesCallback(String packageName, PackageManagerService pm) {
        mPackageName = packageName;
        mPm = pm;
    }

    @Override
    public void onPackageFullyLoaded() {
        final String codePath;
        synchronized (mPm.mLock) {
            final PackageSetting ps = mPm.mSettings.getPackageLPr(mPackageName);
            if (ps == null) {
                return;
            }
            codePath = ps.getPathString();
        }
        // Unregister progress listener
        mPm.mIncrementalManager.unregisterLoadingProgressCallbacks(codePath);
        // Make sure the information is preserved
        mPm.scheduleWriteSettingsLocked();
    }
}
