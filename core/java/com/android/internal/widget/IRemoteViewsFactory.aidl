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

package com.android.internal.widget;

import android.content.Intent;
import android.widget.RemoteViews;

/** {@hide} */
interface IRemoteViewsFactory {
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void onDataSetChanged();
    oneway void onDataSetChangedAsync();
    oneway void onDestroy(in Intent intent);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    int getCount();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    RemoteViews getViewAt(int position);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    RemoteViews getLoadingView();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    int getViewTypeCount();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    long getItemId(int position);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean hasStableIds();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean isCreated();
    RemoteViews.RemoteCollectionItems getRemoteCollectionItems(int capSize);
}

