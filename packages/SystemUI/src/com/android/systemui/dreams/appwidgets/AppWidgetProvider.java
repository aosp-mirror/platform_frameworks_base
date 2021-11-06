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

package com.android.systemui.dreams.appwidgets;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.List;

import javax.inject.Inject;

/**
 * {@link AppWidgetProvider} is a singleton for accessing app widgets within SystemUI. This
 * consolidates resources such as the App Widget Host across potentially multiple
 * {@link AppWidgetOverlayProvider} instances and other usages.
 */
@SysUISingleton
public class AppWidgetProvider {
    private static final String TAG = "AppWidgetProvider";
    public static final int APP_WIDGET_HOST_ID = 1025;

    private final Context mContext;
    private final AppWidgetManager mAppWidgetManager;
    private final AppWidgetHost mAppWidgetHost;
    private final Resources mResources;

    @Inject
    public AppWidgetProvider(Context context, @Main Resources resources) {
        mContext = context;
        mResources = resources;
        mAppWidgetManager = android.appwidget.AppWidgetManager.getInstance(context);
        mAppWidgetHost = new AppWidgetHost(context, APP_WIDGET_HOST_ID);
        mAppWidgetHost.startListening();
    }

    /**
     * Returns an {@link AppWidgetHostView} associated with a given {@link ComponentName}.
     * @param component The {@link ComponentName} of the target {@link AppWidgetHostView}.
     * @return The {@link AppWidgetHostView} or {@code null} on error.
     */
    public AppWidgetHostView getWidget(ComponentName component) {
        final List<AppWidgetProviderInfo> appWidgetInfos =
                mAppWidgetManager.getInstalledProviders();

        for (AppWidgetProviderInfo widgetInfo : appWidgetInfos) {
            if (widgetInfo.provider.equals(component)) {
                final int widgetId = mAppWidgetHost.allocateAppWidgetId();

                boolean success = mAppWidgetManager.bindAppWidgetIdIfAllowed(widgetId,
                        widgetInfo.provider);

                if (!success) {
                    Log.e(TAG, "could not bind to app widget:" + component);
                    break;
                }

                final AppWidgetHostView appWidgetView =
                        mAppWidgetHost.createView(mContext, widgetId, widgetInfo);

                if (appWidgetView != null) {
                    // Register a layout change listener to update the widget on any sizing changes.
                    appWidgetView.addOnLayoutChangeListener(
                            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                                final float density = mResources.getDisplayMetrics().density;
                                final int height = Math.round((bottom - top) / density);
                                final int width = Math.round((right - left) / density);
                                appWidgetView.updateAppWidgetSize(null, width, height, width,
                                        height);
                            });
                }

                return appWidgetView;
            }
        }

        return null;
    }
}
