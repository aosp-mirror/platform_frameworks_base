package com.android.internal.policy.impl.keyguard;

import android.view.View;

public class KeyguardViewStateManager implements SlidingChallengeLayout.OnChallengeScrolledListener {

    private KeyguardWidgetPager mPagedView;
    private int mCurrentPageIndex;
    private SlidingChallengeLayout mSlidingChallengeLayout;
    private int[] mTmpPoint = new int[2];

    int mChallengeTop = 0;

    public KeyguardViewStateManager() {
    }

    public void setPagedView(KeyguardWidgetPager pagedView) {
        mPagedView = pagedView;
    }

    public void setSlidingChallenge(SlidingChallengeLayout layout) {
        mSlidingChallengeLayout = layout;
    }

    public void onPageBeginMoving() {
        if (mSlidingChallengeLayout.isChallengeShowing()) {
            mSlidingChallengeLayout.showChallenge(false);
        }
    }

    public void onPageEndMoving() {
    }

    public void onPageSwitch(View newPage, int newPageIndex) {
        // Reset the previous page size and ensure the current page is sized appropriately
        if (mPagedView != null) {
            KeyguardWidgetFrame oldPage = mPagedView.getWidgetPageAt(mCurrentPageIndex);
            // Reset the old widget page to full size
            if (oldPage != null) {
                oldPage.resetSize();
            }

            KeyguardWidgetFrame newCurPage = mPagedView.getWidgetPageAt(newPageIndex);
            if (mSlidingChallengeLayout.isChallengeShowing()) {
                sizeWidgetFrameToChallengeTop(newCurPage);
            }
        }
        mCurrentPageIndex = newPageIndex;
    }

    private void sizeWidgetFrameToChallengeTop(KeyguardWidgetFrame frame) {
        if (frame == null) return;
        mTmpPoint[0] = 0;
        mTmpPoint[1] = mChallengeTop;
        mapPoint(mSlidingChallengeLayout, frame, mTmpPoint);
        frame.setChallengeTop(mTmpPoint[1]);
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
        if (scrollState == SlidingChallengeLayout.SCROLL_STATE_IDLE) {
            if (mPagedView == null) return;

            boolean challengeShowing = mSlidingChallengeLayout.isChallengeShowing();
            int curPage = mPagedView.getCurrentPage();
            KeyguardWidgetFrame frame = mPagedView.getWidgetPageAt(curPage);

            if (frame != null) {
                if (!challengeShowing) {
                    frame.resetSize();
                } else {
                    sizeWidgetFrameToChallengeTop(frame);
                }
            }

            if (challengeShowing) {
                mPagedView.setOnlyAllowEdgeSwipes(true);
            } else {
                mPagedView.setOnlyAllowEdgeSwipes(false);
            }

        }
    }

    @Override
    public void onScrollPositionChanged(float scrollPosition, int challengeTop) {
        mChallengeTop = challengeTop;
    }

}
