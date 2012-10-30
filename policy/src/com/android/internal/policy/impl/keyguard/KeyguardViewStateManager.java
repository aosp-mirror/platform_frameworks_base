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

import android.os.Handler;
import android.os.Looper;
import android.view.View;

public class KeyguardViewStateManager implements SlidingChallengeLayout.OnChallengeScrolledListener {

    private KeyguardWidgetPager mPagedView;
    private ChallengeLayout mChallengeLayout;
    private Runnable mHideHintsRunnable;
    private KeyguardSecurityView mKeyguardSecurityContainer;
    private int[] mTmpPoint = new int[2];
    private static final int SCREEN_ON_HINT_DURATION = 1000;
    private static final int SCREEN_ON_RING_HINT_DELAY = 300;
    Handler mMainQueue = new Handler(Looper.myLooper());

    int mLastScrollState = SlidingChallengeLayout.SCROLL_STATE_IDLE;

    // Paged view state
    private int mPageListeningToSlider = -1;
    private int mCurrentPage = -1;

    int mChallengeTop = 0;

    public KeyguardViewStateManager() {
    }

    public void setPagedView(KeyguardWidgetPager pagedView) {
        mPagedView = pagedView;
    }

    public void setChallengeLayout(ChallengeLayout layout) {
        mChallengeLayout = layout;
    }

    public void setSecurityViewContainer(KeyguardSecurityView container) {
        mKeyguardSecurityContainer = container;
    }

    public void onPageBeginMoving() {
        if (mChallengeLayout.isChallengeShowing()) {
            mChallengeLayout.showChallenge(false);
        }
        if (mHideHintsRunnable != null) {
            mMainQueue.removeCallbacks(mHideHintsRunnable);
            mHideHintsRunnable = null;
        }
    }

    public void onPageEndMoving() {
    }

    public void showBouncer(boolean show) {
        mChallengeLayout.showBouncer();
    }

    public void onPageSwitch(View newPage, int newPageIndex) {
        // Reset the previous page size and ensure the current page is sized appropriately.
        // We only modify the page state if it is not currently under control by the slider.
        // This prevents conflicts.
        if (mPagedView != null && mChallengeLayout != null) {
            KeyguardWidgetFrame prevPage = mPagedView.getWidgetPageAt(mCurrentPage);
            if (prevPage != null && mCurrentPage != mPageListeningToSlider) {
                prevPage.resetSize();
            }

            KeyguardWidgetFrame newCurPage = mPagedView.getWidgetPageAt(newPageIndex);
            boolean challengeOverlapping = mChallengeLayout.isChallengeOverlapping();
            if (challengeOverlapping && !newCurPage.isSmall()
                    && mPageListeningToSlider != newPageIndex) {
                shrinkWidget(newCurPage);
            }
        }
        mCurrentPage = newPageIndex;
    }

    private int getChallengeTopRelativeToFrame(KeyguardWidgetFrame frame, int top) {
        mTmpPoint[0] = 0;
        mTmpPoint[1] = top;
        mapPoint((View) mChallengeLayout, frame, mTmpPoint);
        return mTmpPoint[1];
    }

    private void shrinkWidget(KeyguardWidgetFrame frame) {
        if (frame != null && mChallengeLayout != null &&
                mChallengeLayout instanceof SlidingChallengeLayout) {
            SlidingChallengeLayout scl = (SlidingChallengeLayout) mChallengeLayout;
            int top = scl.getMaxChallengeTop();
            frame.shrinkWidget(getChallengeTopRelativeToFrame(frame, top));
        }
    }

    /**
     * Simple method to map a point from one view's coordinates to another's. Note: this method
     * doesn't account for transforms, so if the views will be transformed, this should not be used.
     *
     * @param fromView The view to which the point is relative
     * @param toView The view into which the point should be mapped
     * @param pt The point
     */
    public void mapPoint(View fromView, View toView, int pt[]) {
        int[] loc = new int[2];
        fromView.getLocationInWindow(loc);
        int x = loc[0];
        int y = loc[1];

        toView.getLocationInWindow(loc);
        int vX = loc[0];
        int vY = loc[1];

        pt[0] += x - vX;
        pt[1] += y - vY;
    }

    @Override
    public void onScrollStateChanged(int scrollState) {
        if (mPagedView == null || mChallengeLayout == null) return;
        boolean challengeOverlapping = mChallengeLayout.isChallengeOverlapping();

        if (scrollState == SlidingChallengeLayout.SCROLL_STATE_IDLE) {
            KeyguardWidgetFrame frame = mPagedView.getWidgetPageAt(mPageListeningToSlider);
            if (frame == null) return;

            if (!challengeOverlapping) {
                frame.resetSize();
            }
            frame.hideFrame(this);

            if (challengeOverlapping) {
                mPagedView.setOnlyAllowEdgeSwipes(true);
            } else {
                mPagedView.setOnlyAllowEdgeSwipes(false);
            }

            if (mChallengeLayout.isChallengeShowing()) {
                mKeyguardSecurityContainer.onResume();
            } else {
                mKeyguardSecurityContainer.onPause();
            }
            mPageListeningToSlider = -1;
        } else if (mLastScrollState == SlidingChallengeLayout.SCROLL_STATE_IDLE) {
            // Whether dragging or settling, if the last state was idle, we use this signal
            // to update the current page who will receive events from the sliding challenge.
            // We resize the frame as appropriate.
            mPageListeningToSlider = mPagedView.getNextPage();
            KeyguardWidgetFrame frame = mPagedView.getWidgetPageAt(mPageListeningToSlider);
            if (frame == null) return;

            frame.showFrame(this);

            // As soon as the security begins sliding, the widget becomes small (if it wasn't
            // small to begin with).
            if (!frame.isSmall()) {
                // We need to fetch the final page, in case the pages are in motion.
                mPageListeningToSlider = mPagedView.getNextPage();
                System.out.println("Shrink widget from scroll state changed!");
                shrinkWidget(frame);
            }
            // View is on the move.  Pause the security view until it completes.
            mKeyguardSecurityContainer.onPause();
        }
        mLastScrollState = scrollState;
    }

    @Override
    public void onScrollPositionChanged(float scrollPosition, int challengeTop) {
        mChallengeTop = challengeTop;
        KeyguardWidgetFrame frame = mPagedView.getWidgetPageAt(mPageListeningToSlider);
        if (frame != null) {
            frame.adjustFrame(getChallengeTopRelativeToFrame(frame, mChallengeTop));
        }
    }

    public void showUsabilityHints() {
        mMainQueue.postDelayed( new Runnable() {
            @Override
            public void run() {
                mKeyguardSecurityContainer.showUsabilityHint();
            }
        } , SCREEN_ON_RING_HINT_DELAY);
        mPagedView.showInitialPageHints();
        mHideHintsRunnable = new Runnable() {
            @Override
            public void run() {
                mPagedView.hideOutlinesAndSidePages();
                mHideHintsRunnable = null;
            }
        };

        mMainQueue.postDelayed(mHideHintsRunnable, SCREEN_ON_HINT_DURATION);
    }
}
