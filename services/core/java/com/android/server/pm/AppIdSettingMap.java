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

import com.android.server.utils.WatchedSparseArray;

/**
 * A wrapper over {@link WatchedSparseArray} that tracks the current maximum App ID.
 */
public class AppIdSettingMap extends WatchedSparseArray<SettingBase> {
    private int mCurrentMaxAppId;

    @Override
    public void put(int key, SettingBase value) {
        if (key > mCurrentMaxAppId) {
            mCurrentMaxAppId = key;
        }
        super.put(key, value);
    }

    @Override
    public AppIdSettingMap snapshot() {
        AppIdSettingMap l = new AppIdSettingMap();
        snapshot(l, this);
        return l;
    }

    /**
     * @return the maximum of all the App IDs that have been added to the map. 0 if map is empty.
     */
    public int getCurrentMaxAppId() {
        return mCurrentMaxAppId;
    }

    /**
     * @return the next available App ID that has not been added to the map
     */
    public int getNextAvailableAppId() {
        if (mCurrentMaxAppId == 0) {
            // No app id has been added yet
            return Process.FIRST_APPLICATION_UID;
        } else {
            return mCurrentMaxAppId + 1;
        }
    }
}
