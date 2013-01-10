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

package com.android.internal.policy.impl.keyguard;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

public class CheckLongPressHelper {
    private View mView;
    private boolean mHasPerformedLongPress;
    private CheckForLongPress mPendingCheckForLongPress;
    private float mDownX, mDownY;
    private int mLongPressTimeout;
    private int mScaledTouchSlop;

    class CheckForLongPress implements Runnable {
        public void run() {
            if ((mView.getParent() != null) && mView.hasWindowFocus()
                    && !mHasPerformedLongPress) {
                if (mView.performLongClick()) {
                    mView.setPressed(false);
                    mHasPerformedLongPress = true;
                }
            }
        }
    }

    public CheckLongPressHelper(View v) {
        mScaledTouchSlop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        mLongPressTimeout = ViewConfiguration.getLongPressTimeout();
        mView = v;
    }

    public void postCheckForLongPress(MotionEvent ev) {
        mDownX = ev.getX();
        mDownY = ev.getY();
        mHasPerformedLongPress = false;

        if (mPendingCheckForLongPress == null) {
            mPendingCheckForLongPress = new CheckForLongPress();
        }
        mView.postDelayed(mPendingCheckForLongPress, mLongPressTimeout);
    }

    public void onMove(MotionEvent ev) {
        float x = ev.getX();
        float y = ev.getY();
        boolean xMoved = Math.abs(mDownX - x) > mScaledTouchSlop;
        boolean yMoved = Math.abs(mDownY - y) > mScaledTouchSlop;

        if (xMoved || yMoved) {
            cancelLongPress();
        }
    }

    public void cancelLongPress() {
        mHasPerformedLongPress = false;
        if (mPendingCheckForLongPress != null) {
            mView.removeCallbacks(mPendingCheckForLongPress);
            mPendingCheckForLongPress = null;
        }
    }

    public boolean hasPerformedLongPress() {
        return mHasPerformedLongPress;
    }
}
