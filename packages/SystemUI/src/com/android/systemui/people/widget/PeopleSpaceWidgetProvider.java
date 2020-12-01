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

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.systemui.R;
import com.android.systemui.people.PeopleSpaceUtils;

/** People Space Widget Provider class. */
public class PeopleSpaceWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "PeopleSpaceWidgetPvd";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    public static final String EXTRA_TILE_ID = "extra_tile_id";
    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";
    public static final String EXTRA_UID = "extra_uid";

    /** Called when widget updates. */
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);

        if (DEBUG) Log.d(TAG, "onUpdate called");
        // Perform this loop procedure for each App Widget that belongs to this provider
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views =
                    new RemoteViews(context.getPackageName(), R.layout.people_space_widget);

            Intent intent = new Intent(context, PeopleSpaceWidgetService.class);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            views.setRemoteAdapter(R.id.widget_list_view, intent);

            Intent activityIntent = new Intent(context, LaunchConversationActivity.class);
            activityIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_HISTORY
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            views.setPendingIntentTemplate(R.id.widget_list_view, pendingIntent);

            // Tell the AppWidgetManager to perform an update on the current app widget
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list_view);
            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }
}
