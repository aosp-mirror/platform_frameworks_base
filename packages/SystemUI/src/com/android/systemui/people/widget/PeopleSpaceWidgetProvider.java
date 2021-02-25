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

import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.SHORTCUT_ID;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;

import android.app.PendingIntent;
import android.app.people.IPeopleManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.systemui.R;
import com.android.systemui.people.PeopleSpaceUtils;

import java.util.Collections;

/** People Space Widget Provider class. */
public class PeopleSpaceWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "PeopleSpaceWidgetPvd";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    public static final String EXTRA_TILE_ID = "extra_tile_id";
    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";
    public static final String EXTRA_USER_HANDLE = "extra_user_handle";
    public static final String EXTRA_NOTIFICATION_KEY = "extra_notification_key";

    public UiEventLogger mUiEventLogger = new UiEventLoggerImpl();

    /** Called when widget updates. */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);

        if (DEBUG) Log.d(TAG, "onUpdate called");
        boolean showSingleConversation = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0) == 0;
        if (showSingleConversation) {
            PeopleSpaceUtils.updateSingleConversationWidgets(context, appWidgetIds,
                    appWidgetManager, IPeopleManager.Stub.asInterface(
                            ServiceManager.getService(Context.PEOPLE_SERVICE)));
            return;
        }
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

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);

        for (int widgetId : appWidgetIds) {
            if (DEBUG) Log.d(TAG, "Widget removed");
            mUiEventLogger.log(PeopleSpaceUtils.PeopleSpaceWidgetEvent.PEOPLE_SPACE_WIDGET_DELETED);
            if (launcherApps != null) {
                SharedPreferences widgetSp = context.getSharedPreferences(String.valueOf(widgetId),
                        Context.MODE_PRIVATE);
                String packageName = widgetSp.getString(PACKAGE_NAME, null);
                String shortcutId = widgetSp.getString(SHORTCUT_ID, null);
                int userId = widgetSp.getInt(USER_ID, -1);

                if (packageName != null && shortcutId != null && userId != -1) {
                    try {
                        if (DEBUG) Log.d(TAG, "Uncaching shortcut for PeopleTile: " + shortcutId);
                        launcherApps.uncacheShortcuts(packageName,
                                Collections.singletonList(shortcutId),
                                UserHandle.of(userId),
                                LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS);
                    } catch (Exception e) {
                        Log.d(TAG, "Exception uncaching shortcut:" + e);
                    }
                }
            }
            PeopleSpaceUtils.removeStorageForTile(context, widgetId);
        }
    }
}
