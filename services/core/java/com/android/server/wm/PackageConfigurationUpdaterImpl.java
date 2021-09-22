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

package com.android.server.wm;

import android.os.Binder;
import android.os.LocaleList;
import android.util.Slog;

/**
 * An implementation of {@link ActivityTaskManagerInternal.PackageConfigurationUpdater}.
 */
final class PackageConfigurationUpdaterImpl implements
        ActivityTaskManagerInternal.PackageConfigurationUpdater {
    private static final String TAG = "PackageConfigurationUpdaterImpl";
    private final int mPid;
    private Integer mNightMode;
    private LocaleList mLocales;
    private ActivityTaskManagerService mAtm;

    PackageConfigurationUpdaterImpl(int pid, ActivityTaskManagerService atm) {
        mPid = pid;
        mAtm = atm;
    }

    @Override
    public ActivityTaskManagerInternal.PackageConfigurationUpdater setNightMode(int nightMode) {
        synchronized (this) {
            mNightMode = nightMode;
        }
        return this;
    }

    @Override
    public ActivityTaskManagerInternal.PackageConfigurationUpdater
            setLocales(LocaleList locales) {
        synchronized (this) {
            mLocales = locales;
        }
        return this;
    }

    @Override
    public void commit() {
        synchronized (this) {
            synchronized (mAtm.mGlobalLock) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    final WindowProcessController wpc = mAtm.mProcessMap.getProcess(mPid);
                    if (wpc == null) {
                        Slog.w(TAG, "Override application configuration: cannot find pid " + mPid);
                        return;
                    }
                    LocaleList localesOverride = LocaleOverlayHelper.combineLocalesIfOverlayExists(
                            mLocales, mAtm.getGlobalConfiguration().getLocales());
                    wpc.applyAppSpecificConfig(mNightMode, localesOverride);
                    wpc.updateAppSpecificSettingsForAllActivities(mNightMode, localesOverride);
                    mAtm.mPackageConfigPersister.updateFromImpl(wpc.mName, wpc.mUserId, this);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    Integer getNightMode() {
        return mNightMode;
    }

    LocaleList getLocales() {
        return mLocales;
    }
}
