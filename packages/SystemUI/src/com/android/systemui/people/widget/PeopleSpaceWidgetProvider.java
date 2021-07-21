/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.people.PeopleSpaceUtils;

import javax.inject.Inject;

/** People Space Widget Provider class. */
public class PeopleSpaceWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "PeopleSpaceWidgetPvd";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    public static final String EXTRA_TILE_ID = "extra_tile_id";
    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";
    public static final String EXTRA_USER_HANDLE = "extra_user_handle";
    public static final String EXTRA_NOTIFICATION_KEY = "extra_notification_key";

    public PeopleSpaceWidgetManager mPeopleSpaceWidgetManager;

    @Inject
    PeopleSpaceWidgetProvider(PeopleSpaceWidgetManager peopleSpaceWidgetManager) {
        mPeopleSpaceWidgetManager = peopleSpaceWidgetManager;
    }

    /** Called when widget updates. */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);

        if (DEBUG) Log.d(TAG, "onUpdate called");
        ensurePeopleSpaceWidgetManagerInitialized();
        mPeopleSpaceWidgetManager.updateWidgets(appWidgetIds);
    }

    /** Called when widget updates. */
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
            int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        ensurePeopleSpaceWidgetManagerInitialized();
        mPeopleSpaceWidgetManager.onAppWidgetOptionsChanged(appWidgetId, newOptions);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        ensurePeopleSpaceWidgetManagerInitialized();
        mPeopleSpaceWidgetManager.deleteWidgets(appWidgetIds);
    }

    @Override
    public void onRestored(Context context, int[] oldWidgetIds, int[] newWidgetIds) {
        super.onRestored(context, oldWidgetIds, newWidgetIds);
        ensurePeopleSpaceWidgetManagerInitialized();
        mPeopleSpaceWidgetManager.remapWidgets(oldWidgetIds, newWidgetIds);
    }

    private void ensurePeopleSpaceWidgetManagerInitialized() {
        mPeopleSpaceWidgetManager.init();
    }

    @VisibleForTesting
    public void setPeopleSpaceWidgetManager(PeopleSpaceWidgetManager manager) {
        mPeopleSpaceWidgetManager = manager;
    }
}
