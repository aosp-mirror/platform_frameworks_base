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

package com.android.server.location.settings;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import java.io.File;

public class FakeLocationSettings extends LocationSettings {

    public FakeLocationSettings(Context context) {
        super(context);
    }

    @Override
    protected File getUserSettingsDir(int userId) {
        return ApplicationProvider.getApplicationContext().getCacheDir();
    }

    @Override
    protected LocationUserSettingsStore createUserSettingsStore(int userId, File file) {
        return new FakeLocationUserSettingsStore(userId, file);
    }

    private class FakeLocationUserSettingsStore extends LocationUserSettingsStore {

        FakeLocationUserSettingsStore(int userId, File file) {
            super(userId, file);
        }

        @Override
        protected void onChange(LocationUserSettings oldSettings,
                LocationUserSettings newSettings) {
            fireListeners(mUserId, oldSettings, newSettings);
        }
    }
}

