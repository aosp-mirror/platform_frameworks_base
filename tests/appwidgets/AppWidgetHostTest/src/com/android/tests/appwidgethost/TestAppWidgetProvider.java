/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.tests.appwidgethost;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.appwidget.AppWidgetManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

public class TestAppWidgetProvider extends BroadcastReceiver {
    static final String TAG = "TestAppWidgetProvider";

    static final String PREFS_NAME = "com.android.tests.appwidgethost.TestAppWidgetProvider";
    static final String PREF_PREFIX_KEY = "prefix";

    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "intent=" + intent);

        if (AppWidgetManager.ACTION_APPWIDGET_ENABLED.equals(action)) {
            Log.d(TAG, "ENABLED");
        }
        else if (AppWidgetManager.ACTION_APPWIDGET_DISABLED.equals(action)) {
            Log.d(TAG, "DISABLED");
        }
        else if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            Log.d(TAG, "UPDATE");
            // BEGIN_INCLUDE(getExtra_EXTRA_APPWIDGET_IDS)
            Bundle extras = intent.getExtras();
            int[] appWidgetIds = extras.getIntArray(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            // END_INCLUDE(getExtra_EXTRA_APPWIDGET_IDS)

            SharedPreferences prefs = context.getSharedPreferences(
                    TestAppWidgetProvider.PREFS_NAME, 0);
            String prefix = prefs.getString(PREF_PREFIX_KEY, "hai");

            AppWidgetManager gm = AppWidgetManager.getInstance(context);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.test_appwidget);
            views.setTextViewText(R.id.oh_hai_text, prefix + ": " + SystemClock.elapsedRealtime());
            if (false) {
                gm.updateAppWidget(appWidgetIds, views);
            } else {
                gm.updateAppWidget(new ComponentName("com.android.tests.appwidgethost",
                            "com.android.tests.appwidgethost.TestAppWidgetProvider"), views);
            }
        }
    }
}

