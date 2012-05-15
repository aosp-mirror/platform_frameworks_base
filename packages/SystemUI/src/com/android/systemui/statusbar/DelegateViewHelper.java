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

import android.util.Slog;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

import com.android.systemui.R;

public class DelegateViewHelper {
    private View mDelegateView;
    private View mSourceView;
    private BaseStatusBar mBar;
    private int[] mTempPoint = new int[2];
    private float[] mDownPoint = new float[2];
    private int mOrientation;
    private float mTriggerThreshhold;

    public DelegateViewHelper(View sourceView) {
        mSourceView = sourceView;
        if (mSourceView != null) {
            mTriggerThreshhold = mSourceView.getContext().getResources()
                    .getDimension(R.dimen.navbar_search_up_threshhold);
        }
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
        if (mBar.shouldDisableNavbarGestures()) {
            return false;
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDownPoint[0] = event.getX();
                mDownPoint[1] = event.getY();
                break;
        }
        if (mDelegateView != null) {
            if (mDelegateView.getVisibility() != View.VISIBLE && event.getAction() != MotionEvent.ACTION_CANCEL) {
                final boolean isVertical = (mOrientation == Surface.ROTATION_90
                        || mOrientation == Surface.ROTATION_270);
                final int historySize = event.getHistorySize();
                for (int k = 0; k < historySize + 1; k++) {
                    float x = k < historySize ? event.getHistoricalX(k) : event.getX();
                    float y = k < historySize ? event.getHistoricalY(k) : event.getY();
                    final float distance = isVertical ? (mDownPoint[0] - x) : (mDownPoint[1] - y);
                    if (distance > mTriggerThreshhold) {
                        mBar.showSearchPanel();
                        break;
                    }
                }
            }
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