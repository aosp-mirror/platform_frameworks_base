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
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;

/** Our special app widget host for the Search widget */
public class RecentsAppWidgetHost extends AppWidgetHost {

    /* Callbacks to notify when an app package changes */
    interface RecentsAppWidgetHostCallbacks {
        public void refreshSearchWidget();
    }

    Context mContext;
    RecentsAppWidgetHostCallbacks mCb;
    RecentsConfiguration mConfig;
    boolean mIsListening;

    public RecentsAppWidgetHost(Context context, int hostId) {
        super(context, hostId);
        mContext = context;
        mConfig = RecentsConfiguration.getInstance();
    }

    public void startListening(RecentsAppWidgetHostCallbacks cb) {
        mCb = cb;
        mIsListening = true;
        super.startListening();
    }

    @Override
    public void stopListening() {
        super.stopListening();
        // Ensure that we release any references to the callbacks
        mCb = null;
        mContext = null;
        mIsListening = false;
    }

    public boolean isListening() {
        return mIsListening;
    }

    @Override
    protected void onProviderChanged(int appWidgetId, AppWidgetProviderInfo appWidgetInfo) {
        if (mCb == null) return;

        SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        if (appWidgetId > -1 && appWidgetId == mConfig.searchBarAppWidgetId) {
            // The search provider may have changed, so just delete the old widget and bind it again
            ssp.unbindSearchAppWidget(this, appWidgetId);
            // Update the search widget
            mConfig.updateSearchBarAppWidgetId(mContext, -1);
            mCb.refreshSearchWidget();
        }
    }
}
