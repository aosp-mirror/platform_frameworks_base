/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;

import com.android.systemui.statusbar.*;
import com.android.systemui.R;

public class TabletStatusBarService extends StatusBarService {

    View mStatusBarView;
    NotificationIconArea mNotificationIconArea;

    int mIconSize;
    

    @Override
    public void onCreate() {
        super.onCreate();
    }

    protected View makeStatusBarView() {
        Resources res = getResources();

        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);

        final View sb = View.inflate(this, R.layout.status_bar, null);
        mStatusBarView = sb;

        // the more notifications icon
        mNotificationIconArea = (NotificationIconArea)sb.findViewById(R.id.notificationIcons);

        return sb;
    }

    protected int getStatusBarGravity() {
        return Gravity.BOTTOM | Gravity.FILL_HORIZONTAL;
    }

    public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon) {
        // TODO
    }

    public void updateIcon(String slot, int index, int viewIndex,
            StatusBarIcon old, StatusBarIcon icon) {
        // TODO
    }

    public void removeIcon(String slot, int index, int viewIndex) {
        // TODO
    }

    public void addNotification(IBinder key, StatusBarNotification notification) {
        // TODO
    }

    public void updateNotification(IBinder key, StatusBarNotification notification) {
        // TODO
    }

    public void removeNotification(IBinder key) {
        // TODO
    }

    public void disable(int state) {
        // TODO
    }

    public void animateExpand() {
        // TODO
    }

    public void animateCollapse() {
        // TODO
    }
}
