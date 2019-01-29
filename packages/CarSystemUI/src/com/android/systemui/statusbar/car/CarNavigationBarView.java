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

package com.android.systemui.statusbar.car;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarIconController;

/**
 * A custom navigation bar for the automotive use case.
 * <p>
 * The navigation bar in the automotive use case is more like a list of shortcuts, rendered
 * in a linear layout.
 */
class CarNavigationBarView extends LinearLayout {
    private View mNavButtons;
    private CarFacetButton mNotificationsButton;
    private CarStatusBar mCarStatusBar;
    private Context mContext;
    private View mLockScreenButtons;
    private OnTouchListener mStatusBarWindowTouchListener;


    public CarNavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    @Override
    public void onFinishInflate() {
        mNavButtons = findViewById(R.id.nav_buttons);
        mLockScreenButtons = findViewById(R.id.lock_screen_nav_buttons);

        mNotificationsButton = findViewById(R.id.notifications);
        if (mNotificationsButton != null) {
            mNotificationsButton.setOnClickListener(this::onNotificationsClick);
        }
        View mStatusIcons = findViewById(R.id.statusIcons);
        if (mStatusIcons != null) {
            // Attach the controllers for Status icons such as wifi and bluetooth if the standard
            // container is in the view.
            StatusBarIconController.DarkIconManager mDarkIconManager =
                    new StatusBarIconController.DarkIconManager(
                            mStatusIcons.findViewById(R.id.statusIcons));
            mDarkIconManager.setShouldLog(true);
            Dependency.get(StatusBarIconController.class).addIconGroup(mDarkIconManager);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mStatusBarWindowTouchListener == null) {
            return false;
        }
        // forward touch events to the status bar window so it can add a drag down
        // windows if required (Notification shade)
        mStatusBarWindowTouchListener.onTouch(this, ev);
        return false;
    }

    void setStatusBar(CarStatusBar carStatusBar) {
        mCarStatusBar = carStatusBar;
        mStatusBarWindowTouchListener = carStatusBar.getStatusBarWindowTouchListener();
    }

    protected void onNotificationsClick(View v) {
        mCarStatusBar.toggleCarNotifications();
    }

    /**
     * If there are buttons declared in the layout they will be shown and the normal
     * Nav buttons will be hidden.
     */
    public void showKeyguardButtons() {
        if (mLockScreenButtons == null) {
            return;
        }
        mLockScreenButtons.setVisibility(View.VISIBLE);
        mNavButtons.setVisibility(View.GONE);
    }

    /**
     * If there are buttons declared in the layout they will be hidden and the normal
     * Nav buttons will be shown.
     */
    public void hideKeyguardButtons() {
        if (mLockScreenButtons == null) {
            return;
        }
        mNavButtons.setVisibility(View.VISIBLE);
        mLockScreenButtons.setVisibility(View.GONE);
    }
}
