/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

public class NotificationPanelView extends PanelView implements
        ExpandableView.OnHeightChangedListener {
    public static final boolean DEBUG_GESTURES = true;

    PhoneStatusBar mStatusBar;
    private View mHeader;
    private View mKeyguardStatusView;

    private NotificationStackScrollLayout mNotificationStackScroller;
    private boolean mTrackingSettings;
    private int mNotificationTopPadding;

    public NotificationPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setStatusBar(PhoneStatusBar bar) {
        if (mStatusBar != null) {
            mStatusBar.setOnFlipRunnable(null);
        }
        mStatusBar = bar;
        if (bar != null) {
            mStatusBar.setOnFlipRunnable(new Runnable() {
                @Override
                public void run() {
                    requestPanelHeightUpdate();
                }
            });
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeader = findViewById(R.id.header);
        mKeyguardStatusView = findViewById(R.id.keyguard_status_view);
        mNotificationStackScroller = (NotificationStackScrollLayout)
                findViewById(R.id.notification_stack_scroller);
        mNotificationStackScroller.setOnHeightChangedListener(this);
        mNotificationTopPadding = getResources().getDimensionPixelSize(
                R.dimen.notifications_top_padding);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int keyguardBottomMargin =
                ((MarginLayoutParams) mKeyguardStatusView.getLayoutParams()).bottomMargin;
        mNotificationStackScroller.setTopPadding(mStatusBar.getBarState() == StatusBarState.KEYGUARD
                ? mKeyguardStatusView.getBottom() + keyguardBottomMargin
                : mHeader.getBottom() + mNotificationTopPadding);
    }

    @Override
    public void fling(float vel, boolean always) {
        GestureRecorder gr = ((PhoneStatusBarView) mBar).mBar.getGestureRecorder();
        if (gr != null) {
            gr.tag(
                "fling " + ((vel > 0) ? "open" : "closed"),
                "notifications,v=" + vel);
        }
        super.fling(vel, always);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.getText()
                    .add(getContext().getString(R.string.accessibility_desc_notification_shade));
            return true;
        }

        return super.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // intercept for quick settings
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            final View target = mStatusBar.getBarState() == StatusBarState.KEYGUARD
                    ? mKeyguardStatusView
                    : mHeader;
            final boolean inTarget = PhoneStatusBar.inBounds(target, event, true);
            if (inTarget && !isInSettings()) {
                mTrackingSettings = true;
                requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (!inTarget && isInSettings()) {
                mTrackingSettings = true;
                requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // TODO: Handle doublefinger swipe to notifications again. Look at history for a reference
        // implementation.
        if (mTrackingSettings) {
            mStatusBar.onSettingsEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                mTrackingSettings = false;
            }
            return true;
        }
        if (isInSettings()) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected boolean isScrolledToBottom() {
        if (!isInSettings()) {
            return mNotificationStackScroller.isScrolledToBottom();
        }
        return super.isScrolledToBottom();
    }

    @Override
    protected int getMaxPanelHeight() {
        if (!isInSettings()) {
            int maxPanelHeight = super.getMaxPanelHeight();
            int emptyBottomMargin = mNotificationStackScroller.getEmptyBottomMargin();
            return maxPanelHeight - emptyBottomMargin;
        }
        return super.getMaxPanelHeight();
    }

    private boolean isInSettings() {
        return mStatusBar != null && mStatusBar.isFlippedToSettings();
    }

    @Override
    protected void onHeightUpdated(float expandedHeight) {
        mNotificationStackScroller.setStackHeight(expandedHeight);
    }

    @Override
    protected int getDesiredMeasureHeight() {
        return mMaxPanelHeight;
    }

    @Override
    protected void onExpandingStarted() {
        super.onExpandingStarted();
        mNotificationStackScroller.onExpansionStarted();
    }

    @Override
    protected void onExpandingFinished() {
        super.onExpandingFinished();
        mNotificationStackScroller.onExpansionStopped();
    }

    @Override
    public void onHeightChanged(ExpandableView view) {
        requestPanelHeightUpdate();
    }
}
