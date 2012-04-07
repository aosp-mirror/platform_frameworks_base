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

package com.android.systemui.statusbar;

import android.view.MotionEvent;
import android.view.Surface;
import android.view.VelocityTracker;
import android.view.View;

public class DelegateViewHelper {
    private static final int VELOCITY_THRESHOLD = 1000;
    private VelocityTracker mVelocityTracker;
    private View mDelegateView;
    private View mSourceView;
    private BaseStatusBar mBar;
    private int[] mTempPoint = new int[2];
    private int mOrientation;

    public DelegateViewHelper(View sourceView) {
        mSourceView = sourceView;
    }

    public void setDelegateView(View view) {
        mDelegateView = view;
    }

    public void setBar(BaseStatusBar phoneStatusBar) {
        mBar = phoneStatusBar;
    }

    public void setOrientation(int orientation) {
        mOrientation = orientation;
    }

    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mVelocityTracker = VelocityTracker.obtain();
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                break;
        }
        if (mVelocityTracker != null) {
            if (mDelegateView != null && mDelegateView.getVisibility() != View.VISIBLE) {
                mVelocityTracker.addMovement(event);
                mVelocityTracker.computeCurrentVelocity(1000);
                final boolean isVertical = (mOrientation == Surface.ROTATION_90
                        || mOrientation == Surface.ROTATION_270);
                float velocity = isVertical ? - mVelocityTracker.getXVelocity()
                        : - mVelocityTracker.getYVelocity();
                if (velocity > VELOCITY_THRESHOLD) {
                    if (mDelegateView != null && mDelegateView.getVisibility() != View.VISIBLE) {
                        mBar.showSearchPanel();
                    }
                }
            }
        }
        if (mDelegateView != null) {
            mSourceView.getLocationOnScreen(mTempPoint);
            float deltaX = mTempPoint[0];
            float deltaY = mTempPoint[1];

            mDelegateView.getLocationOnScreen(mTempPoint);
            deltaX -= mTempPoint[0];
            deltaY -= mTempPoint[1];

            event.offsetLocation(deltaX, deltaY);
            mDelegateView.dispatchTouchEvent(event);
            event.offsetLocation(-deltaX, -deltaY);
        }
        return false;
    }
}