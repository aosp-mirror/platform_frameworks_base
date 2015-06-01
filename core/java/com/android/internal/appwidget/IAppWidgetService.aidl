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
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
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
    int[] startListening(IAppWidgetHost host, String callingPackage, int hostId,
            out List<RemoteViews> updatedViews);
    void stopListening(String callingPackage, int hostId);
    int allocateAppWidgetId(String callingPackage, int hostId);
    void deleteAppWidgetId(String callingPackage, int appWidgetId);
    void deleteHost(String packageName, int hostId);
    void deleteAllHosts();
    RemoteViews getAppWidgetViews(String callingPackage, int appWidgetId);
    int[] getAppWidgetIdsForHost(String callingPackage, int hostId);
    IntentSender createAppWidgetConfigIntentSender(String callingPackage, int appWidgetId);

    //
    // for AppWidgetManager
    //
    void updateAppWidgetIds(String callingPackage, in int[] appWidgetIds, in RemoteViews views);
    void updateAppWidgetOptions(String callingPackage, int appWidgetId, in Bundle extras);
    Bundle getAppWidgetOptions(String callingPackage, int appWidgetId);
    void partiallyUpdateAppWidgetIds(String callingPackage, in int[] appWidgetIds,
            in RemoteViews views);
    void updateAppWidgetProvider(in ComponentName provider, in RemoteViews views);
    void notifyAppWidgetViewDataChanged(String packageName, in int[] appWidgetIds, int viewId);
    List<AppWidgetProviderInfo> getInstalledProvidersForProfile(int categoryFilter,
            int profileId);
    AppWidgetProviderInfo getAppWidgetInfo(String callingPackage, int appWidgetId);
    boolean hasBindAppWidgetPermission(in String packageName, int userId);
    void setBindAppWidgetPermission(in String packageName, int userId, in boolean permission);
    boolean bindAppWidgetId(in String callingPackage, int appWidgetId,
            int providerProfileId, in ComponentName providerComponent, in Bundle options);
    void bindRemoteViewsService(String callingPackage, int appWidgetId, in Intent intent,
            in IBinder connection);
    void unbindRemoteViewsService(String callingPackage, int appWidgetId, in Intent intent);
    int[] getAppWidgetIds(in ComponentName providerComponent);
}

