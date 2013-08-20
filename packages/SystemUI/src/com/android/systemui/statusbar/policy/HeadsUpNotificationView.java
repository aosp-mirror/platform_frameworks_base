/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.android.systemui.ExpandHelper;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.NotificationData;

public class HeadsUpNotificationView extends FrameLayout implements SwipeHelper.Callback, ExpandHelper.Callback {
    private static final String TAG = "HeadsUpNotificationView";
    private static final boolean DEBUG = false;
    private static final boolean SPEW = DEBUG;

    Rect mTmpRect = new Rect();

    private final int mTouchSensitivityDelay;
    private SwipeHelper mSwipeHelper;

    private BaseStatusBar mBar;
    private ExpandHelper mExpandHelper;
    private long mStartTouchTime;

    private ViewGroup mContentHolder;
    private ViewGroup mContentSlider;

    private NotificationData.Entry mHeadsUp;

    public HeadsUpNotificationView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HeadsUpNotificationView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mTouchSensitivityDelay = getResources().getInteger(R.integer.heads_up_sensitivity_delay);
        if (DEBUG) Log.v(TAG, "create() " + mTouchSensitivityDelay);
    }

    public void setBar(BaseStatusBar bar) {
        mBar = bar;
    }

    public ViewGroup getHolder() {
        return mContentHolder;
    }

    public boolean setNotification(NotificationData.Entry headsUp) {
        mHeadsUp = headsUp;
        mHeadsUp.row.setExpanded(false);
        if (mContentHolder == null) {
            // too soon!
            return false;
        }
        mContentHolder.setX(0);
        mContentHolder.setVisibility(View.VISIBLE);
        mContentHolder.setAlpha(1f);
        mContentHolder.removeAllViews();
        mContentHolder.addView(mHeadsUp.row);
        mSwipeHelper.snapChild(mContentSlider, 1f);
        mStartTouchTime = System.currentTimeMillis() + mTouchSensitivityDelay;
        return true;
    }

    public boolean isClearable() {
        return mHeadsUp == null || mHeadsUp.notification.isClearable();
    }

    public void setMargin(int notificationPanelMarginPx) {
        if (SPEW) Log.v(TAG, "setMargin() " + notificationPanelMarginPx);
        if (mContentSlider != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mContentSlider.getLayoutParams();
            lp.setMarginStart(notificationPanelMarginPx);
            mContentSlider.setLayoutParams(lp);
        }
    }

    // LinearLayout methods

    @Override
    public void onDraw(android.graphics.Canvas c) {
        super.onDraw(c);
        if (DEBUG) {
            //Log.d(TAG, "onDraw: canvas height: " + c.getHeight() + "px; measured height: "
            //        + getMeasuredHeight() + "px");
            c.save();
            c.clipRect(6, 6, c.getWidth() - 6, getMeasuredHeight() - 6,
                    android.graphics.Region.Op.DIFFERENCE);
            c.drawColor(0xFFcc00cc);
            c.restore();
        }
    }

    // ViewGroup methods

    @Override
    public void onAttachedToWindow() {
        float densityScale = getResources().getDisplayMetrics().density;
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, densityScale, pagingTouchSlop);

        int minHeight = getResources().getDimensionPixelSize(R.dimen.notification_row_min_height);
        int maxHeight = getResources().getDimensionPixelSize(R.dimen.notification_row_max_height);
        mExpandHelper = new ExpandHelper(mContext, this, minHeight, maxHeight);

        mContentHolder = (ViewGroup) findViewById(R.id.content_holder);
        mContentSlider = (ViewGroup) findViewById(R.id.content_slider);

        if (mHeadsUp != null) {
            // whoops, we're on already!
            setNotification(mHeadsUp);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.v(TAG, "onInterceptTouchEvent()");
        if (System.currentTimeMillis() < mStartTouchTime) {
            return true;
        }
        return mSwipeHelper.onInterceptTouchEvent(ev)
                || mExpandHelper.onInterceptTouchEvent(ev)
                || super.onInterceptTouchEvent(ev);
    }

    // View methods

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (System.currentTimeMillis() < mStartTouchTime) {
            return false;
        }
        mBar.resetHeadsUpDecayTimer();
        return mSwipeHelper.onTouchEvent(ev)
                || mExpandHelper.onTouchEvent(ev)
                || super.onTouchEvent(ev);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
    }

    // ExpandHelper.Callback methods

    @Override
    public View getChildAtRawPosition(float x, float y) {
        return getChildAtPosition(x, y);
    }

    @Override
    public View getChildAtPosition(float x, float y) {
        return mHeadsUp == null ? null : mHeadsUp.row;
    }

    @Override
    public boolean canChildBeExpanded(View v) {
        return mHeadsUp != null && mHeadsUp.row == v && mHeadsUp.row.isExpandable();
    }

    @Override
    public void setUserExpandedChild(View v, boolean userExpanded) {
        if (mHeadsUp != null && mHeadsUp.row == v) {
            mHeadsUp.row.setUserExpanded(userExpanded);
        }
    }

    @Override
    public void setUserLockedChild(View v, boolean userLocked) {
        if (mHeadsUp != null && mHeadsUp.row == v) {
            mHeadsUp.row.setUserLocked(userLocked);
        }
    }

    // SwipeHelper.Callback methods

    @Override
    public boolean canChildBeDismissed(View v) {
        return true;
    }

    @Override
    public void onChildDismissed(View v) {
        Log.v(TAG, "User swiped heads up to dismiss");
        mBar.onHeadsUpDismissed();
    }

    @Override
    public void onBeginDrag(View v) {
    }

    @Override
    public void onDragCancelled(View v) {
        mContentHolder.setAlpha(1f); // sometimes this isn't quite reset
    }

    @Override
    public View getChildAtPosition(MotionEvent ev) {
        return mContentSlider;
    }

    @Override
    public View getChildContentView(View v) {
        return mContentSlider;
    }
}