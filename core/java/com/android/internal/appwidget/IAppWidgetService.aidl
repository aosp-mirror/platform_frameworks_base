/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.appwidget;

import android.content.ComponentName;
import android.content.Intent;
import android.appwidget.AppWidgetProviderInfo;
import com.android.internal.appwidget.IAppWidgetHost;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.RemoteViews;

/** {@hide} */
interface IAppWidgetService {

    //
    // for AppWidgetHost
    //
    int[] startListening(IAppWidgetHost host, String packageName, int hostId,
            out List<RemoteViews> updatedViews, int userId);
    void stopListening(int hostId, int userId);
    int allocateAppWidgetId(String packageName, int hostId, int userId);
    void deleteAppWidgetId(int appWidgetId, int userId);
    void deleteHost(int hostId, int userId);
    void deleteAllHosts(int userId);
    RemoteViews getAppWidgetViews(int appWidgetId, int userId);
    int[] getAppWidgetIdsForHost(int hostId, int userId);

    //
    // for AppWidgetManager
    //
    void updateAppWidgetIds(in int[] appWidgetIds, in RemoteViews views, int userId);
    void updateAppWidgetOptions(int appWidgetId, in Bundle extras, int userId);
    Bundle getAppWidgetOptions(int appWidgetId, int userId);
    void partiallyUpdateAppWidgetIds(in int[] appWidgetIds, in RemoteViews views, int userId);
    void updateAppWidgetProvider(in ComponentName provider, in RemoteViews views, int userId);
    void notifyAppWidgetViewDataChanged(in int[] appWidgetIds, int viewId, int userId);
    List<AppWidgetProviderInfo> getInstalledProviders(int categoryFilter, int userId);
    AppWidgetProviderInfo getAppWidgetInfo(int appWidgetId, int userId);
    boolean hasBindAppWidgetPermission(in String packageName, int userId);
    void setBindAppWidgetPermission(in String packageName, in boolean permission, int userId);
    void bindAppWidgetId(int appWidgetId, in ComponentName provider, in Bundle options, int userId);
    boolean bindAppWidgetIdIfAllowed(in String packageName, int appWidgetId,
            in ComponentName provider, in Bundle options, int userId);
    void bindRemoteViewsService(int appWidgetId, in Intent intent, in IBinder connection, int userId);
    void unbindRemoteViewsService(int appWidgetId, in Intent intent, int userId);
    int[] getAppWidgetIds(in ComponentName provider, int userId);

}

