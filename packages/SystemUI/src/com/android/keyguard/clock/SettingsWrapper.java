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

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Wrapper around Settings used for testing.
 */
public class SettingsWrapper {

    private static final String TAG = "ClockFaceSettings";
    private static final String CUSTOM_CLOCK_FACE = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE;
    private static final String DOCKED_CLOCK_FACE = Settings.Secure.DOCKED_CLOCK_FACE;
    private static final String CLOCK_FIELD = "clock";

    private final ContentResolver mContentResolver;
    private final Migration mMigration;

    SettingsWrapper(ContentResolver contentResolver) {
        this(contentResolver, new Migrator(contentResolver));
    }

    @VisibleForTesting
    SettingsWrapper(ContentResolver contentResolver, Migration migration) {
        mContentResolver = contentResolver;
        mMigration = migration;
    }

    /**
     * Gets the value stored in settings for the custom clock face.
     *
     * @param userId ID of the user.
     */
    String getLockScreenCustomClockFace(int userId) {
        return decode(
                Settings.Secure.getStringForUser(mContentResolver, CUSTOM_CLOCK_FACE, userId),
                userId);
    }

    /**
     * Gets the value stored in settings for the clock face to use when docked.
     *
     * @param userId ID of the user.
     */
    String getDockedClockFace(int userId) {
        return Settings.Secure.getStringForUser(mContentResolver, DOCKED_CLOCK_FACE, userId);
    }

    /**
     * Decodes the string stored in settings, which should be formatted as JSON.
     * @param value String stored in settings. If value is not JSON, then the settings is
     *              overwritten with JSON containing the prior value.
     * @return ID of the clock face to show on AOD and lock screen. If value is not JSON, the value
     *         is returned.
     */
    @VisibleForTesting
    String decode(@Nullable String value, int userId) {
        if (value == null) {
            return value;
        }
        JSONObject json;
        try {
            json = new JSONObject(value);
        } catch (JSONException ex) {
            Log.e(TAG, "Settings value is not valid JSON", ex);
            // The settings value isn't JSON since it didn't parse so migrate the value to JSON.
            // TODO(b/135674383): Remove this migration path in the following release.
            mMigration.migrate(value, userId);
            return value;
        }
        try {
            return json.getString(CLOCK_FIELD);
        } catch (JSONException ex) {
            Log.e(TAG, "JSON object does not contain clock field.", ex);
            return null;
        }
    }

    interface Migration {
        void migrate(String value, int userId);
    }

    /**
     * Implementation of {@link Migration} that writes valid JSON back to Settings.
     */
    private static final class Migrator implements Migration {

        private final ContentResolver mContentResolver;

        Migrator(ContentResolver contentResolver) {
            mContentResolver = contentResolver;
        }

        /**
         * Migrate settings values that don't parse by converting to JSON format.
         *
         * Values in settings must be JSON to be backed up and restored. To help users maintain
         * their current settings, convert existing values into the JSON format.
         *
         * TODO(b/135674383): Remove this migration code in the following release.
         */
        @Override
        public void migrate(String value, int userId) {
            try {
                JSONObject json = new JSONObject();
                json.put(CLOCK_FIELD, value);
                Settings.Secure.putStringForUser(mContentResolver, CUSTOM_CLOCK_FACE,
                        json.toString(),
                        userId);
            } catch (JSONException ex) {
                Log.e(TAG, "Failed migrating settings value to JSON format", ex);
            }
        }
    }
}
