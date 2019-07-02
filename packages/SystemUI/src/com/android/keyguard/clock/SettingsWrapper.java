/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * Wrapper around Settings used for testing.
 */
public class SettingsWrapper {

    private static final String CUSTOM_CLOCK_FACE = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE;
    private static final String DOCKED_CLOCK_FACE = Settings.Secure.DOCKED_CLOCK_FACE;

    private ContentResolver mContentResolver;

    public SettingsWrapper(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    /**
     * Gets the value stored in settings for the custom clock face.
     *
     * @param userId ID of the user.
     */
    public String getLockScreenCustomClockFace(int userId) {
        return Settings.Secure.getStringForUser(mContentResolver, CUSTOM_CLOCK_FACE, userId);
    }

    /**
     * Gets the value stored in settings for the clock face to use when docked.
     *
     * @param userId ID of the user.
     */
    public String getDockedClockFace(int userId) {
        return Settings.Secure.getStringForUser(mContentResolver, DOCKED_CLOCK_FACE, userId);
    }
}
