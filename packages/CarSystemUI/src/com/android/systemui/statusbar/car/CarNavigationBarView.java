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
import android.graphics.Rect;
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
    private CarNavigationButton mNotificationsButton;
    private CarStatusBar mCarStatusBar;
    private Context mContext;
    private View mLockScreenButtons;
    // used to wire in open/close gestures for notifications
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
        // needs to be clickable so that it will receive ACTION_MOVE events
        setClickable(true);
    }

    // Used to forward touch events even if the touch was initiated from a child component
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mStatusBarWindowTouchListener != null) {
            boolean shouldConsumeEvent = shouldConsumeNotificationButtonEvent(ev);
            // Forward touch events to the status bar window so it can drag
            // windows if required (Notification shade)
            mStatusBarWindowTouchListener.onTouch(this, ev);
            // return true if child views should not receive this event.
            if (shouldConsumeEvent) {
                return true;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    /**
     * If the motion event is over top of the notification button while the notification
     * panel is open, we need the statusbar touch listeners handle the event instead of the button.
     * Since the statusbar listener will trigger a close of the notification panel before the
     * any button click events are fired this will prevent reopening the panel.
     *
     * Note: we can't use requestDisallowInterceptTouchEvent because the gesture detector will
     * always receive the ACTION_DOWN and thus think a longpress happened if no other events are
     * received
     *
     * @return true if the notification button should not receive the event
     */
    private boolean shouldConsumeNotificationButtonEvent(MotionEvent ev) {
        if (mNotificationsButton == null || !mCarStatusBar.isNotificationPanelOpen()) {
            return false;
        }
        Rect notificationButtonLocation = new Rect();
        mNotificationsButton.getHitRect(notificationButtonLocation);
        return notificationButtonLocation.contains((int) ev.getX(), (int) ev.getY());
    }


    void setStatusBar(CarStatusBar carStatusBar) {
        mCarStatusBar = carStatusBar;
    }

    /**
     * Set a touch listener that will be called from onInterceptTouchEvent and onTouchEvent
     *
     * @param statusBarWindowTouchListener The listener to call from touch and intercept touch
     */
    void setStatusBarWindowTouchListener(OnTouchListener statusBarWindowTouchListener) {
        mStatusBarWindowTouchListener = statusBarWindowTouchListener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mStatusBarWindowTouchListener != null) {
            mStatusBarWindowTouchListener.onTouch(this, event);
        }
        return super.onTouchEvent(event);
    }

    protected void onNotificationsClick(View v) {
        mCarStatusBar.togglePanel();
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
        if (mLockScreenButtons == null) return;

        mNavButtons.setVisibility(View.VISIBLE);
        mLockScreenButtons.setVisibility(View.GONE);
    }

    /**
     * Toggles the notification unseen indicator on/off.
     *
     * @param hasUnseen true if the unseen notification count is great than 0.
     */
    void toggleNotificationUnseenIndicator(Boolean hasUnseen) {
        if (mNotificationsButton == null) return;

        mNotificationsButton.setUnseen(hasUnseen);
    }
}
