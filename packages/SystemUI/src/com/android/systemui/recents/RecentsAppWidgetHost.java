/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;

/** Our special app widget host for the Search widget */
public class RecentsAppWidgetHost extends AppWidgetHost {

    /* Callbacks to notify when an app package changes */
    interface RecentsAppWidgetHostCallbacks {
        void refreshSearchWidgetView();
    }

    RecentsAppWidgetHostCallbacks mCb;
    boolean mIsListening;

    public RecentsAppWidgetHost(Context context, int hostId) {
        super(context, hostId);
    }

    public void startListening(RecentsAppWidgetHostCallbacks cb) {
        mCb = cb;
        if (!mIsListening) {
            mIsListening = true;
            super.startListening();
        }
    }

    @Override
    public void stopListening() {
        if (mIsListening) {
            super.stopListening();
        }
        // Ensure that we release any references to the callbacks
        mCb = null;
        mIsListening = false;
    }

    @Override
    protected AppWidgetHostView onCreateView(Context context, int appWidgetId,
                                             AppWidgetProviderInfo appWidget) {
        return new RecentsAppWidgetHostView(context);
    }

    /**
     * Note: this is only called for packages that have updated, not removed.
     */
    @Override
    protected void onProviderChanged(int appWidgetId, AppWidgetProviderInfo appWidgetInfo) {
        super.onProviderChanged(appWidgetId, appWidgetInfo);
        if (mIsListening && mCb != null) {
            mCb.refreshSearchWidgetView();
        }
    }
}
