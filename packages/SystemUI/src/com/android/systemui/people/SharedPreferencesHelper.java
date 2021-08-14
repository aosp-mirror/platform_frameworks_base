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

package com.android.systemui.people;

import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.SHORTCUT_ID;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;

import android.content.SharedPreferences;

import com.android.systemui.people.widget.PeopleTileKey;

/** Helper class for Conversations widgets SharedPreferences storage. */
public class SharedPreferencesHelper {
    /** Clears all storage from {@code sp}. */
    public static void clear(SharedPreferences sp) {
        SharedPreferences.Editor editor = sp.edit();
        editor.clear();
        editor.apply();
    }

    /** Sets {@code sp}'s storage to identify a {@link PeopleTileKey}. */
    public static void setPeopleTileKey(SharedPreferences sp, PeopleTileKey key) {
        setPeopleTileKey(sp, key.getShortcutId(), key.getUserId(), key.getPackageName());
    }

    /** Sets {@code sp}'s storage to identify a {@link PeopleTileKey}. */
    public static void setPeopleTileKey(SharedPreferences sp, String shortcutId, int userId,
            String packageName) {
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(SHORTCUT_ID, shortcutId);
        editor.putInt(USER_ID, userId);
        editor.putString(PACKAGE_NAME, packageName);
        editor.apply();
    }

    /** Returns a {@link PeopleTileKey} based on storage from {@code sp}. */
    public static PeopleTileKey getPeopleTileKey(SharedPreferences sp) {
        String shortcutId = sp.getString(SHORTCUT_ID, null);
        String packageName = sp.getString(PACKAGE_NAME, null);
        int userId = sp.getInt(USER_ID, INVALID_USER_ID);
        return new PeopleTileKey(shortcutId, userId, packageName);
    }
}
