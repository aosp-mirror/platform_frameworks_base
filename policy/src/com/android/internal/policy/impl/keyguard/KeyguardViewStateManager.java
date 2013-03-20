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

public class KeyguardViewStateManager implements
        SlidingChallengeLayout.OnChallengeScrolledListener,
        ChallengeLayout.OnBouncerStateChangedListener {

    private KeyguardWidgetPager mKeyguardWidgetPager;
    private ChallengeLayout mChallengeLayout;
    private KeyguardHostView mKeyguardHostView;
    private int[] mTmpPoint = new int[2];
    private int[] mTmpLoc = new int[2];

    private KeyguardSecurityView mKeyguardSecurityContainer;
    private static final int SCREEN_ON_HINT_DURATION = 1000;
    private static final int SCREEN_ON_RING_HINT_DELAY = 300;
    Handler mMainQueue = new Handler(Looper.myLooper());

    int mLastScrollState = SlidingChallengeLayout.SCROLL_STATE_IDLE;

    // Paged view state
    private int mPageListeningToSlider = -1;
    private int mCurrentPage = -1;
    private int mPageIndexOnPageBeginMoving = -1;

    int mChallengeTop = 0;

    public KeyguardViewStateManager(KeyguardHostView hostView) {
        mKeyguardHostView = hostView;
    }

    public void setPagedView(KeyguardWidgetPager pagedView) {
        mKeyguardWidgetPager = pagedView;
        updateEdgeSwiping();
    }

    public void setChallengeLayout(ChallengeLayout layout) {
        mChallengeLayout = layout;
        updateEdgeSwiping();
    }

    private void updateEdgeSwiping() {
        if (mChallengeLayout != null && mKeyguardWidgetPager != null) {
            if (mChallengeLayout.isChallengeOverlapping()) {
                mKeyguardWidgetPager.setOnlyAllowEdgeSwipes(true);
            } else {
                mKeyguardWidgetPager.setOnlyAllowEdgeSwipes(false);
            }
        }
    }

    public boolean isChallengeShowing() {
        if (mChallengeLayout != null) {
            return mChallengeLayout.isChallengeShowing();
        }
        return false;
    }

    public boolean isChallengeOverlapping() {
        if (mChallengeLayout != null) {
            return mChallengeLayout.isChallengeOverlapping();
        }
        return false;
    }

    public void setSecurityViewContainer(KeyguardSecurityView container) {
        mKeyguardSecurityContainer = container;
    }

    public void showBouncer(boolean show) {
        mChallengeLayout.showBouncer();
    }

    public boolean isBouncing() {
        return mChallengeLayout.isBouncing();
    }

    public void fadeOutSecurity(int duration) {
        ((View) mKeyguardSecurityContainer).animate().alpha(0).setDuration(duration);
    }

    public void fadeInSecurity(int duration) {
        ((View) mKeyguardSecurityContainer).animate().alpha(1f).setDuration(duration);
    }

    public void onPageBeginMoving() {
        if (mChallengeLayout.isChallengeOverlapping() &&
                mChallengeLayout instanceof SlidingChallengeLayout) {
            SlidingChallengeLayout scl = (SlidingChallengeLayout) mChallengeLayout;
            scl.fadeOutChallenge();
            mPageIndexOnPageBeginMoving = mKeyguardWidgetPager.getCurrentPage();
        }
        // We use mAppWidgetToShow to show a particular widget after you add it--
        // once the user swipes a page we clear that behavior
        if (mKeyguardHostView != null) {
            mKeyguardHostView.clearAppWidgetToShow();
            mKeyguardHostView.setOnDismissAction(null);
        }
        if (mHideHintsRunnable != null) {
            mMainQueue.removeCallbacks(mHideHintsRunnable);
            mHideHintsRunnable = null;
        }
    }

    public void onPageEndMoving() {
        mPageIndexOnPageBeginMoving = -1;
    }

    public void onPageSwitching(View newPage, int newPageIndex) {
        if (mKeyguardWidgetPager != null && mChallengeLayout instanceof SlidingChallengeLayout) {
            boolean isCameraPage = newPage instanceof CameraWidgetFrame;
            ((SlidingChallengeLayout) mChallengeLayout).setChallengeInteractive(!isCameraPage);
        }

        // If the page we're settling to is the same as we started on, and the action of
        // moving the page hid the security, we restore it immediately.
        if (mPageIndexOnPageBeginMoving == mKeyguardWidgetPager.getNextPage() &&
                mChallengeLayout instanceof SlidingChallengeLayout) {
            SlidingChallengeLayout scl = (SlidingChallengeLayout) mChallengeLayout;
            scl.fadeInChallenge();
            mKeyguardWidgetPager.setWidgetToResetOnPageFadeOut(-1);
        }
        mPageIndexOnPageBeginMoving = -1;
    }

    public void onPageSwitched(View newPage, int newPageIndex) {
        // Reset the previous page size and ensure the current page is sized appropriately.
        // We only modify the page state if it is not currently under control by the slider.
        // This prevents conflicts.

        // If the page hasn't switched, don't bother with any of this
        if (mCurrentPage == newPageIndex) return;

        if (mKeyguardWidgetPager != null && mChallengeLayout != null) {
            KeyguardWidgetFrame prevPage = mKeyguardWidgetPager.getWidgetPageAt(mCurrentPage);
            if (prevPage != null && mCurrentPage != mPageListeningToSlider && mCurrentPage
                    != mKeyguardWidgetPager.getWidgetToResetOnPageFadeOut()) {
                prevPage.resetSize();
            }

            KeyguardWidgetFrame newCurPage = mKeyguardWidgetPager.getWidgetPageAt(newPageIndex);
            boolean challengeOverlapping = mChallengeLayout.isChallengeOverlapping();
            if (challengeOverlapping && !newCurPage.isSmall()
                    && mPageListeningToSlider != newPageIndex) {
                newCurPage.shrinkWidget();
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

    /**
     * Simple method to map a point from one view's coordinates to another's. Note: this method
     * doesn't account for transforms, so if the views will be transformed, this should not be used.
     *
     * @param fromView The view to which the point is relative
     * @param toView The view into which the point should be mapped
     * @param pt The point
     */
    private void mapPoint(View fromView, View toView, int pt[]) {
        fromView.getLocationInWindow(mTmpLoc);

        int x = mTmpLoc[0];
        int y = mTmpLoc[1];

        toView.getLocationInWindow(mTmpLoc);
        int vX = mTmpLoc[0];
        int vY = mTmpLoc[1];

        pt[0] += x - vX;
        pt[1] += y - vY;
    }

    private void userActivity() {
        if (mKeyguardHostView != null) {
            mKeyguardHostView.onUserActivityTimeoutChanged();
            mKeyguardHostView.userActivity();
        }
    }

    @Override
    public void onScrollStateChanged(int scrollState) {
        if (mKeyguardWidgetPager == null || mChallengeLayout == null) return;

        boolean challengeOverlapping = mChallengeLayout.isChallengeOverlapping();

        if (scrollState == SlidingChallengeLayout.SCROLL_STATE_IDLE) {
            KeyguardWidgetFrame frame = mKeyguardWidgetPager.getWidgetPageAt(mPageListeningToSlider);
            if (frame == null) return;

            if (!challengeOverlapping) {
                if (!mKeyguardWidgetPager.isPageMoving()) {
                    frame.resetSize();
                    userActivity();
                } else {
                    mKeyguardWidgetPager.setWidgetToResetOnPageFadeOut(mPageListeningToSlider);
                }
            }
            if (frame.isSmall()) {
                // This is to make sure that if the scroller animation gets cut off midway
                // that the frame doesn't stay in a partial down position.
                frame.setFrameHeight(frame.getSmallFrameHeight());
            }
            if (scrollState != SlidingChallengeLayout.SCROLL_STATE_FADING) {
                frame.hideFrame(this);
            }
            updateEdgeSwiping();

            if (mChallengeLayout.isChallengeShowing()) {
                mKeyguardSecurityContainer.onResume(KeyguardSecurityView.VIEW_REVEALED);
            } else {
                mKeyguardSecurityContainer.onPause();
            }
            mPageListeningToSlider = -1;
        } else if (mLastScrollState == SlidingChallengeLayout.SCROLL_STATE_IDLE) {
            // Whether dragging or settling, if the last state was idle, we use this signal
            // to update the current page who will receive events from the sliding challenge.
            // We resize the frame as appropriate.
            mPageListeningToSlider = mKeyguardWidgetPager.getNextPage();
            KeyguardWidgetFrame frame = mKeyguardWidgetPager.getWidgetPageAt(mPageListeningToSlider);
            if (frame == null) return;

            // Skip showing the frame and shrinking the widget if we are
            if (!mChallengeLayout.isBouncing()) {
                if (scrollState != SlidingChallengeLayout.SCROLL_STATE_FADING) {
                    frame.showFrame(this);
                }

                // As soon as the security begins sliding, the widget becomes small (if it wasn't
                // small to begin with).
                if (!frame.isSmall()) {
                    // We need to fetch the final page, in case the pages are in motion.
                    mPageListeningToSlider = mKeyguardWidgetPager.getNextPage();
                    frame.shrinkWidget(false);
                }
            } else {
                if (!frame.isSmall()) {
                    // We need to fetch the final page, in case the pages are in motion.
                    mPageListeningToSlider = mKeyguardWidgetPager.getNextPage();
                }
            }

            // View is on the move.  Pause the security view until it completes.
            mKeyguardSecurityContainer.onPause();
        }
        mLastScrollState = scrollState;
    }

    @Override
    public void onScrollPositionChanged(float scrollPosition, int challengeTop) {
        mChallengeTop = challengeTop;
        KeyguardWidgetFrame frame = mKeyguardWidgetPager.getWidgetPageAt(mPageListeningToSlider);
        if (frame != null && mLastScrollState != SlidingChallengeLayout.SCROLL_STATE_FADING) {
            frame.adjustFrame(getChallengeTopRelativeToFrame(frame, mChallengeTop));
        }
    }

    private Runnable mHideHintsRunnable = new Runnable() {
        @Override
        public void run() {
            if (mKeyguardWidgetPager != null) {
                mKeyguardWidgetPager.hideOutlinesAndSidePages();
            }
        }
    };

    public void showUsabilityHints() {
        mMainQueue.postDelayed( new Runnable() {
            @Override
            public void run() {
                mKeyguardSecurityContainer.showUsabilityHint();
            }
        } , SCREEN_ON_RING_HINT_DELAY);
        mKeyguardWidgetPager.showInitialPageHints();
        if (mHideHintsRunnable != null) {
            mMainQueue.postDelayed(mHideHintsRunnable, SCREEN_ON_HINT_DURATION);
        }
    }

    // ChallengeLayout.OnBouncerStateChangedListener
    @Override
    public void onBouncerStateChanged(boolean bouncerActive) {
        if (bouncerActive) {
            mKeyguardWidgetPager.zoomOutToBouncer();
        } else {
            mKeyguardWidgetPager.zoomInFromBouncer();
            if (mKeyguardHostView != null) {
                mKeyguardHostView.setOnDismissAction(null);
            }
        }
    }
}
