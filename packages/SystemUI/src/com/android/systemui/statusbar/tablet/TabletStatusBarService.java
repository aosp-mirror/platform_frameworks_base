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
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Message;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Slog;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;

import com.android.systemui.statusbar.*;
import com.android.systemui.R;

public class TabletStatusBarService extends StatusBarService {
    public static final boolean DEBUG = false;
    public static final String TAG = "TabletStatusBar";

    View mStatusBarView;
    NotificationIconArea mNotificationIconArea;

    int mIconSize;

    H mHandler = new H();

    private View mNotificationPanel;
    private View mSystemPanel;
    
    protected void addPanelWindows() {
        if (mNotificationPanel == null) {
            mNotificationPanel = View.inflate(this, R.layout.sysbar_panel_notifications, null);
            mSystemPanel = View.inflate(this, R.layout.sysbar_panel_system, null);
        }

        mNotificationPanel.setVisibility(View.GONE);
        mSystemPanel.setVisibility(View.GONE);

        final Resources res = getResources();
        final int barHeight= res.getDimensionPixelSize(
            com.android.internal.R.dimen.status_bar_height);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                300, // ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.setTitle("NotificationPanel");
        lp.windowAnimations = com.android.internal.R.style.Animation_SlidingCard;

        WindowManagerImpl.getDefault().addView(mNotificationPanel, lp);

        lp = new WindowManager.LayoutParams(
                400, // ViewGroup.LayoutParams.WRAP_CONTENT,
                200, // ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.setTitle("SystemPanel");
        lp.windowAnimations = com.android.internal.R.style.Animation_SlidingCard;

        WindowManagerImpl.getDefault().addView(mSystemPanel, lp);

        // Lorem ipsum, Dolores
        TextView tv = ((TextView) mNotificationPanel.findViewById(R.id.notificationPanelDummy));
        if (tv != null) tv.setText("(You probably have new email)");
        tv = ((TextView) mSystemPanel.findViewById(R.id.systemPanelDummy));
        if (tv != null) tv.setText("System status: great");
    }

    @Override
    public void onCreate() {
        super.onCreate(); // will add the main bar view

        addPanelWindows();
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

    private class H extends Handler {
        public static final int MSG_OPEN_NOTIFICATION_PANEL = 1000;
        public static final int MSG_CLOSE_NOTIFICATION_PANEL = 1001;
        public static final int MSG_OPEN_SYSTEM_PANEL = 1010;
        public static final int MSG_CLOSE_SYSTEM_PANEL = 1011;
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_OPEN_NOTIFICATION_PANEL:
                    if (DEBUG) Slog.d(TAG, "opening notifications panel");
                    mNotificationPanel.setVisibility(View.VISIBLE);
                    break;
                case MSG_CLOSE_NOTIFICATION_PANEL:
                    if (DEBUG) Slog.d(TAG, "closing notifications panel");
                    mNotificationPanel.setVisibility(View.GONE);
                    break;
                case MSG_OPEN_SYSTEM_PANEL:
                    if (DEBUG) Slog.d(TAG, "opening system panel");
                    mSystemPanel.setVisibility(View.VISIBLE);
                    break;
                case MSG_CLOSE_SYSTEM_PANEL:
                    if (DEBUG) Slog.d(TAG, "closing system panel");
                    mSystemPanel.setVisibility(View.GONE);
                    break;
            }
        }
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
        mHandler.removeMessages(H.MSG_OPEN_NOTIFICATION_PANEL);
        mHandler.sendEmptyMessage(H.MSG_OPEN_NOTIFICATION_PANEL);
    }

    public void animateCollapse() {
        mHandler.removeMessages(H.MSG_CLOSE_NOTIFICATION_PANEL);
        mHandler.sendEmptyMessage(H.MSG_CLOSE_NOTIFICATION_PANEL);
        mHandler.removeMessages(H.MSG_CLOSE_SYSTEM_PANEL);
        mHandler.sendEmptyMessage(H.MSG_CLOSE_SYSTEM_PANEL);
    }

    public void notificationIconsClicked(View v) {
        if (DEBUG) Slog.d(TAG, "clicked notification icons");
        int msg = (mNotificationPanel.getVisibility() == View.GONE) 
            ? H.MSG_OPEN_NOTIFICATION_PANEL
            : H.MSG_CLOSE_NOTIFICATION_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    public void systemInfoClicked(View v) {
        if (DEBUG) Slog.d(TAG, "clicked system info");
        int msg = (mSystemPanel.getVisibility() == View.GONE) 
            ? H.MSG_OPEN_SYSTEM_PANEL
            : H.MSG_CLOSE_SYSTEM_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }
}
