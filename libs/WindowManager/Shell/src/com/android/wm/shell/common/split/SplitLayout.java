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

package com.android.wm.shell.common.split;

import static android.content.res.Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
import static android.content.res.Configuration.SCREEN_WIDTH_DP_UNDEFINED;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;
import static android.view.WindowManagerPolicyConstants.SPLIT_DIVIDER_LAYER;

import static com.android.internal.policy.DividerSnapAlgorithm.SnapTarget.FLAG_DISMISS_END;
import static com.android.internal.policy.DividerSnapAlgorithm.SnapTarget.FLAG_DISMISS_START;
import static com.android.wm.shell.animation.Interpolators.DIM_INTERPOLATOR;
import static com.android.wm.shell.animation.Interpolators.SLOWDOWN_INTERPOLATOR;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DockedDividerUtils;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;

/**
 * Records and handles layout of splits. Helps to calculate proper bounds when configuration or
 * divide position changes.
 */
public final class SplitLayout implements DisplayInsetsController.OnInsetsChangedListener {
    /**
     * Split position isn't specified normally meaning to use what ever it is currently set to.
     */
    public static final int SPLIT_POSITION_UNDEFINED = -1;

    /**
     * Specifies that a split is positioned at the top half of the screen if
     * in portrait mode or at the left half of the screen if in landscape mode.
     */
    public static final int SPLIT_POSITION_TOP_OR_LEFT = 0;

    /**
     * Specifies that a split is positioned at the bottom half of the screen if
     * in portrait mode or at the right half of the screen if in landscape mode.
     */
    public static final int SPLIT_POSITION_BOTTOM_OR_RIGHT = 1;

    @IntDef(prefix = {"SPLIT_POSITION_"}, value = {
            SPLIT_POSITION_UNDEFINED,
            SPLIT_POSITION_TOP_OR_LEFT,
            SPLIT_POSITION_BOTTOM_OR_RIGHT
    })
    public @interface SplitPosition {
    }

    private final int mDividerWindowWidth;
    private final int mDividerInsets;
    private final int mDividerSize;

    private final Rect mTempRect = new Rect();
    private final Rect mRootBounds = new Rect();
    private final Rect mDividerBounds = new Rect();
    private final Rect mBounds1 = new Rect();
    private final Rect mBounds2 = new Rect();
    private final Rect mWinBounds1 = new Rect();
    private final Rect mWinBounds2 = new Rect();
    private final SplitLayoutHandler mSplitLayoutHandler;
    private final SplitWindowManager mSplitWindowManager;
    private final DisplayImeController mDisplayImeController;
    private final ImePositionProcessor mImePositionProcessor;
    private final DismissingParallaxPolicy mDismissingParallaxPolicy;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final InsetsState mInsetsState = new InsetsState();

    private Context mContext;
    private DividerSnapAlgorithm mDividerSnapAlgorithm;
    private WindowContainerToken mWinToken1;
    private WindowContainerToken mWinToken2;
    private int mDividePosition;
    private boolean mInitialized = false;
    private int mOrientation;
    private int mRotation;

    public SplitLayout(String windowName, Context context, Configuration configuration,
            SplitLayoutHandler splitLayoutHandler,
            SplitWindowManager.ParentContainerCallbacks parentContainerCallbacks,
            DisplayImeController displayImeController, ShellTaskOrganizer taskOrganizer) {
        mContext = context.createConfigurationContext(configuration);
        mOrientation = configuration.orientation;
        mRotation = configuration.windowConfiguration.getRotation();
        mSplitLayoutHandler = splitLayoutHandler;
        mDisplayImeController = displayImeController;
        mSplitWindowManager = new SplitWindowManager(windowName, mContext, configuration,
                parentContainerCallbacks);
        mTaskOrganizer = taskOrganizer;
        mImePositionProcessor = new ImePositionProcessor(mContext.getDisplayId());
        mDismissingParallaxPolicy = new DismissingParallaxPolicy();

        final Resources resources = context.getResources();
        mDividerWindowWidth = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        mDividerInsets = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);
        mDividerSize = mDividerWindowWidth - mDividerInsets * 2;

        mRootBounds.set(configuration.windowConfiguration.getBounds());
        mDividerSnapAlgorithm = getSnapAlgorithm(mContext, mRootBounds);
        resetDividerPosition();
    }

    /** Gets bounds of the primary split. */
    public Rect getBounds1() {
        return new Rect(mBounds1);
    }

    /** Gets bounds of the secondary split. */
    public Rect getBounds2() {
        return new Rect(mBounds2);
    }

    /** Gets bounds of divider window. */
    public Rect getDividerBounds() {
        return new Rect(mDividerBounds);
    }

    /** Returns leash of the current divider bar. */
    @Nullable
    public SurfaceControl getDividerLeash() {
        return mSplitWindowManager == null ? null : mSplitWindowManager.getSurfaceControl();
    }

    int getDividePosition() {
        return mDividePosition;
    }

    /**
     * Returns the divider position as a fraction from 0 to 1.
     */
    public float getDividerPositionAsFraction() {
        return Math.min(1f, Math.max(0f, isLandscape()
                ? (float) ((mBounds1.right + mBounds2.left) / 2f) / mBounds2.right
                : (float) ((mBounds1.bottom + mBounds2.top) / 2f) / mBounds2.bottom));
    }

    /** Applies new configuration, returns {@code false} if there's no effect to the layout. */
    public boolean updateConfiguration(Configuration configuration) {
        boolean affectsLayout = false;

        // Update the split bounds when necessary. Besides root bounds changed, split bounds need to
        // be updated when the rotation changed to cover the case that users rotated the screen 180
        // degrees.
        // Make sure to render the divider bar with proper resources that matching the screen
        // orientation.
        final int rotation = configuration.windowConfiguration.getRotation();
        final Rect rootBounds = configuration.windowConfiguration.getBounds();
        final int orientation = configuration.orientation;
        if (rotation != mRotation || !mRootBounds.equals(rootBounds)
                || orientation != mOrientation) {
            mContext = mContext.createConfigurationContext(configuration);
            mSplitWindowManager.setConfiguration(configuration);
            mOrientation = orientation;
            mTempRect.set(mRootBounds);
            mRootBounds.set(rootBounds);
            mDividerSnapAlgorithm = getSnapAlgorithm(mContext, mRootBounds);
            initDividerPosition(mTempRect);
            affectsLayout = true;
        }

        if (mInitialized) {
            release();
            init();
        }

        return affectsLayout;
    }

    private void initDividerPosition(Rect oldBounds) {
        final float snapRatio = (float) mDividePosition
                / (float) (isLandscape(oldBounds) ? oldBounds.width() : oldBounds.height());
        // Estimate position by previous ratio.
        final float length =
                (float) (isLandscape() ? mRootBounds.width() : mRootBounds.height());
        final int estimatePosition = (int) (length * snapRatio);
        // Init divider position by estimated position using current bounds snap algorithm.
        mDividePosition = mDividerSnapAlgorithm.calculateNonDismissingSnapTarget(
                estimatePosition).position;
        updateBounds(mDividePosition);
    }

    /** Updates recording bounds of divider window and both of the splits. */
    private void updateBounds(int position) {
        mDividerBounds.set(mRootBounds);
        mBounds1.set(mRootBounds);
        mBounds2.set(mRootBounds);
        final boolean isLandscape = isLandscape(mRootBounds);
        if (isLandscape) {
            position += mRootBounds.left;
            mDividerBounds.left = position - mDividerInsets;
            mDividerBounds.right = mDividerBounds.left + mDividerWindowWidth;
            mBounds1.right = position;
            mBounds2.left = mBounds1.right + mDividerSize;
        } else {
            position += mRootBounds.top;
            mDividerBounds.top = position - mDividerInsets;
            mDividerBounds.bottom = mDividerBounds.top + mDividerWindowWidth;
            mBounds1.bottom = position;
            mBounds2.top = mBounds1.bottom + mDividerSize;
        }
        DockedDividerUtils.sanitizeStackBounds(mBounds1, true /** topLeft */);
        DockedDividerUtils.sanitizeStackBounds(mBounds2, false /** topLeft */);
        mDismissingParallaxPolicy.applyDividerPosition(position, isLandscape);
    }

    /** Inflates {@link DividerView} on the root surface. */
    public void init() {
        if (mInitialized) return;
        mInitialized = true;
        mSplitWindowManager.init(this, mInsetsState);
        mDisplayImeController.addPositionProcessor(mImePositionProcessor);
    }

    /** Releases the surface holding the current {@link DividerView}. */
    public void release() {
        if (!mInitialized) return;
        mInitialized = false;
        mSplitWindowManager.release();
        mDisplayImeController.removePositionProcessor(mImePositionProcessor);
        mImePositionProcessor.reset();
    }

    @Override
    public void insetsChanged(InsetsState insetsState) {
        mInsetsState.set(insetsState);
        if (!mInitialized) {
            return;
        }
        mSplitWindowManager.onInsetsChanged(insetsState);
    }

    @Override
    public void insetsControlChanged(InsetsState insetsState,
            InsetsSourceControl[] activeControls) {
        if (!mInsetsState.equals(insetsState)) {
            insetsChanged(insetsState);
        }
    }

    /**
     * Updates bounds with the passing position. Usually used to update recording bounds while
     * performing animation or dragging divider bar to resize the splits.
     */
    void updateDivideBounds(int position) {
        updateBounds(position);
        mSplitWindowManager.setResizingSplits(true);
        mSplitLayoutHandler.onLayoutChanging(this);
    }

    void setDividePosition(int position) {
        mDividePosition = position;
        updateBounds(mDividePosition);
        mSplitLayoutHandler.onLayoutChanged(this);
        mSplitWindowManager.setResizingSplits(false);
    }

    /** Resets divider position. */
    public void resetDividerPosition() {
        mDividePosition = mDividerSnapAlgorithm.getMiddleTarget().position;
        mSplitWindowManager.setResizingSplits(false);
        updateBounds(mDividePosition);
        mWinToken1 = null;
        mWinToken2 = null;
        mWinBounds1.setEmpty();
        mWinBounds2.setEmpty();
    }

    /**
     * Sets new divide position and updates bounds correspondingly. Notifies listener if the new
     * target indicates dismissing split.
     */
    public void snapToTarget(int currentPosition, DividerSnapAlgorithm.SnapTarget snapTarget) {
        switch (snapTarget.flag) {
            case FLAG_DISMISS_START:
                flingDividePosition(currentPosition, snapTarget.position,
                        () -> mSplitLayoutHandler.onSnappedToDismiss(false /* bottomOrRight */));
                break;
            case FLAG_DISMISS_END:
                flingDividePosition(currentPosition, snapTarget.position,
                        () -> mSplitLayoutHandler.onSnappedToDismiss(true /* bottomOrRight */));
                break;
            default:
                flingDividePosition(currentPosition, snapTarget.position, null);
                break;
        }
    }

    void onDoubleTappedDivider() {
        mSplitLayoutHandler.onDoubleTappedDivider();
    }

    /**
     * Returns {@link DividerSnapAlgorithm.SnapTarget} which matches passing position and velocity.
     * If hardDismiss is set to {@code true}, it will be harder to reach dismiss target.
     */
    public DividerSnapAlgorithm.SnapTarget findSnapTarget(int position, float velocity,
            boolean hardDismiss) {
        return mDividerSnapAlgorithm.calculateSnapTarget(position, velocity, hardDismiss);
    }

    private DividerSnapAlgorithm getSnapAlgorithm(Context context, Rect rootBounds) {
        final boolean isLandscape = isLandscape(rootBounds);
        return new DividerSnapAlgorithm(
                context.getResources(),
                rootBounds.width(),
                rootBounds.height(),
                mDividerSize,
                !isLandscape,
                getDisplayInsets(context),
                isLandscape ? DOCKED_LEFT : DOCKED_TOP /* dockSide */);
    }

    @VisibleForTesting
    void flingDividePosition(int from, int to, @Nullable Runnable flingFinishedCallback) {
        if (from == to) {
            // No animation run, it should stop resizing here.
            mSplitWindowManager.setResizingSplits(false);
            return;
        }
        ValueAnimator animator = ValueAnimator
                .ofInt(from, to)
                .setDuration(250);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.addUpdateListener(
                animation -> updateDivideBounds((int) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setDividePosition(to);
                if (flingFinishedCallback != null) {
                    flingFinishedCallback.run();
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                setDividePosition(to);
            }
        });
        animator.start();
    }

    private static Rect getDisplayInsets(Context context) {
        return context.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics()
                .getWindowInsets()
                .getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout())
                .toRect();
    }

    private static boolean isLandscape(Rect bounds) {
        return bounds.width() > bounds.height();
    }

    /**
     * Return if this layout is landscape.
     */
    public boolean isLandscape() {
        return isLandscape(mRootBounds);
    }

    /** Apply recorded surface layout to the {@link SurfaceControl.Transaction}. */
    public void applySurfaceChanges(SurfaceControl.Transaction t, SurfaceControl leash1,
            SurfaceControl leash2, SurfaceControl dimLayer1, SurfaceControl dimLayer2) {
        final SurfaceControl dividerLeash = getDividerLeash();
        if (dividerLeash != null) {
            t.setPosition(dividerLeash, mDividerBounds.left, mDividerBounds.top);
            // Resets layer of divider bar to make sure it is always on top.
            t.setLayer(dividerLeash, SPLIT_DIVIDER_LAYER);
        }
        t.setPosition(leash1, mBounds1.left, mBounds1.top)
                .setWindowCrop(leash1, mBounds1.width(), mBounds1.height());
        t.setPosition(leash2, mBounds2.left, mBounds2.top)
                .setWindowCrop(leash2, mBounds2.width(), mBounds2.height());

        if (mImePositionProcessor.adjustSurfaceLayoutForIme(
                t, dividerLeash, leash1, leash2, dimLayer1, dimLayer2)) {
            return;
        }

        mDismissingParallaxPolicy.adjustDismissingSurface(t, leash1, leash2, dimLayer1, dimLayer2);
    }

    /** Apply recorded task layout to the {@link WindowContainerTransaction}. */
    public void applyTaskChanges(WindowContainerTransaction wct,
            ActivityManager.RunningTaskInfo task1, ActivityManager.RunningTaskInfo task2) {
        if (mImePositionProcessor.applyTaskLayoutForIme(wct, task1.token, task2.token)) {
            return;
        }

        if (!mBounds1.equals(mWinBounds1) || !task1.token.equals(mWinToken1)) {
            wct.setBounds(task1.token, mBounds1);
            mWinBounds1.set(mBounds1);
            mWinToken1 = task1.token;
        }
        if (!mBounds2.equals(mWinBounds2) || !task2.token.equals(mWinToken2)) {
            wct.setBounds(task2.token, mBounds2);
            mWinBounds2.set(mBounds2);
            mWinToken2 = task2.token;
        }
    }

    /**
     * Shift configuration bounds to prevent client apps get configuration changed or relaunch. And
     * restore shifted configuration bounds if it's no longer shifted.
     */
    public void applyLayoutShifted(WindowContainerTransaction wct, int offsetX, int offsetY,
            ActivityManager.RunningTaskInfo taskInfo1, ActivityManager.RunningTaskInfo taskInfo2) {
        if (offsetX == 0 && offsetY == 0) {
            wct.setBounds(taskInfo1.token, mBounds1);
            wct.setAppBounds(taskInfo1.token, null);
            wct.setScreenSizeDp(taskInfo1.token,
                    SCREEN_WIDTH_DP_UNDEFINED, SCREEN_HEIGHT_DP_UNDEFINED);

            wct.setBounds(taskInfo2.token, mBounds2);
            wct.setAppBounds(taskInfo2.token, null);
            wct.setScreenSizeDp(taskInfo2.token,
                    SCREEN_WIDTH_DP_UNDEFINED, SCREEN_HEIGHT_DP_UNDEFINED);
        } else {
            mTempRect.set(taskInfo1.configuration.windowConfiguration.getBounds());
            mTempRect.offset(offsetX, offsetY);
            wct.setBounds(taskInfo1.token, mTempRect);
            mTempRect.set(taskInfo1.configuration.windowConfiguration.getAppBounds());
            mTempRect.offset(offsetX, offsetY);
            wct.setAppBounds(taskInfo1.token, mTempRect);
            wct.setScreenSizeDp(taskInfo1.token,
                    taskInfo1.configuration.screenWidthDp,
                    taskInfo1.configuration.screenHeightDp);

            mTempRect.set(taskInfo2.configuration.windowConfiguration.getBounds());
            mTempRect.offset(offsetX, offsetY);
            wct.setBounds(taskInfo2.token, mTempRect);
            mTempRect.set(taskInfo2.configuration.windowConfiguration.getAppBounds());
            mTempRect.offset(offsetX, offsetY);
            wct.setAppBounds(taskInfo2.token, mTempRect);
            wct.setScreenSizeDp(taskInfo2.token,
                    taskInfo2.configuration.screenWidthDp,
                    taskInfo2.configuration.screenHeightDp);
        }
    }

    /** Handles layout change event. */
    public interface SplitLayoutHandler {

        /** Calls when dismissing split. */
        void onSnappedToDismiss(boolean snappedToEnd);

        /** Calls when the bounds is changing due to animation or dragging divider bar. */
        void onLayoutChanging(SplitLayout layout);

        /** Calls when the target bounds changed. */
        void onLayoutChanged(SplitLayout layout);

        /**
         * Notifies when the layout shifted. So the layout handler can shift configuration
         * bounds correspondingly to make sure client apps won't get configuration changed or
         * relaunch. If the layout is no longer shifted, layout handler should restore shifted
         * configuration bounds.
         */
        void onLayoutShifted(int offsetX, int offsetY, SplitLayout layout);

        /** Calls when user double tapped on the divider bar. */
        default void onDoubleTappedDivider() {
        }

        /** Returns split position of the token. */
        @SplitPosition
        int getSplitItemPosition(WindowContainerToken token);
    }

    /**
     * Calculates and applies proper dismissing parallax offset and dimming value to hint users
     * dismissing gesture.
     */
    private class DismissingParallaxPolicy {
        // The current dismissing side.
        int mDismissingSide = DOCKED_INVALID;

        // The parallax offset to hint the dismissing side and progress.
        final Point mDismissingParallaxOffset = new Point();

        // The dimming value to hint the dismissing side and progress.
        float mDismissingDimValue = 0.0f;

        /**
         * Applies a parallax to the task to hint dismissing progress.
         *
         * @param position    the split position to apply dismissing parallax effect
         * @param isLandscape indicates whether it's splitting horizontally or vertically
         */
        void applyDividerPosition(int position, boolean isLandscape) {
            mDismissingSide = DOCKED_INVALID;
            mDismissingParallaxOffset.set(0, 0);
            mDismissingDimValue = 0;

            int totalDismissingDistance = 0;
            if (position <= mDividerSnapAlgorithm.getFirstSplitTarget().position) {
                mDismissingSide = isLandscape ? DOCKED_LEFT : DOCKED_TOP;
                totalDismissingDistance = mDividerSnapAlgorithm.getDismissStartTarget().position
                        - mDividerSnapAlgorithm.getFirstSplitTarget().position;
            } else if (position >= mDividerSnapAlgorithm.getLastSplitTarget().position) {
                mDismissingSide = isLandscape ? DOCKED_RIGHT : DOCKED_BOTTOM;
                totalDismissingDistance = mDividerSnapAlgorithm.getLastSplitTarget().position
                        - mDividerSnapAlgorithm.getDismissEndTarget().position;
            }

            if (mDismissingSide != DOCKED_INVALID) {
                float fraction = Math.max(0,
                        Math.min(mDividerSnapAlgorithm.calculateDismissingFraction(position), 1f));
                mDismissingDimValue = DIM_INTERPOLATOR.getInterpolation(fraction);
                fraction = calculateParallaxDismissingFraction(fraction, mDismissingSide);
                if (isLandscape) {
                    mDismissingParallaxOffset.x = (int) (fraction * totalDismissingDistance);
                } else {
                    mDismissingParallaxOffset.y = (int) (fraction * totalDismissingDistance);
                }
            }
        }

        /**
         * @return for a specified {@code fraction}, this returns an adjusted value that simulates a
         * slowing down parallax effect
         */
        private float calculateParallaxDismissingFraction(float fraction, int dockSide) {
            float result = SLOWDOWN_INTERPOLATOR.getInterpolation(fraction) / 3.5f;

            // Less parallax at the top, just because.
            if (dockSide == WindowManager.DOCKED_TOP) {
                result /= 2f;
            }
            return result;
        }

        /** Applies parallax offset and dimming value to the root surface at the dismissing side. */
        boolean adjustDismissingSurface(SurfaceControl.Transaction t,
                SurfaceControl leash1, SurfaceControl leash2,
                SurfaceControl dimLayer1, SurfaceControl dimLayer2) {
            SurfaceControl targetLeash, targetDimLayer;
            switch (mDismissingSide) {
                case DOCKED_TOP:
                case DOCKED_LEFT:
                    targetLeash = leash1;
                    targetDimLayer = dimLayer1;
                    mTempRect.set(mBounds1);
                    break;
                case DOCKED_BOTTOM:
                case DOCKED_RIGHT:
                    targetLeash = leash2;
                    targetDimLayer = dimLayer2;
                    mTempRect.set(mBounds2);
                    break;
                case DOCKED_INVALID:
                default:
                    t.setAlpha(dimLayer1, 0).hide(dimLayer1);
                    t.setAlpha(dimLayer2, 0).hide(dimLayer2);
                    return false;
            }

            t.setPosition(targetLeash,
                    mTempRect.left + mDismissingParallaxOffset.x,
                    mTempRect.top + mDismissingParallaxOffset.y);
            // Transform the screen-based split bounds to surface-based crop bounds.
            mTempRect.offsetTo(-mDismissingParallaxOffset.x, -mDismissingParallaxOffset.y);
            t.setWindowCrop(targetLeash, mTempRect);
            t.setAlpha(targetDimLayer, mDismissingDimValue)
                    .setVisibility(targetDimLayer, mDismissingDimValue > 0.001f);
            return true;
        }
    }

    /** Records IME top offset changes and updates SplitLayout correspondingly. */
    private class ImePositionProcessor implements DisplayImeController.ImePositionProcessor {
        /**
         * Maximum size of an adjusted split bounds relative to original stack bounds. Used to
         * restrict IME adjustment so that a min portion of top split remains visible.
         */
        private static final float ADJUSTED_SPLIT_FRACTION_MAX = 0.7f;
        private static final float ADJUSTED_NONFOCUS_DIM = 0.3f;

        private final int mDisplayId;

        private boolean mImeShown;
        private int mYOffsetForIme;
        private float mDimValue1;
        private float mDimValue2;

        private int mStartImeTop;
        private int mEndImeTop;

        private int mTargetYOffset;
        private int mLastYOffset;
        private float mTargetDim1;
        private float mTargetDim2;
        private float mLastDim1;
        private float mLastDim2;

        private ImePositionProcessor(int displayId) {
            mDisplayId = displayId;
        }

        @Override
        public int onImeStartPositioning(int displayId, int hiddenTop, int shownTop,
                boolean showing, boolean isFloating, SurfaceControl.Transaction t) {
            if (displayId != mDisplayId) return 0;
            final int imeTargetPosition = getImeTargetPosition();
            if (!mInitialized || imeTargetPosition == SPLIT_POSITION_UNDEFINED) return 0;
            mStartImeTop = showing ? hiddenTop : shownTop;
            mEndImeTop = showing ? shownTop : hiddenTop;
            mImeShown = showing;

            // Update target dim values
            mLastDim1 = mDimValue1;
            mTargetDim1 = imeTargetPosition == SPLIT_POSITION_BOTTOM_OR_RIGHT && showing
                    ? ADJUSTED_NONFOCUS_DIM : 0.0f;
            mLastDim2 = mDimValue2;
            mTargetDim2 = imeTargetPosition == SPLIT_POSITION_TOP_OR_LEFT && showing
                    ? ADJUSTED_NONFOCUS_DIM : 0.0f;

            // Calculate target bounds offset for IME
            mLastYOffset = mYOffsetForIme;
            final boolean needOffset = imeTargetPosition == SPLIT_POSITION_BOTTOM_OR_RIGHT
                    && !isFloating && !isLandscape(mRootBounds) && showing;
            mTargetYOffset = needOffset ? getTargetYOffset() : 0;

            if (mTargetYOffset != mLastYOffset) {
                // Freeze the configuration size with offset to prevent app get a configuration
                // changed or relaunch. This is required to make sure client apps will calculate
                // insets properly after layout shifted.
                if (mTargetYOffset == 0) {
                    mSplitLayoutHandler.onLayoutShifted(0, 0, SplitLayout.this);
                } else {
                    mSplitLayoutHandler.onLayoutShifted(0, mTargetYOffset - mLastYOffset,
                            SplitLayout.this);
                }
            }

            // Make {@link DividerView} non-interactive while IME showing in split mode. Listen to
            // ImePositionProcessor#onImeVisibilityChanged directly in DividerView is not enough
            // because DividerView won't receive onImeVisibilityChanged callback after it being
            // re-inflated.
            mSplitWindowManager.setInteractive(
                    !showing || imeTargetPosition == SPLIT_POSITION_UNDEFINED);

            return needOffset ? IME_ANIMATION_NO_ALPHA : 0;
        }

        @Override
        public void onImePositionChanged(int displayId, int imeTop, SurfaceControl.Transaction t) {
            if (displayId != mDisplayId) return;
            onProgress(getProgress(imeTop));
            mSplitLayoutHandler.onLayoutChanging(SplitLayout.this);
        }

        @Override
        public void onImeEndPositioning(int displayId, boolean cancel,
                SurfaceControl.Transaction t) {
            if (displayId != mDisplayId || cancel) return;
            onProgress(1.0f);
            mSplitLayoutHandler.onLayoutChanging(SplitLayout.this);
        }

        @Override
        public void onImeControlTargetChanged(int displayId, boolean controlling) {
            if (displayId != mDisplayId) return;
            // Restore the split layout when wm-shell is not controlling IME insets anymore.
            if (!controlling && mImeShown) {
                reset();
                mSplitWindowManager.setInteractive(true);
                mSplitLayoutHandler.onLayoutChanging(SplitLayout.this);
            }
        }

        private int getTargetYOffset() {
            final int desireOffset = Math.abs(mEndImeTop - mStartImeTop);
            // Make sure to keep at least 30% visible for the top split.
            final int maxOffset = (int) (mBounds1.height() * ADJUSTED_SPLIT_FRACTION_MAX);
            return -Math.min(desireOffset, maxOffset);
        }

        @SplitPosition
        private int getImeTargetPosition() {
            final WindowContainerToken token = mTaskOrganizer.getImeTarget(mDisplayId);
            return mSplitLayoutHandler.getSplitItemPosition(token);
        }

        private float getProgress(int currImeTop) {
            return ((float) currImeTop - mStartImeTop) / (mEndImeTop - mStartImeTop);
        }

        private void onProgress(float progress) {
            mDimValue1 = getProgressValue(mLastDim1, mTargetDim1, progress);
            mDimValue2 = getProgressValue(mLastDim2, mTargetDim2, progress);
            mYOffsetForIme =
                    (int) getProgressValue((float) mLastYOffset, (float) mTargetYOffset, progress);
        }

        private float getProgressValue(float start, float end, float progress) {
            return start + (end - start) * progress;
        }

        void reset() {
            mImeShown = false;
            mYOffsetForIme = mLastYOffset = mTargetYOffset = 0;
            mDimValue1 = mLastDim1 = mTargetDim1 = 0.0f;
            mDimValue2 = mLastDim2 = mTargetDim2 = 0.0f;
        }

        /**
         * Applies adjusted task layout for showing IME.
         *
         * @return {@code false} if there's no need to adjust, otherwise {@code true}
         */
        boolean applyTaskLayoutForIme(WindowContainerTransaction wct,
                WindowContainerToken token1, WindowContainerToken token2) {
            if (mYOffsetForIme == 0) return false;

            mTempRect.set(mBounds1);
            mTempRect.offset(0, mYOffsetForIme);
            wct.setBounds(token1, mTempRect);

            mTempRect.set(mBounds2);
            mTempRect.offset(0, mYOffsetForIme);
            wct.setBounds(token2, mTempRect);

            return true;
        }

        /**
         * Adjusts surface layout while showing IME.
         *
         * @return {@code false} if there's no need to adjust, otherwise {@code true}
         */
        boolean adjustSurfaceLayoutForIme(SurfaceControl.Transaction t,
                SurfaceControl dividerLeash, SurfaceControl leash1, SurfaceControl leash2,
                SurfaceControl dimLayer1, SurfaceControl dimLayer2) {
            if (mYOffsetForIme == 0) return false;

            if (dividerLeash != null) {
                mTempRect.set(mDividerBounds);
                mTempRect.offset(0, mYOffsetForIme);
                t.setPosition(dividerLeash, mTempRect.left, mTempRect.top);
            }

            mTempRect.set(mBounds1);
            mTempRect.offset(0, mYOffsetForIme);
            t.setPosition(leash1, mTempRect.left, mTempRect.top);

            mTempRect.set(mBounds2);
            mTempRect.offset(0, mYOffsetForIme);
            t.setPosition(leash2, mTempRect.left, mTempRect.top);

            t.setAlpha(dimLayer1, mDimValue1).setVisibility(dimLayer1, mDimValue1 > 0.001f);
            t.setAlpha(dimLayer2, mDimValue2).setVisibility(dimLayer2, mDimValue2 > 0.001f);

            return true;
        }
    }
}
