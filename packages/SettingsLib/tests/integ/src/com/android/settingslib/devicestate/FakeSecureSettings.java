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

package com.android.settingslib.devicestate;

import android.database.ContentObserver;
import android.util.Pair;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import java.util.HashMap;
import java.util.Map;

/** Fake implementation of {@link SecureSettings} that stores everything in memory. */
class FakeSecureSettings implements SecureSettings {

    private final Map<SettingsKey, String> mValues = new HashMap<>();
    private final Multimap<SettingsKey, ContentObserver> mContentObservers = HashMultimap.create();

    @Override
    public void putStringForUser(String name, String value, int userHandle) {
        SettingsKey settingsKey = new SettingsKey(userHandle, name);
        mValues.put(settingsKey, value);
        for (ContentObserver observer : mContentObservers.get(settingsKey)) {
            observer.onChange(/* selfChange= */ false);
        }
    }

    @Override
    public String getStringForUser(String name, int userHandle) {
        return mValues.getOrDefault(new SettingsKey(userHandle, name), "");
    }

    @Override
    public void registerContentObserver(String name, boolean notifyForDescendants,
            ContentObserver settingsObserver, int userHandle) {
        mContentObservers.put(new SettingsKey(userHandle, name), settingsObserver);
    }

    private static class SettingsKey extends Pair<Integer, String> {

        SettingsKey(Integer userHandle, String settingName) {
            super(userHandle, settingName);
        }
    }
}
