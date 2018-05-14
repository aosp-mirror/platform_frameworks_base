/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins.statusbar;

import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.annotations.DependsOn;
import com.android.systemui.plugins.annotations.ProvidesInterface;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.OnMenuEventListener;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.MenuItem;

@ProvidesInterface(action = NotificationMenuRowPlugin.ACTION,
        version = NotificationMenuRowPlugin.VERSION)
@DependsOn(target = OnMenuEventListener.class)
@DependsOn(target = MenuItem.class)
@DependsOn(target = NotificationSwipeActionHelper.class)
@DependsOn(target = SnoozeOption.class)
public interface NotificationMenuRowPlugin extends Plugin {

    public static final String ACTION = "com.android.systemui.action.PLUGIN_NOTIFICATION_MENU_ROW";
    public static final int VERSION = 4;

    @ProvidesInterface(version = OnMenuEventListener.VERSION)
    public interface OnMenuEventListener {
        public static final int VERSION = 1;
        public void onMenuClicked(View row, int x, int y, MenuItem menu);

        public void onMenuReset(View row);

        public void onMenuShown(View row);
    }

    @ProvidesInterface(version = MenuItem.VERSION)
    public interface MenuItem {
        public static final int VERSION = 1;
        public View getMenuView();

        public View getGutsView();

        public String getContentDescription();
    }

    /**
     * @return a list of items to populate the menu 'behind' a notification.
     */
    public ArrayList<MenuItem> getMenuItems(Context context);

    /**
     * @return the {@link MenuItem} to display when a notification is long pressed.
     */
    public MenuItem getLongpressMenuItem(Context context);

    /**
     * @return the {@link MenuItem} to display when app ops icons are pressed.
     */
    public MenuItem getAppOpsMenuItem(Context context);

    /**
     * @return the {@link MenuItem} to display when snooze item is pressed.
     */
    public MenuItem getSnoozeMenuItem(Context context);

    public void setMenuItems(ArrayList<MenuItem> items);

    public void setMenuClickListener(OnMenuEventListener listener);

    public void setSwipeActionHelper(NotificationSwipeActionHelper listener);

    public void setAppName(String appName);

    public void createMenu(ViewGroup parent, StatusBarNotification sbn);

    public View getMenuView();

    public boolean isMenuVisible();

    public void resetMenu();

    public void onTranslationUpdate(float translation);

    public void onHeightUpdate();

    public void onNotificationUpdated(StatusBarNotification sbn);

    public boolean onTouchEvent(View view, MotionEvent ev, float velocity);

    public default boolean onInterceptTouchEvent(View view, MotionEvent ev) {
        return false;
    }

    public default boolean useDefaultMenuItems() {
        return false;
    }

    public default void onConfigurationChanged() {
    }
}
