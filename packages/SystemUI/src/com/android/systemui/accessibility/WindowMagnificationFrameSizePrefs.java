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

package com.android.systemui.accessibility;

import static com.android.systemui.accessibility.WindowMagnificationSettings.MagnificationSize;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Size;

import com.android.systemui.Flags;

/**
 * Class to handle SharedPreference for window magnification size.
 */
final class WindowMagnificationFrameSizePrefs {

    private static final String WINDOW_MAGNIFICATION_PREFERENCES =
            "window_magnification_preferences";
    Context mContext;
    SharedPreferences mWindowMagnificationSizePreferences;

    WindowMagnificationFrameSizePrefs(Context context) {
        mContext = context;
        mWindowMagnificationSizePreferences = mContext
                .getSharedPreferences(WINDOW_MAGNIFICATION_PREFERENCES, Context.MODE_PRIVATE);
    }

    /**
     * Uses smallest screen width DP as the key for preference.
     */
    private String getKey() {
        return String.valueOf(
                mContext.getResources().getConfiguration().smallestScreenWidthDp);
    }

    /**
     * Saves the window frame size for current screen density.
     */
    public void saveIndexAndSizeForCurrentDensity(int index, Size size) {
        if (Flags.saveAndRestoreMagnificationSettingsButtons()) {
            mWindowMagnificationSizePreferences.edit()
                    .putString(getKey(),
                            WindowMagnificationFrameSpec.serialize(index, size)).apply();
        } else {
            mWindowMagnificationSizePreferences.edit()
                    .putString(getKey(), size.toString()).apply();
        }
    }

    /**
     * Check if there is a preference saved for current screen density.
     *
     * @return true if there is a preference saved for current screen density, false if it is unset.
     */
    public boolean isPreferenceSavedForCurrentDensity() {
        return mWindowMagnificationSizePreferences.contains(getKey());
    }

    /**
     * Gets the index preference for current screen density. Returns DEFAULT if no preference
     * is found.
     */
    public @MagnificationSize int getIndexForCurrentDensity() {
        final String spec = mWindowMagnificationSizePreferences.getString(getKey(), null);
        if (spec == null) {
            return MagnificationSize.DEFAULT;
        }
        try {
            return WindowMagnificationFrameSpec.deserialize(spec).getIndex();
        } catch (NumberFormatException e) {
            return MagnificationSize.DEFAULT;
        }
    }

    /**
     * Gets the size preference for current screen density.
     */
    public Size getSizeForCurrentDensity() {
        if (Flags.saveAndRestoreMagnificationSettingsButtons()) {
            return WindowMagnificationFrameSpec
                    .deserialize(mWindowMagnificationSizePreferences.getString(getKey(), null))
                    .getSize();
        } else {
            return Size.parseSize(mWindowMagnificationSizePreferences.getString(getKey(), null));
        }
    }

}
