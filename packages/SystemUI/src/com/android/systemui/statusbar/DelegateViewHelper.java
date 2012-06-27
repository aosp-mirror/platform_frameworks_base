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

import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.android.systemui.R;

public class DelegateViewHelper {
    private View mDelegateView;
    private View mSourceView;
    private BaseStatusBar mBar;
    private int[] mTempPoint = new int[2];
    private float[] mDownPoint = new float[2];
    private float mTriggerThreshhold;
    private boolean mPanelShowing;

    RectF mInitialTouch = new RectF();
    private boolean mStarted;
    private boolean mSwapXY = false;

    public DelegateViewHelper(View sourceView) {
        setSourceView(sourceView);
    }

    public void setDelegateView(View view) {
        mDelegateView = view;
    }

    public void setBar(BaseStatusBar phoneStatusBar) {
        mBar = phoneStatusBar;
    }

    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mSourceView == null || mDelegateView == null
                || mBar.shouldDisableNavbarGestures() || mBar.inKeyguardRestrictedInputMode()) {
            return false;
        }

        mSourceView.getLocationOnScreen(mTempPoint);
        final float sourceX = mTempPoint[0];
        final float sourceY = mTempPoint[1];


        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mPanelShowing = mDelegateView.getVisibility() == View.VISIBLE;
                mDownPoint[0] = event.getX();
                mDownPoint[1] = event.getY();
                mStarted = mInitialTouch.contains(mDownPoint[0] + sourceX, mDownPoint[1] + sourceY);
                break;
        }

        if (!mStarted) {
            return false;
        }

        if (!mPanelShowing && event.getAction() == MotionEvent.ACTION_MOVE) {
            final int historySize = event.getHistorySize();
            for (int k = 0; k < historySize + 1; k++) {
                float x = k < historySize ? event.getHistoricalX(k) : event.getX();
                float y = k < historySize ? event.getHistoricalY(k) : event.getY();
                final float distance = mSwapXY ? (mDownPoint[0] - x) : (mDownPoint[1] - y);
                if (distance > mTriggerThreshhold) {
                    mBar.showSearchPanel();
                    mPanelShowing = true;
                    break;
                }
            }
        }

        mDelegateView.getLocationOnScreen(mTempPoint);
        final float delegateX = mTempPoint[0];
        final float delegateY = mTempPoint[1];

        float deltaX = sourceX - delegateX;
        float deltaY = sourceY - delegateY;
        event.offsetLocation(deltaX, deltaY);
        mDelegateView.dispatchTouchEvent(event);
        event.offsetLocation(-deltaX, -deltaY);
        return mPanelShowing;
    }

    public void setSourceView(View view) {
        mSourceView = view;
        if (mSourceView != null) {
            mTriggerThreshhold = mSourceView.getContext().getResources()
                    .getDimension(R.dimen.navbar_search_up_threshhold);
        }
    }

    /**
     * Selects the initial touch region based on a list of views.  This is meant to be called by
     * a container widget on children over which the initial touch should be detected.  Note this
     * will compute a minimum bound that contains all specified views.
     *
     * @param views
     */
    public void setInitialTouchRegion(View ... views) {
        RectF bounds = new RectF();
        int p[] = new int[2];
        for (int i = 0; i < views.length; i++) {
            View view = views[i];
            if (view == null) continue;
            view.getLocationOnScreen(p);
            if (i == 0) {
                bounds.set(p[0], p[1], p[0] + view.getWidth(), p[1] + view.getHeight());
            } else {
                bounds.union(p[0], p[1], p[0] + view.getWidth(), p[1] + view.getHeight());
            }
        }
        mInitialTouch.set(bounds);
    }

    /**
     * When rotation is set to NO_SENSOR, then this allows swapping x/y for gesture detection
     * @param swap
     */
    public void setSwapXY(boolean swap) {
        mSwapXY = swap;
    }
}