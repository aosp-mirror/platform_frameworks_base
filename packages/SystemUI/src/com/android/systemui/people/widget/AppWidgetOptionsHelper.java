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

package com.android.systemui.people.widget;

import static com.android.systemui.people.PeopleSpaceUtils.DEBUG;
import static com.android.systemui.people.PeopleSpaceUtils.EMPTY_KEY;
import static com.android.systemui.people.PeopleSpaceUtils.EMPTY_STRING;
import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.SHORTCUT_ID;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;

import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.os.Bundle;
import android.util.Log;

/** Helper class encapsulating AppWidgetOptions for People Tile. */
public class AppWidgetOptionsHelper {
    private static final String TAG = "AppWidgetOptionsHelper";

    /** Key to store {@link PeopleSpaceTile} in AppWidgetOptions Bundle. */
    public static final String OPTIONS_PEOPLE_TILE = "options_people_tile";

    /** Sets {@link PeopleSpaceTile} in AppWidgetOptions. */
    public static Bundle setPeopleTile(AppWidgetManager appWidgetManager, int appWidgetId,
            PeopleSpaceTile tile) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        if (tile == null) {
            if (DEBUG) Log.d(TAG, "Requested to store null tile");
            return options;
        }
        options.putParcelable(OPTIONS_PEOPLE_TILE, tile);
        appWidgetManager.updateAppWidgetOptions(appWidgetId, options);
        return options;
    }

    /** Gets {@link PeopleSpaceTile} from AppWidgetOptions. */
    public static PeopleSpaceTile getPeopleTile(AppWidgetManager appWidgetManager,
            int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        return options != null ? options.getParcelable(OPTIONS_PEOPLE_TILE) : null;
    }

    /** Sets {@link PeopleTileKey} in AppWidgetOptions. */
    public static void setPeopleTileKey(AppWidgetManager appWidgetManager, int appWidgetId,
            PeopleTileKey key) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        options.putString(SHORTCUT_ID, key.getShortcutId());
        options.putInt(USER_ID, key.getUserId());
        options.putString(PACKAGE_NAME, key.getPackageName());
        appWidgetManager.updateAppWidgetOptions(appWidgetId, options);
    }

    /** Gets {@link PeopleTileKey} from AppWidgetOptions. */
    public static PeopleTileKey getPeopleTileKey(AppWidgetManager appWidgetManager,
            int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        if (options == null) {
            return EMPTY_KEY;
        }
        return getPeopleTileKeyFromBundle(options);
    }

    /** Gets {@link PeopleTileKey} from Bundle {@code options}. */
    public static PeopleTileKey getPeopleTileKeyFromBundle(Bundle options) {
        String pkg = options.getString(PACKAGE_NAME, EMPTY_STRING);
        int userId = options.getInt(USER_ID, INVALID_USER_ID);
        String shortcutId = options.getString(SHORTCUT_ID, EMPTY_STRING);
        return new PeopleTileKey(shortcutId, userId, pkg);
    }

    /** Removes {@link PeopleTileKey} from AppWidgetOptions. */
    public static void removePeopleTileKey(AppWidgetManager appWidgetManager,
            int appWidgetId) {
        setPeopleTileKey(appWidgetManager, appWidgetId, EMPTY_KEY);
    }
}
