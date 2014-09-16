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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import com.android.systemui.statusbar.BaseStatusBar;

public class NavigationBarViewTaskSwitchHelper extends GestureDetector.SimpleOnGestureListener {

    private BaseStatusBar mBar;
    private boolean mIsVertical;
    private boolean mIsRTL;

    private final GestureDetector mTaskSwitcherDetector;
    private final int mScrollTouchSlop;
    private final int mMinFlingVelocity;
    private int mTouchDownX;
    private int mTouchDownY;

    public NavigationBarViewTaskSwitchHelper(Context context) {
        ViewConfiguration configuration = ViewConfiguration.get(context);
        mScrollTouchSlop = 4 * configuration.getScaledTouchSlop();
        mMinFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        mTaskSwitcherDetector = new GestureDetector(context, this);
    }

    public void setBar(BaseStatusBar phoneStatusBar) {
        mBar = phoneStatusBar;
    }

    public void setBarState(boolean isVertical, boolean isRTL) {
        mIsVertical = isVertical;
        mIsRTL = isRTL;
    }

    public boolean onInterceptTouchEvent(MotionEvent event) {
        // If we move more than a fixed amount, then start capturing for the
        // task switcher detector
        mTaskSwitcherDetector.onTouchEvent(event);
        int action = event.getAction();
        boolean intercepted = false;
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                mTouchDownX = (int) event.getX();
                mTouchDownY = (int) event.getY();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int x = (int) event.getX();
                int y = (int) event.getY();
                int xDiff = Math.abs(x - mTouchDownX);
                int yDiff = Math.abs(y - mTouchDownY);
                boolean exceededTouchSlop = !mIsVertical
                        ? xDiff > mScrollTouchSlop && xDiff > yDiff
                        : yDiff > mScrollTouchSlop && yDiff > xDiff;
                if (exceededTouchSlop) {
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                break;
        }
        return intercepted;
    }

    public boolean onTouchEvent(MotionEvent event) {
        return mTaskSwitcherDetector.onTouchEvent(event);
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        float absVelX = Math.abs(velocityX);
        float absVelY = Math.abs(velocityY);
        boolean isValidFling = absVelX > mMinFlingVelocity &&
                mIsVertical ? (absVelY > absVelX) : (absVelX > absVelY);
        if (isValidFling) {
            boolean showNext;
            if (!mIsRTL) {
                showNext = mIsVertical ? (velocityY < 0) : (velocityX < 0);
            } else {
                // In RTL, vertical is still the same, but horizontal is flipped
                showNext = mIsVertical ? (velocityY < 0) : (velocityX > 0);
            }
            if (showNext) {
                mBar.showNextAffiliatedTask();
            } else {
                mBar.showPreviousAffiliatedTask();
            }
        }
        return true;
    }
}
