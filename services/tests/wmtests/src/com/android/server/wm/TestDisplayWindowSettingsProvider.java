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

import android.annotation.NonNull;
import android.view.DisplayInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory DisplayWindowSettingsProvider used in tests. Ensures no settings are read from or
 * written to device-specific display settings files.
 */
public final class TestDisplayWindowSettingsProvider extends DisplayWindowSettingsProvider {

    private final Map<String, SettingsEntry> mOverrideSettingsMap = new HashMap<>();

    @Override
    @NonNull
    public SettingsEntry getSettings(@NonNull DisplayInfo info) {
        // Because no settings are read from settings files, there is no need to store base
        // settings. Only override settings are necessary to track because they can be modified
        // during tests (e.g. display size, ignore orientation requests).
        return getOverrideSettings(info);
    }

    @Override
    @NonNull
    public SettingsEntry getOverrideSettings(@NonNull DisplayInfo info) {
        return new SettingsEntry(getOrCreateOverrideSettingsEntry(info));
    }

    @Override
    public void updateOverrideSettings(@NonNull DisplayInfo info,
            @NonNull SettingsEntry overrides) {
        final SettingsEntry overrideSettings = getOrCreateOverrideSettingsEntry(info);
        overrideSettings.setTo(overrides);
    }

    @Override
    public void onDisplayRemoved(@NonNull DisplayInfo info) {
        final String identifier = getIdentifier(info);
        mOverrideSettingsMap.remove(identifier);
    }

    @NonNull
    private SettingsEntry getOrCreateOverrideSettingsEntry(DisplayInfo info) {
        final String identifier = getIdentifier(info);
        SettingsEntry settings;
        if ((settings = mOverrideSettingsMap.get(identifier)) != null) {
            return settings;
        }
        settings = new SettingsEntry();
        mOverrideSettingsMap.put(identifier, settings);
        return settings;
    }

    /**
     * In {@link TestDisplayWindowSettingsProvider}, always use uniqueId as the identifier.
     */
    private static String getIdentifier(DisplayInfo displayInfo) {
        return displayInfo.uniqueId;
    }
}
