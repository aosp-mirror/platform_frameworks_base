/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;

public class RecentsAppWidgetHostView extends AppWidgetHostView {

    private Context mContext;
    private int mPreviousOrientation;

    public RecentsAppWidgetHostView(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void updateAppWidget(RemoteViews remoteViews) {
        // Store the orientation in which the widget was inflated
        updateLastInflationOrientation();
        super.updateAppWidget(remoteViews);
    }

    @Override
    protected View getErrorView() {
        // Just return an empty view as the error view when failing to inflate the Recents search
        // bar widget (this is mainly to catch the case where we try and inflate the widget view
        // while the search provider is updating)
        return new View(mContext);
    }

    /**
     * Updates the last orientation that this widget was inflated.
     */
    private void updateLastInflationOrientation() {
        mPreviousOrientation = mContext.getResources().getConfiguration().orientation;
    }

    /**
     * @return whether the search widget was updated while Recents was in a different orientation
     *         in the background.
     */
    public boolean isReinflateRequired() {
        // Re-inflate is required if the orientation has changed since last inflated.
        int orientation = mContext.getResources().getConfiguration().orientation;
        if (mPreviousOrientation != orientation) {
            return true;
        }
        return false;
    }
}
