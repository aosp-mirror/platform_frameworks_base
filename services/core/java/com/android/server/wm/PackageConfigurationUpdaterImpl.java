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

import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.LocaleList;
import android.util.ArraySet;
import android.util.Slog;

import java.util.Optional;

/**
 * An implementation of {@link ActivityTaskManagerInternal.PackageConfigurationUpdater}.
 */
final class PackageConfigurationUpdaterImpl implements
        ActivityTaskManagerInternal.PackageConfigurationUpdater {
    private static final String TAG = "PackageConfigurationUpdaterImpl";
    private final Optional<Integer> mPid;
    private Integer mNightMode;
    private LocaleList mLocales;
    private String mPackageName;
    private int mUserId;
    private ActivityTaskManagerService mAtm;

    PackageConfigurationUpdaterImpl(int pid, ActivityTaskManagerService atm) {
        mPid = Optional.of(pid);
        mAtm = atm;
    }

    PackageConfigurationUpdaterImpl(String packageName, int userId,
            ActivityTaskManagerService atm) {
        mPackageName = packageName;
        mUserId = userId;
        mAtm = atm;
        mPid = Optional.empty();
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
                    final int uid;
                    if (mPid.isPresent()) {
                        WindowProcessController wpc = mAtm.mProcessMap.getProcess(mPid.get());
                        if (wpc == null) {
                            Slog.w(TAG, "commit: Override application configuration failed: "
                                    + "cannot find pid " + mPid);
                            return;
                        }
                        uid = wpc.mUid;
                        mUserId = wpc.mUserId;
                        mPackageName = wpc.mInfo.packageName;
                    } else {
                        uid = mAtm.getPackageManagerInternalLocked().getPackageUid(mPackageName,
                                /* flags = */ PackageManager.MATCH_ALL, mUserId);
                        if (uid < 0) {
                            Slog.w(TAG, "commit: update of application configuration failed: "
                                    + "userId or packageName not valid " + mUserId);
                            return;
                        }
                    }
                    updateConfig(uid, mPackageName);
                    mAtm.mPackageConfigPersister.updateFromImpl(mPackageName, mUserId, this);

                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    private void updateConfig(int uid, String packageName) {
        final ArraySet<WindowProcessController> processes = mAtm.mProcessMap.getProcesses(uid);
        if (processes == null) return;
        for (int i = processes.size() - 1; i >= 0; i--) {
            final WindowProcessController wpc = processes.valueAt(i);
            if (!wpc.mInfo.packageName.equals(packageName)) continue;
            LocaleList localesOverride = LocaleOverlayHelper.combineLocalesIfOverlayExists(
                    mLocales, mAtm.getGlobalConfiguration().getLocales());
            wpc.applyAppSpecificConfig(mNightMode, localesOverride);
            wpc.updateAppSpecificSettingsForAllActivities(mNightMode, localesOverride);
        }
    }

    Integer getNightMode() {
        return mNightMode;
    }

    LocaleList getLocales() {
        return mLocales;
    }
}
