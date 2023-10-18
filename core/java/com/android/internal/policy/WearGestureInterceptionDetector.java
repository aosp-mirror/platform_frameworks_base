/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.policy;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * Wear-specific gesture interception detector to be installed at DecorView, for compatibility of
 * apps depending on legacy SwipeDismissLayout behavior.
 *
 * <p>Results of the detector will be used by {@code DecorView} to intercept motion events. The
 * interception state will also be sent to {@code android.view.ViewRootImpl} and {@code
 * com.android.server.wm.DisplayContent} through {@code android.view.IWindowSession}.
 *
 * <p>SystemUI can register {@code android.view.IDecorViewGestureListener} to listen for the result
 * of the detector. The result will be valid for between a pair of touch down/up events.
 */
public class WearGestureInterceptionDetector {
    private static final boolean DEBUG = false;
    private static final String TAG = "WearGestureInterceptionDetector";

    private final DecorView mInstalledDecorView;
    private final float mTouchSlop;
    private final float mSwipingStartThreshold;
    private boolean mSwiping;

    private float mDownX;
    private float mDownY;
    private int mActivePointerId;
    private boolean mDiscardIntercept;

    WearGestureInterceptionDetector(Context context, DecorView installedDecorView) {
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mInstalledDecorView = installedDecorView;
        mSwipingStartThreshold = mTouchSlop * 2;
    }

    /** Check if this gesture interception detector should be enabled. */
    public static boolean isEnabled(Context context) {
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            return false;
        }

        // Compatibility check for flag that disables legacy SwipeDismissLayout.
        TypedArray windowAttr =
                context.obtainStyledAttributes(new int[] {android.R.attr.windowSwipeToDismiss});
        boolean windowSwipeToDismiss = true;
        if (windowAttr.getIndexCount() > 0) {
            windowSwipeToDismiss = windowAttr.getBoolean(0, true);
        }
        windowAttr.recycle();
        return windowSwipeToDismiss;
    }

    private int getIndexForValidPointer(MotionEvent ev) {
        int pointerIndex = ev.findPointerIndex(mActivePointerId);
        if (pointerIndex == -1) {
            if (DEBUG) {
                Log.e(TAG, "Invalid pointer index: ignoring.");
            }
            mDiscardIntercept = true;
        }
        return pointerIndex;
    }

    private void updateSwiping(MotionEvent ev) {
        if (mSwiping) {
            return;
        }
        float deltaX = ev.getRawX() - mDownX;
        float deltaY = ev.getRawY() - mDownY;
        // Check if we have left the touch slop area.
        if ((deltaX * deltaX) + (deltaY * deltaY) > (mTouchSlop * mTouchSlop)) {
            mSwiping = deltaX > mSwipingStartThreshold && Math.abs(deltaY) < Math.abs(deltaX);
        }
    }

    private void updateDiscardIntercept(MotionEvent ev, int pointerIndex) {
        if (!mSwiping) {
            // Don't look at canScroll until we have passed the touch slop
            return;
        }
        if (mDiscardIntercept) {
            return;
        }
        final boolean checkLeft = mDownX < ev.getRawX();
        final float x = ev.getX(pointerIndex);
        final float y = ev.getY(pointerIndex);
        if (canScroll(mInstalledDecorView, false, checkLeft, x, y)) {
            mDiscardIntercept = true;
        }
    }

    /** Resets internal members when canceling. */
    private void resetMembers() {
        mDownX = 0;
        mDownY = 0;
        mSwiping = false;
        mDiscardIntercept = false;
    }

    /** Should we intercept the MotionEvent for system gesture? */
    public boolean isIntercepting() {
        return !mDiscardIntercept && mSwiping;
    }

    /** Tests if the MotionEvent should be intercepted */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetMembers();
                mDownX = ev.getRawX();
                mDownY = ev.getRawY();
                mActivePointerId = ev.getPointerId(0);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mActivePointerId = ev.getPointerId(ev.getActionIndex());
                break;
            case MotionEvent.ACTION_POINTER_UP:
                int associatedPointerIndex = ev.getActionIndex();
                if (ev.getPointerId(associatedPointerIndex) == mActivePointerId) {
                    // This was our active pointer going up.
                    // Choose the first available pointer index.
                    int newActionIndex = associatedPointerIndex == 0 ? 1 : 0;
                    mActivePointerId = ev.getPointerId(newActionIndex);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mDiscardIntercept) {
                    break;
                }
                final int pointerIndex = getIndexForValidPointer(ev);
                if (pointerIndex == -1) {
                    break;
                }
                updateSwiping(ev);
                updateDiscardIntercept(ev, pointerIndex);
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                resetMembers();
                break;
        }
        return isIntercepting();
    }

    /**
     * Tests scroll-ability within child views of v in the direction of dx.
     *
     * @param v View to test for horizontal scroll-ability
     * @param checkSelf Whether the view v passed should itself be checked for scroll-ability
     *     (true), or just its children (false).
     * @param checkLeft Which direction to check? Left = true, right = false.
     * @param x X coordinate of the active touch point
     * @param y Y coordinate of the active touch point
     * @return true if child views of v can be scrolled by delta of dx.
     */
    private boolean canScroll(View v, boolean checkSelf, boolean checkLeft, float x, float y) {
        if (v instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) v;
            final int scrollX = v.getScrollX();
            final int scrollY = v.getScrollY();
            final int count = group.getChildCount();
            for (int i = count - 1; i >= 0; i--) {
                final View child = group.getChildAt(i);

                if (x + scrollX < child.getLeft()
                        || x + scrollX >= child.getRight()
                        || y + scrollY < child.getTop()
                        || y + scrollY >= child.getBottom()) {
                    // This child is out of bound, don't bother checking.
                    continue;
                }

                // Recursively check until finding the first scrollable or none is scrollable.
                if (canScroll(
                        /* view= */ child,
                        /* checkSelf= */ true,
                        /* checkLeft= */ checkLeft,
                        /* x= */ x + scrollX - child.getLeft(),
                        /* y= */ y + scrollY - child.getTop())) {
                    return true;
                }
            }
        }

        return checkSelf && v.canScrollHorizontally(checkLeft ? -1 : 1);
    }
}
