/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.stackdivider;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.TypedValue;
import android.window.WindowContainerTransaction;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DockedDividerUtils;
import com.android.systemui.wm.DisplayLayout;

/**
 * Handles split-screen related internal display layout. In general, this represents the
 * WM-facing understanding of the splits.
 */
public class SplitDisplayLayout {
    /** Minimum size of an adjusted stack bounds relative to original stack bounds. Used to
     * restrict IME adjustment so that a min portion of top stack remains visible.*/
    private static final float ADJUSTED_STACK_FRACTION_MIN = 0.3f;

    private static final int DIVIDER_WIDTH_INACTIVE_DP = 4;

    SplitScreenTaskOrganizer mTiles;
    DisplayLayout mDisplayLayout;
    Context mContext;

    // Lazy stuff
    boolean mResourcesValid = false;
    int mDividerSize;
    int mDividerSizeInactive;
    private DividerSnapAlgorithm mSnapAlgorithm = null;
    private DividerSnapAlgorithm mMinimizedSnapAlgorithm = null;
    Rect mPrimary = null;
    Rect mSecondary = null;
    Rect mAdjustedPrimary = null;
    Rect mAdjustedSecondary = null;

    public SplitDisplayLayout(Context ctx, DisplayLayout dl, SplitScreenTaskOrganizer taskTiles) {
        mTiles = taskTiles;
        mDisplayLayout = dl;
        mContext = ctx;
    }

    void rotateTo(int newRotation) {
        mDisplayLayout.rotateTo(mContext.getResources(), newRotation);
        final Configuration config = new Configuration();
        config.unset();
        config.orientation = mDisplayLayout.getOrientation();
        Rect tmpRect = new Rect(0, 0, mDisplayLayout.width(), mDisplayLayout.height());
        tmpRect.inset(mDisplayLayout.nonDecorInsets());
        config.windowConfiguration.setAppBounds(tmpRect);
        tmpRect.set(0, 0, mDisplayLayout.width(), mDisplayLayout.height());
        tmpRect.inset(mDisplayLayout.stableInsets());
        config.screenWidthDp = (int) (tmpRect.width() / mDisplayLayout.density());
        config.screenHeightDp = (int) (tmpRect.height() / mDisplayLayout.density());
        mContext = mContext.createConfigurationContext(config);
        mSnapAlgorithm = null;
        mMinimizedSnapAlgorithm = null;
        mResourcesValid = false;
    }

    private void updateResources() {
        if (mResourcesValid) {
            return;
        }
        mResourcesValid = true;
        Resources res = mContext.getResources();
        mDividerSize = DockedDividerUtils.getDividerSize(res,
                DockedDividerUtils.getDividerInsets(res));
        mDividerSizeInactive = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, DIVIDER_WIDTH_INACTIVE_DP, res.getDisplayMetrics());
    }

    int getPrimarySplitSide() {
        switch (mDisplayLayout.getNavigationBarPosition(mContext.getResources())) {
            case DisplayLayout.NAV_BAR_BOTTOM:
                return mDisplayLayout.isLandscape() ? DOCKED_LEFT : DOCKED_TOP;
            case DisplayLayout.NAV_BAR_LEFT:
                return DOCKED_RIGHT;
            case DisplayLayout.NAV_BAR_RIGHT:
                return DOCKED_LEFT;
            default:
                return DOCKED_INVALID;
        }
    }

    DividerSnapAlgorithm getSnapAlgorithm() {
        if (mSnapAlgorithm == null) {
            updateResources();
            boolean isHorizontalDivision = !mDisplayLayout.isLandscape();
            mSnapAlgorithm = new DividerSnapAlgorithm(mContext.getResources(),
                    mDisplayLayout.width(), mDisplayLayout.height(), mDividerSize,
                    isHorizontalDivision, mDisplayLayout.stableInsets(), getPrimarySplitSide());
        }
        return mSnapAlgorithm;
    }

    DividerSnapAlgorithm getMinimizedSnapAlgorithm(boolean homeStackResizable) {
        if (mMinimizedSnapAlgorithm == null) {
            updateResources();
            boolean isHorizontalDivision = !mDisplayLayout.isLandscape();
            mMinimizedSnapAlgorithm = new DividerSnapAlgorithm(mContext.getResources(),
                    mDisplayLayout.width(), mDisplayLayout.height(), mDividerSize,
                    isHorizontalDivision, mDisplayLayout.stableInsets(), getPrimarySplitSide(),
                    true /* isMinimized */, homeStackResizable);
        }
        return mMinimizedSnapAlgorithm;
    }

    void resizeSplits(int position) {
        mPrimary = mPrimary == null ? new Rect() : mPrimary;
        mSecondary = mSecondary == null ? new Rect() : mSecondary;
        calcSplitBounds(position, mPrimary, mSecondary);
    }

    void resizeSplits(int position, WindowContainerTransaction t) {
        resizeSplits(position);
        t.setBounds(mTiles.mPrimary.token, mPrimary);
        t.setBounds(mTiles.mSecondary.token, mSecondary);

        t.setSmallestScreenWidthDp(mTiles.mPrimary.token,
                getSmallestWidthDpForBounds(mContext, mDisplayLayout, mPrimary));
        t.setSmallestScreenWidthDp(mTiles.mSecondary.token,
                getSmallestWidthDpForBounds(mContext, mDisplayLayout, mSecondary));
    }

    void calcSplitBounds(int position, @NonNull Rect outPrimary, @NonNull Rect outSecondary) {
        int dockSide = getPrimarySplitSide();
        DockedDividerUtils.calculateBoundsForPosition(position, dockSide, outPrimary,
                mDisplayLayout.width(), mDisplayLayout.height(), mDividerSize);

        DockedDividerUtils.calculateBoundsForPosition(position,
                DockedDividerUtils.invertDockSide(dockSide), outSecondary, mDisplayLayout.width(),
                mDisplayLayout.height(), mDividerSize);
    }

    Rect calcResizableMinimizedHomeStackBounds() {
        DividerSnapAlgorithm.SnapTarget miniMid =
                getMinimizedSnapAlgorithm(true /* resizable */).getMiddleTarget();
        Rect homeBounds = new Rect();
        DockedDividerUtils.calculateBoundsForPosition(miniMid.position,
                DockedDividerUtils.invertDockSide(getPrimarySplitSide()), homeBounds,
                mDisplayLayout.width(), mDisplayLayout.height(), mDividerSize);
        return homeBounds;
    }

    /**
     * Updates the adjustment depending on it's current state.
     */
    void updateAdjustedBounds(int currImeTop, int hiddenTop, int shownTop) {
        adjustForIME(mDisplayLayout, currImeTop, hiddenTop, shownTop, mDividerSize,
                mDividerSizeInactive, mPrimary, mSecondary);
    }

    /** Assumes top/bottom split. Splits are not adjusted for left/right splits. */
    private void adjustForIME(DisplayLayout dl, int currImeTop, int hiddenTop, int shownTop,
            int dividerWidth, int dividerWidthInactive, Rect primaryBounds, Rect secondaryBounds) {
        if (mAdjustedPrimary == null) {
            mAdjustedPrimary = new Rect();
            mAdjustedSecondary = new Rect();
        }

        final Rect displayStableRect = new Rect();
        dl.getStableBounds(displayStableRect);

        final float shownFraction = ((float) (currImeTop - hiddenTop)) / (shownTop - hiddenTop);
        final int currDividerWidth =
                (int) (dividerWidthInactive * shownFraction + dividerWidth * (1.f - shownFraction));

        // Calculate the highest we can move the bottom of the top stack to keep 30% visible.
        final int minTopStackBottom = displayStableRect.top
                + (int) ((mPrimary.bottom - displayStableRect.top) * ADJUSTED_STACK_FRACTION_MIN);
        // Based on that, calculate the maximum amount we'll allow the ime to shift things.
        final int maxOffset = mPrimary.bottom - minTopStackBottom;
        // Calculate how much we would shift things without limits (basically the height of ime).
        final int desiredOffset = hiddenTop - shownTop;
        // Calculate an "adjustedTop" which is the currImeTop but restricted by our constraints.
        // We want an effect where the adjustment only occurs during the "highest" portion of the
        // ime animation. This is done by shifting the adjustment values by the difference in
        // offsets (effectively playing the whole adjustment animation some fixed amount of pixels
        // below the ime top).
        final int topCorrection = Math.max(0, desiredOffset - maxOffset);
        final int adjustedTop = currImeTop + topCorrection;
        // The actual yOffset is the distance between adjustedTop and the bottom of the display.
        // Since our adjustedTop values are playing "below" the ime, we clamp at 0 so we only
        // see adjustment upward.
        final int yOffset = Math.max(0, dl.height() - adjustedTop);

        // TOP
        // Reduce the offset by an additional small amount to squish the divider bar.
        mAdjustedPrimary.set(primaryBounds);
        mAdjustedPrimary.offset(0, -yOffset + (dividerWidth - currDividerWidth));

        // BOTTOM
        mAdjustedSecondary.set(secondaryBounds);
        mAdjustedSecondary.offset(0, -yOffset);
    }

    static int getSmallestWidthDpForBounds(@NonNull Context context, DisplayLayout dl,
            Rect bounds) {
        int dividerSize = DockedDividerUtils.getDividerSize(context.getResources(),
                DockedDividerUtils.getDividerInsets(context.getResources()));

        int minWidth = Integer.MAX_VALUE;

        // Go through all screen orientations and find the orientation in which the task has the
        // smallest width.
        Rect tmpRect = new Rect();
        Rect rotatedDisplayRect = new Rect();
        Rect displayRect = new Rect(0, 0, dl.width(), dl.height());

        DisplayLayout tmpDL = new DisplayLayout();
        for (int rotation = 0; rotation < 4; rotation++) {
            tmpDL.set(dl);
            tmpDL.rotateTo(context.getResources(), rotation);
            DividerSnapAlgorithm snap = initSnapAlgorithmForRotation(context, tmpDL, dividerSize);

            tmpRect.set(bounds);
            DisplayLayout.rotateBounds(tmpRect, displayRect, rotation - dl.rotation());
            rotatedDisplayRect.set(0, 0, tmpDL.width(), tmpDL.height());
            final int dockSide = getPrimarySplitSide(tmpRect, rotatedDisplayRect,
                    tmpDL.getOrientation());
            final int position = DockedDividerUtils.calculatePositionForBounds(tmpRect, dockSide,
                    dividerSize);

            final int snappedPosition =
                    snap.calculateNonDismissingSnapTarget(position).position;
            DockedDividerUtils.calculateBoundsForPosition(snappedPosition, dockSide, tmpRect,
                    tmpDL.width(), tmpDL.height(), dividerSize);
            Rect insettedDisplay = new Rect(rotatedDisplayRect);
            insettedDisplay.inset(tmpDL.stableInsets());
            tmpRect.intersect(insettedDisplay);
            minWidth = Math.min(tmpRect.width(), minWidth);
        }
        return (int) (minWidth / dl.density());
    }

    static DividerSnapAlgorithm initSnapAlgorithmForRotation(Context context, DisplayLayout dl,
            int dividerSize) {
        final Configuration config = new Configuration();
        config.unset();
        config.orientation = dl.getOrientation();
        Rect tmpRect = new Rect(0, 0, dl.width(), dl.height());
        tmpRect.inset(dl.nonDecorInsets());
        config.windowConfiguration.setAppBounds(tmpRect);
        tmpRect.set(0, 0, dl.width(), dl.height());
        tmpRect.inset(dl.stableInsets());
        config.screenWidthDp = (int) (tmpRect.width() / dl.density());
        config.screenHeightDp = (int) (tmpRect.height() / dl.density());
        final Context rotationContext = context.createConfigurationContext(config);
        return new DividerSnapAlgorithm(
                rotationContext.getResources(), dl.width(), dl.height(), dividerSize,
                config.orientation == ORIENTATION_PORTRAIT, dl.stableInsets());
    }

    /**
     * Get the current primary-split side. Determined by its location of {@param bounds} within
     * {@param displayRect} but if both are the same, it will try to dock to each side and determine
     * if allowed in its respected {@param orientation}.
     *
     * @param bounds bounds of the primary split task to get which side is docked
     * @param displayRect bounds of the display that contains the primary split task
     * @param orientation the origination of device
     * @return current primary-split side
     */
    static int getPrimarySplitSide(Rect bounds, Rect displayRect, int orientation) {
        if (orientation == ORIENTATION_PORTRAIT) {
            // Portrait mode, docked either at the top or the bottom.
            final int diff = (displayRect.bottom - bounds.bottom) - (bounds.top - displayRect.top);
            if (diff < 0) {
                return DOCKED_BOTTOM;
            } else {
                // Top is default
                return DOCKED_TOP;
            }
        } else if (orientation == ORIENTATION_LANDSCAPE) {
            // Landscape mode, docked either on the left or on the right.
            final int diff = (displayRect.right - bounds.right) - (bounds.left - displayRect.left);
            if (diff < 0) {
                return DOCKED_RIGHT;
            }
            return DOCKED_LEFT;
        }
        return DOCKED_INVALID;
    }
}
