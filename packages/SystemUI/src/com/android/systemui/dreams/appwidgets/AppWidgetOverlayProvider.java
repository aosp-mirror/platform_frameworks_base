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

import android.appwidget.AppWidgetHostView;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.systemui.dreams.OverlayHost;
import com.android.systemui.dreams.OverlayHostView;
import com.android.systemui.dreams.OverlayProvider;
import com.android.systemui.plugins.ActivityStarter;

import javax.inject.Inject;

/**
 * {@link AppWidgetOverlayProvider} is an implementation of {@link OverlayProvider} for providing
 * app widget-based overlays.
 */
public class AppWidgetOverlayProvider implements OverlayProvider {
    private static final String TAG = "AppWdgtOverlayProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final ActivityStarter mActivityStarter;
    private final AppWidgetProvider mAppWidgetProvider;
    private final ComponentName mComponentName;
    private final OverlayHostView.LayoutParams mLayoutParams;

    @Inject
    public AppWidgetOverlayProvider(ActivityStarter activityStarter,
            ComponentName componentName, AppWidgetProvider widgetProvider,
            OverlayHostView.LayoutParams layoutParams) {
        mActivityStarter = activityStarter;
        mComponentName = componentName;
        mAppWidgetProvider = widgetProvider;
        mLayoutParams = layoutParams;
    }

    @Override
    public void onCreateOverlay(Context context, OverlayHost.CreationCallback creationCallback,
            OverlayHost.InteractionCallback interactionCallback) {
        final AppWidgetHostView widget = mAppWidgetProvider.getWidget(mComponentName);

        if (widget == null) {
            Log.e(TAG, "could not create widget");
            return;
        }

        widget.setInteractionHandler((view, pendingIntent, response) -> {
            if (pendingIntent.isActivity()) {
                if (DEBUG) {
                    Log.d(TAG, "launching pending intent from app widget:" + mComponentName);
                }
                interactionCallback.onExit();
                mActivityStarter.startPendingIntentDismissingKeyguard(pendingIntent,
                        null /*intentSentUiThreadCallback*/, view);
                return true;
            } else {
                return RemoteViews.startPendingIntent(view, pendingIntent,
                        response.getLaunchOptions(view));
            }
        });

        creationCallback.onCreated(widget, mLayoutParams);
    }
}
