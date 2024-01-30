/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.soundpicker;

import android.content.res.Resources;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A cursor wrapper class mainly used to guarantee getting a ringtone title
 */
final class LocalizedCursor extends CursorWrapper {

    private static final String TAG = "LocalizedCursor";
    private static final String SOUND_NAME_RES_PREFIX = "sound_name_";

    private final int mTitleIndex;
    private final Resources mResources;
    private final Pattern mSanitizePattern;
    private final String mNamePrefix;

    LocalizedCursor(Cursor cursor, Resources resources, String columnLabel) {
        super(cursor);
        mTitleIndex = mCursor.getColumnIndex(columnLabel);
        mResources = resources;
        mSanitizePattern = Pattern.compile("[^a-zA-Z0-9]");
        if (mTitleIndex == -1) {
            Log.e(TAG, "No index for column " + columnLabel);
            mNamePrefix = null;
        } else {
            mNamePrefix = buildNamePrefix(mResources);
        }
    }

    /**
     * Builds the prefix for the name of the resource to look up.
     * The format is: "ResourcePackageName::ResourceTypeName/" (the type name is expected to be
     * "string" but let's not hardcode it).
     * Here we use an existing resource "notification_sound_default" which is always expected to be
     * found.
     *
     * @param resources Application's resources
     * @return the built name prefix, or null if failed to build.
     */
    @Nullable
    private static String buildNamePrefix(Resources resources) {
        try {
            return String.format("%s:%s/%s",
                    resources.getResourcePackageName(R.string.notification_sound_default),
                    resources.getResourceTypeName(R.string.notification_sound_default),
                    SOUND_NAME_RES_PREFIX);
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Failed to build the prefix for the name of the resource.", e);
        }

        return null;
    }

    /**
     * Process resource name to generate a valid resource name.
     *
     * @return a non-null String
     */
    private String sanitize(String input) {
        if (input == null) {
            return "";
        }
        return mSanitizePattern.matcher(input).replaceAll("_").toLowerCase(Locale.ROOT);
    }

    @Override
    public String getString(int columnIndex) {
        final String defaultName = mCursor.getString(columnIndex);
        if ((columnIndex != mTitleIndex) || (mNamePrefix == null)) {
            return defaultName;
        }
        TypedValue value = new TypedValue();
        try {
            // the name currently in the database is used to derive a name to match
            // against resource names in this package
            mResources.getValue(mNamePrefix + sanitize(defaultName), value,
                    /* resolveRefs= */ false);
        } catch (Resources.NotFoundException e) {
            Log.d(TAG, "Failed to get localized string. Using default string instead.", e);
            return defaultName;
        }
        if ((value != null) && (value.type == TypedValue.TYPE_STRING)) {
            Log.d(TAG, String.format("Replacing name %s with %s",
                    defaultName, value.string.toString()));
            return value.string.toString();
        } else {
            Log.e(TAG, "Invalid value when looking up localized name, using " + defaultName);
            return defaultName;
        }
    }
}
