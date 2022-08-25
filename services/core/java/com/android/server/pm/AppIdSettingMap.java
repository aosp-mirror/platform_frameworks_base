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

package com.android.server.pm;

import android.os.Process;
import android.util.Log;

import com.android.server.utils.SnapshotCache;
import com.android.server.utils.WatchedArrayList;
import com.android.server.utils.WatchedSparseArray;
import com.android.server.utils.Watcher;

/**
 * A wrapper over {@link WatchedArrayList} that tracks the current (app ID -> SettingBase) mapping
 * for non-system apps. Also tracks system app settings in an {@link WatchedSparseArray}.
 */
final class AppIdSettingMap {
    /**
     * We use an ArrayList instead of an SparseArray for non system apps because the number of apps
     * might be big, and only ArrayList gives us a constant lookup time. For a given app ID, the
     * index to the corresponding SettingBase object is (appId - FIRST_APPLICATION_ID). If an app ID
     * doesn't exist (i.e., app is not installed), we fill the corresponding entry with null.
     */
    private final WatchedArrayList<SettingBase> mNonSystemSettings;
    private final SnapshotCache<WatchedArrayList<SettingBase>> mNonSystemSettingsSnapshot;
    private final WatchedSparseArray<SettingBase> mSystemSettings;
    private final SnapshotCache<WatchedSparseArray<SettingBase>> mSystemSettingsSnapshot;
    private int mFirstAvailableAppId = Process.FIRST_APPLICATION_UID;

    AppIdSettingMap() {
        mNonSystemSettings = new WatchedArrayList<>();
        mNonSystemSettingsSnapshot = new SnapshotCache.Auto<>(
                mNonSystemSettings, mNonSystemSettings, "AppIdSettingMap.mNonSystemSettings");
        mSystemSettings = new WatchedSparseArray<>();
        mSystemSettingsSnapshot = new SnapshotCache.Auto<>(
                mSystemSettings, mSystemSettings, "AppIdSettingMap.mSystemSettings");
    }

    AppIdSettingMap(AppIdSettingMap orig) {
        mNonSystemSettings = orig.mNonSystemSettingsSnapshot.snapshot();
        mNonSystemSettingsSnapshot = new SnapshotCache.Sealed<>();
        mSystemSettings = orig.mSystemSettingsSnapshot.snapshot();
        mSystemSettingsSnapshot = new SnapshotCache.Sealed<>();
    }

    /** Returns true if the requested AppID was valid and not already registered. */
    public boolean registerExistingAppId(int appId, SettingBase setting, Object name) {
        if (appId >= Process.FIRST_APPLICATION_UID) {
            int size = mNonSystemSettings.size();
            final int index = appId - Process.FIRST_APPLICATION_UID;
            // fill the array until our index becomes valid
            while (index >= size) {
                mNonSystemSettings.add(null);
                size++;
            }
            if (mNonSystemSettings.get(index) != null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Adding duplicate app id: " + appId
                                + " name=" + name);
                return false;
            }
            mNonSystemSettings.set(index, setting);
        } else {
            if (mSystemSettings.get(appId) != null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Adding duplicate shared id: " + appId
                                + " name=" + name);
                return false;
            }
            mSystemSettings.put(appId, setting);
        }
        return true;
    }

    public SettingBase getSetting(int appId) {
        if (appId >= Process.FIRST_APPLICATION_UID) {
            final int size = mNonSystemSettings.size();
            final int index = appId - Process.FIRST_APPLICATION_UID;
            return index < size ? mNonSystemSettings.get(index) : null;
        } else {
            return mSystemSettings.get(appId);
        }
    }

    public void removeSetting(int appId) {
        if (appId >= Process.FIRST_APPLICATION_UID) {
            final int size = mNonSystemSettings.size();
            final int index = appId - Process.FIRST_APPLICATION_UID;
            if (index < size) {
                mNonSystemSettings.set(index, null);
            }
        } else {
            mSystemSettings.remove(appId);
        }
        setFirstAvailableAppId(appId + 1);
    }

    // This should be called (at least) whenever an application is removed
    private void setFirstAvailableAppId(int uid) {
        if (uid > mFirstAvailableAppId) {
            mFirstAvailableAppId = uid;
        }
    }

    public void replaceSetting(int appId, SettingBase setting) {
        if (appId >= Process.FIRST_APPLICATION_UID) {
            final int size = mNonSystemSettings.size();
            final int index = appId - Process.FIRST_APPLICATION_UID;
            if (index < size) {
                mNonSystemSettings.set(index, setting);
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: calling replaceAppIdLpw to"
                                + " replace SettingBase at appId=" + appId
                                + " but nothing is replaced.");
            }
        } else {
            mSystemSettings.put(appId, setting);
        }
    }

    /** Returns a new AppID or -1 if we could not find an available AppID to assign */
    public int acquireAndRegisterNewAppId(SettingBase obj) {
        final int size = mNonSystemSettings.size();
        for (int i = mFirstAvailableAppId - Process.FIRST_APPLICATION_UID; i < size; i++) {
            if (mNonSystemSettings.get(i) == null) {
                mNonSystemSettings.set(i, obj);
                return Process.FIRST_APPLICATION_UID + i;
            }
        }

        // None left?
        if (size > (Process.LAST_APPLICATION_UID - Process.FIRST_APPLICATION_UID)) {
            return -1;
        }

        mNonSystemSettings.add(obj);
        return Process.FIRST_APPLICATION_UID + size;
    }

    public AppIdSettingMap snapshot() {
        return new AppIdSettingMap(this);
    }

    public void registerObserver(Watcher observer) {
        mNonSystemSettings.registerObserver(observer);
        mSystemSettings.registerObserver(observer);
    }
}
