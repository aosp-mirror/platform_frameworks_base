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

import static com.android.internal.policy.DividerSnapAlgorithm.SnapTarget.FLAG_DISMISS_END;
import static com.android.internal.policy.DividerSnapAlgorithm.SnapTarget.FLAG_DISMISS_START;
import static com.android.wm.shell.animation.Interpolators.DIM_INTERPOLATOR;
import static com.android.wm.shell.animation.Interpolators.SLOWDOWN_INTERPOLATOR;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Display;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.RoundedCorner;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DockedDividerUtils;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.InteractionJankMonitorUtils;
import com.android.wm.shell.common.split.SplitScreenConstants.SplitPosition;

import java.io.PrintWriter;

/**
 * Records and handles layout of splits. Helps to calculate proper bounds when configuration or
 * divide position changes.
 */
public final class SplitLayout implements DisplayInsetsController.OnInsetsChangedListener {

    public static final int PARALLAX_NONE = 0;
    public static final int PARALLAX_DISMISSING = 1;
    public static final int PARALLAX_ALIGN_CENTER = 2;

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
    private final ResizingEffectPolicy mSurfaceEffectPolicy;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final InsetsState mInsetsState = new InsetsState();

    private Context mContext;
    @VisibleForTesting DividerSnapAlgorithm mDividerSnapAlgorithm;
    private WindowContainerToken mWinToken1;
    private WindowContainerToken mWinToken2;
    private int mDividePosition;
    private boolean mInitialized = false;
    private boolean mFreezeDividerWindow = false;
    private int mOrientation;
    private int mRotation;

    private final boolean mDimNonImeSide;

    public SplitLayout(String windowName, Context context, Configuration configuration,
            SplitLayoutHandler splitLayoutHandler,
            SplitWindowManager.ParentContainerCallbacks parentContainerCallbacks,
            DisplayImeController displayImeController, ShellTaskOrganizer taskOrganizer,
            int parallaxType) {
        mContext = context.createConfigurationContext(configuration);
        mOrientation = configuration.orientation;
        mRotation = configuration.windowConfiguration.getRotation();
        mSplitLayoutHandler = splitLayoutHandler;
        mDisplayImeController = displayImeController;
        mSplitWindowManager = new SplitWindowManager(windowName, mContext, configuration,
                parentContainerCallbacks);
        mTaskOrganizer = taskOrganizer;
        mImePositionProcessor = new ImePositionProcessor(mContext.getDisplayId());
        mSurfaceEffectPolicy = new ResizingEffectPolicy(parallaxType);

        final Resources resources = context.getResources();
        mDividerSize = resources.getDimensionPixelSize(R.dimen.split_divider_bar_width);
        mDividerInsets = getDividerInsets(resources, context.getDisplay());
        mDividerWindowWidth = mDividerSize + 2 * mDividerInsets;

        mRootBounds.set(configuration.windowConfiguration.getBounds());
        mDividerSnapAlgorithm = getSnapAlgorithm(mContext, mRootBounds, null);
        resetDividerPosition();

        mDimNonImeSide = resources.getBoolean(R.bool.config_dimNonImeAttachedSide);
    }

    private int getDividerInsets(Resources resources, Display display) {
        final int dividerInset = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);

        int radius = 0;
        RoundedCorner corner = display.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
        radius = corner != null ? Math.max(radius, corner.getRadius()) : radius;
        corner = display.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT);
        radius = corner != null ? Math.max(radius, corner.getRadius()) : radius;
        corner = display.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
        radius = corner != null ? Math.max(radius, corner.getRadius()) : radius;
        corner = display.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
        radius = corner != null ? Math.max(radius, corner.getRadius()) : radius;

        return Math.max(dividerInset, radius);
    }

    /** Gets bounds of the primary split with screen based coordinate. */
    public Rect getBounds1() {
        return new Rect(mBounds1);
    }

    /** Gets bounds of the primary split with parent based coordinate. */
    public Rect getRefBounds1() {
        Rect outBounds = getBounds1();
        outBounds.offset(-mRootBounds.left, -mRootBounds.top);
        return outBounds;
    }

    /** Gets bounds of the secondary split with screen based coordinate. */
    public Rect getBounds2() {
        return new Rect(mBounds2);
    }

    /** Gets bounds of the secondary split with parent based coordinate. */
    public Rect getRefBounds2() {
        final Rect outBounds = getBounds2();
        outBounds.offset(-mRootBounds.left, -mRootBounds.top);
        return outBounds;
    }

    /** Gets bounds of divider window with screen based coordinate. */
    public Rect getDividerBounds() {
        return new Rect(mDividerBounds);
    }

    /** Gets bounds of divider window with parent based coordinate. */
    public Rect getRefDividerBounds() {
        final Rect outBounds = getDividerBounds();
        outBounds.offset(-mRootBounds.left, -mRootBounds.top);
        return outBounds;
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
        // Always update configuration after orientation changed to make sure to render divider bar
        // with proper resources that matching screen orientation.
        final int orientation = configuration.orientation;
        if (mOrientation != orientation) {
            mContext = mContext.createConfigurationContext(configuration);
            mSplitWindowManager.setConfiguration(configuration);
            mOrientation = orientation;
        }

        // Update the split bounds when necessary. Besides root bounds changed, split bounds need to
        // be updated when the rotation changed to cover the case that users rotated the screen 180
        // degrees.
        final int rotation = configuration.windowConfiguration.getRotation();
        final Rect rootBounds = configuration.windowConfiguration.getBounds();
        if (mRotation == rotation && mRootBounds.equals(rootBounds)) {
            return false;
        }

        mTempRect.set(mRootBounds);
        mRootBounds.set(rootBounds);
        mRotation = rotation;
        mDividerSnapAlgorithm = getSnapAlgorithm(mContext, mRootBounds, null);
        initDividerPosition(mTempRect);

        return true;
    }

    /** Rotate the layout to specific rotation and calculate new bounds. The stable insets value
     *  should be calculated by display layout. */
    public void rotateTo(int newRotation, Rect stableInsets) {
        final int rotationDelta = (newRotation - mRotation + 4) % 4;
        final boolean changeOrient = (rotationDelta % 2) != 0;

        mRotation = newRotation;
        Rect tmpRect = new Rect(mRootBounds);
        if (changeOrient) {
            tmpRect.set(mRootBounds.top, mRootBounds.left, mRootBounds.bottom, mRootBounds.right);
        }

        // We only need new bounds here, other configuration should be update later.
        mTempRect.set(mRootBounds);
        mRootBounds.set(tmpRect);
        mDividerSnapAlgorithm = getSnapAlgorithm(mContext, mRootBounds, stableInsets);
        initDividerPosition(mTempRect);
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
        mSurfaceEffectPolicy.applyDividerPosition(position, isLandscape);
    }

    /** Inflates {@link DividerView} on the root surface. */
    public void init() {
        if (mInitialized) return;
        mInitialized = true;
        mSplitWindowManager.init(this, mInsetsState);
        mDisplayImeController.addPositionProcessor(mImePositionProcessor);
    }

    /** Releases the surface holding the current {@link DividerView}. */
    public void release(SurfaceControl.Transaction t) {
        if (!mInitialized) return;
        mInitialized = false;
        mSplitWindowManager.release(t);
        mDisplayImeController.removePositionProcessor(mImePositionProcessor);
        mImePositionProcessor.reset();
    }

    public void release() {
        release(null /* t */);
    }

    /** Releases and re-inflates {@link DividerView} on the root surface. */
    public void update(SurfaceControl.Transaction t) {
        if (!mInitialized) return;
        mSplitWindowManager.release(t);
        mImePositionProcessor.reset();
        mSplitWindowManager.init(this, mInsetsState);
    }

    @Override
    public void insetsChanged(InsetsState insetsState) {
        mInsetsState.set(insetsState);
        if (!mInitialized) {
            return;
        }
        if (mFreezeDividerWindow) {
            // DO NOT change its layout before transition actually run because it might cause
            // flicker.
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

    public void setFreezeDividerWindow(boolean freezeDividerWindow) {
        mFreezeDividerWindow = freezeDividerWindow;
    }

    /**
     * Updates bounds with the passing position. Usually used to update recording bounds while
     * performing animation or dragging divider bar to resize the splits.
     */
    void updateDivideBounds(int position) {
        updateBounds(position);
        mSplitLayoutHandler.onLayoutSizeChanging(this);
    }

    void setDividePosition(int position, boolean applyLayoutChange) {
        mDividePosition = position;
        updateBounds(mDividePosition);
        if (applyLayoutChange) {
            mSplitLayoutHandler.onLayoutSizeChanged(this);
        }
    }

    /** Updates divide position and split bounds base on the ratio within root bounds. */
    public void setDivideRatio(float ratio) {
        final int position = isLandscape()
                ? mRootBounds.left + (int) (mRootBounds.width() * ratio)
                : mRootBounds.top + (int) (mRootBounds.height() * ratio);
        final DividerSnapAlgorithm.SnapTarget snapTarget =
                mDividerSnapAlgorithm.calculateNonDismissingSnapTarget(position);
        setDividePosition(snapTarget.position, false /* applyLayoutChange */);
    }

    /** Resets divider position. */
    public void resetDividerPosition() {
        mDividePosition = mDividerSnapAlgorithm.getMiddleTarget().position;
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
                flingDividePosition(currentPosition, snapTarget.position,
                        () -> setDividePosition(snapTarget.position, true /* applyLayoutChange */));
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

    private DividerSnapAlgorithm getSnapAlgorithm(Context context, Rect rootBounds,
            @Nullable Rect stableInsets) {
        final boolean isLandscape = isLandscape(rootBounds);
        return new DividerSnapAlgorithm(
                context.getResources(),
                rootBounds.width(),
                rootBounds.height(),
                mDividerSize,
                !isLandscape,
                stableInsets != null ? stableInsets : getDisplayInsets(context),
                isLandscape ? DOCKED_LEFT : DOCKED_TOP /* dockSide */);
    }

    @VisibleForTesting
    void flingDividePosition(int from, int to, @Nullable Runnable flingFinishedCallback) {
        if (from == to) {
            // No animation run, still callback to stop resizing.
            mSplitLayoutHandler.onLayoutSizeChanged(this);
            return;
        }
        InteractionJankMonitorUtils.beginTracing(InteractionJankMonitor.CUJ_SPLIT_SCREEN_RESIZE,
                mSplitWindowManager.getDividerView(), "Divider fling");
        ValueAnimator animator = ValueAnimator
                .ofInt(from, to)
                .setDuration(250);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.addUpdateListener(
                animation -> updateDivideBounds((int) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (flingFinishedCallback != null) {
                    flingFinishedCallback.run();
                }
                InteractionJankMonitorUtils.endTracing(
                        InteractionJankMonitor.CUJ_SPLIT_SCREEN_RESIZE);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                setDividePosition(to, true /* applyLayoutChange */);
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

    /** Reverse the split position. */
    @SplitPosition
    public static int reversePosition(@SplitPosition int position) {
        switch (position) {
            case SPLIT_POSITION_TOP_OR_LEFT:
                return SPLIT_POSITION_BOTTOM_OR_RIGHT;
            case SPLIT_POSITION_BOTTOM_OR_RIGHT:
                return SPLIT_POSITION_TOP_OR_LEFT;
            default:
                return SPLIT_POSITION_UNDEFINED;
        }
    }

    /**
     * Return if this layout is landscape.
     */
    public boolean isLandscape() {
        return isLandscape(mRootBounds);
    }

    /** Apply recorded surface layout to the {@link SurfaceControl.Transaction}. */
    public void applySurfaceChanges(SurfaceControl.Transaction t, SurfaceControl leash1,
            SurfaceControl leash2, SurfaceControl dimLayer1, SurfaceControl dimLayer2,
            boolean applyResizingOffset) {
        final SurfaceControl dividerLeash = getDividerLeash();
        if (dividerLeash != null) {
            mTempRect.set(getRefDividerBounds());
            t.setPosition(dividerLeash, mTempRect.left, mTempRect.top);
            // Resets layer of divider bar to make sure it is always on top.
            t.setLayer(dividerLeash, Integer.MAX_VALUE);
        }
        mTempRect.set(getRefBounds1());
        t.setPosition(leash1, mTempRect.left, mTempRect.top)
                .setWindowCrop(leash1, mTempRect.width(), mTempRect.height());
        mTempRect.set(getRefBounds2());
        t.setPosition(leash2, mTempRect.left, mTempRect.top)
                .setWindowCrop(leash2, mTempRect.width(), mTempRect.height());

        if (mImePositionProcessor.adjustSurfaceLayoutForIme(
                t, dividerLeash, leash1, leash2, dimLayer1, dimLayer2)) {
            return;
        }

        mSurfaceEffectPolicy.adjustDimSurface(t, dimLayer1, dimLayer2);
        if (applyResizingOffset) {
            mSurfaceEffectPolicy.adjustRootSurface(t, leash1, leash2);
        }
    }

    /** Apply recorded task layout to the {@link WindowContainerTransaction}. */
    public void applyTaskChanges(WindowContainerTransaction wct,
            ActivityManager.RunningTaskInfo task1, ActivityManager.RunningTaskInfo task2) {
        if (!mBounds1.equals(mWinBounds1) || !task1.token.equals(mWinToken1)) {
            wct.setBounds(task1.token, mBounds1);
            wct.setSmallestScreenWidthDp(task1.token, getSmallestWidthDp(mBounds1));
            mWinBounds1.set(mBounds1);
            mWinToken1 = task1.token;
        }
        if (!mBounds2.equals(mWinBounds2) || !task2.token.equals(mWinToken2)) {
            wct.setBounds(task2.token, mBounds2);
            wct.setSmallestScreenWidthDp(task2.token, getSmallestWidthDp(mBounds2));
            mWinBounds2.set(mBounds2);
            mWinToken2 = task2.token;
        }
    }

    private int getSmallestWidthDp(Rect bounds) {
        mTempRect.set(bounds);
        mTempRect.inset(getDisplayInsets(mContext));
        final int minWidth = Math.min(mTempRect.width(), mTempRect.height());
        final float density = mContext.getResources().getDisplayMetrics().density;
        return (int) (minWidth / density);
    }

    /**
     * Shift configuration bounds to prevent client apps get configuration changed or relaunch. And
     * restore shifted configuration bounds if it's no longer shifted.
     */
    public void applyLayoutOffsetTarget(WindowContainerTransaction wct, int offsetX, int offsetY,
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

    /** Dumps the current split bounds recorded in this layout. */
    public void dump(@NonNull PrintWriter pw, String prefix) {
        pw.println(prefix + "bounds1=" + mBounds1.toShortString());
        pw.println(prefix + "dividerBounds=" + mDividerBounds.toShortString());
        pw.println(prefix + "bounds2=" + mBounds2.toShortString());
    }

    /** Handles layout change event. */
    public interface SplitLayoutHandler {

        /** Calls when dismissing split. */
        void onSnappedToDismiss(boolean snappedToEnd);

        /**
         * Calls when resizing the split bounds.
         *
         * @see #applySurfaceChanges(SurfaceControl.Transaction, SurfaceControl, SurfaceControl,
         * SurfaceControl, SurfaceControl, boolean)
         */
        void onLayoutSizeChanging(SplitLayout layout);

        /**
         * Calls when finish resizing the split bounds.
         *
         * @see #applyTaskChanges(WindowContainerTransaction, ActivityManager.RunningTaskInfo,
         * ActivityManager.RunningTaskInfo)
         * @see #applySurfaceChanges(SurfaceControl.Transaction, SurfaceControl, SurfaceControl,
         * SurfaceControl, SurfaceControl, boolean)
         */
        void onLayoutSizeChanged(SplitLayout layout);

        /**
         * Calls when re-positioning the split bounds. Like moving split bounds while showing IME
         * panel.
         *
         * @see #applySurfaceChanges(SurfaceControl.Transaction, SurfaceControl, SurfaceControl,
         * SurfaceControl, SurfaceControl, boolean)
         */
        void onLayoutPositionChanging(SplitLayout layout);

        /**
         * Notifies the target offset for shifting layout. So layout handler can shift configuration
         * bounds correspondingly to make sure client apps won't get configuration changed or
         * relaunched. If the layout is no longer shifted, layout handler should restore shifted
         * configuration bounds.
         *
         * @see #applyLayoutOffsetTarget(WindowContainerTransaction, int, int,
         * ActivityManager.RunningTaskInfo, ActivityManager.RunningTaskInfo)
         */
        void setLayoutOffsetTarget(int offsetX, int offsetY, SplitLayout layout);

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
    private class ResizingEffectPolicy {
        /** Indicates whether to offset splitting bounds to hint dismissing progress or not. */
        private final int mParallaxType;

        int mShrinkSide = DOCKED_INVALID;

        // The current dismissing side.
        int mDismissingSide = DOCKED_INVALID;

        // The parallax offset to hint the dismissing side and progress.
        final Point mParallaxOffset = new Point();

        // The dimming value to hint the dismissing side and progress.
        float mDismissingDimValue = 0.0f;
        final Rect mContentBounds = new Rect();
        final Rect mSurfaceBounds = new Rect();

        ResizingEffectPolicy(int parallaxType) {
            mParallaxType = parallaxType;
        }

        /**
         * Applies a parallax to the task to hint dismissing progress.
         *
         * @param position    the split position to apply dismissing parallax effect
         * @param isLandscape indicates whether it's splitting horizontally or vertically
         */
        void applyDividerPosition(int position, boolean isLandscape) {
            mDismissingSide = DOCKED_INVALID;
            mParallaxOffset.set(0, 0);
            mDismissingDimValue = 0;

            int totalDismissingDistance = 0;
            if (position < mDividerSnapAlgorithm.getFirstSplitTarget().position) {
                mDismissingSide = isLandscape ? DOCKED_LEFT : DOCKED_TOP;
                totalDismissingDistance = mDividerSnapAlgorithm.getDismissStartTarget().position
                        - mDividerSnapAlgorithm.getFirstSplitTarget().position;
            } else if (position > mDividerSnapAlgorithm.getLastSplitTarget().position) {
                mDismissingSide = isLandscape ? DOCKED_RIGHT : DOCKED_BOTTOM;
                totalDismissingDistance = mDividerSnapAlgorithm.getLastSplitTarget().position
                        - mDividerSnapAlgorithm.getDismissEndTarget().position;
            }

            final boolean topLeftShrink = isLandscape
                    ? position < mWinBounds1.right : position < mWinBounds1.bottom;
            if (topLeftShrink) {
                mShrinkSide = isLandscape ? DOCKED_LEFT : DOCKED_TOP;
                mContentBounds.set(mWinBounds1);
                mSurfaceBounds.set(mBounds1);
            } else {
                mShrinkSide = isLandscape ? DOCKED_RIGHT : DOCKED_BOTTOM;
                mContentBounds.set(mWinBounds2);
                mSurfaceBounds.set(mBounds2);
            }

            if (mDismissingSide != DOCKED_INVALID) {
                float fraction = Math.max(0,
                        Math.min(mDividerSnapAlgorithm.calculateDismissingFraction(position), 1f));
                mDismissingDimValue = DIM_INTERPOLATOR.getInterpolation(fraction);
                if (mParallaxType == PARALLAX_DISMISSING) {
                    fraction = calculateParallaxDismissingFraction(fraction, mDismissingSide);
                    if (isLandscape) {
                        mParallaxOffset.x = (int) (fraction * totalDismissingDistance);
                    } else {
                        mParallaxOffset.y = (int) (fraction * totalDismissingDistance);
                    }
                }
            }

            if (mParallaxType == PARALLAX_ALIGN_CENTER) {
                if (isLandscape) {
                    mParallaxOffset.x =
                            (mSurfaceBounds.width() - mContentBounds.width()) / 2;
                } else {
                    mParallaxOffset.y =
                            (mSurfaceBounds.height() - mContentBounds.height()) / 2;
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
        void adjustRootSurface(SurfaceControl.Transaction t,
                SurfaceControl leash1, SurfaceControl leash2) {
            SurfaceControl targetLeash = null;

            if (mParallaxType == PARALLAX_DISMISSING) {
                switch (mDismissingSide) {
                    case DOCKED_TOP:
                    case DOCKED_LEFT:
                        targetLeash = leash1;
                        mTempRect.set(mBounds1);
                        break;
                    case DOCKED_BOTTOM:
                    case DOCKED_RIGHT:
                        targetLeash = leash2;
                        mTempRect.set(mBounds2);
                        break;
                }
            } else if (mParallaxType == PARALLAX_ALIGN_CENTER) {
                switch (mShrinkSide) {
                    case DOCKED_TOP:
                    case DOCKED_LEFT:
                        targetLeash = leash1;
                        mTempRect.set(mBounds1);
                        break;
                    case DOCKED_BOTTOM:
                    case DOCKED_RIGHT:
                        targetLeash = leash2;
                        mTempRect.set(mBounds2);
                        break;
                }
            }
            if (mParallaxType != PARALLAX_NONE && targetLeash != null) {
                t.setPosition(targetLeash,
                        mTempRect.left + mParallaxOffset.x, mTempRect.top + mParallaxOffset.y);
                // Transform the screen-based split bounds to surface-based crop bounds.
                mTempRect.offsetTo(-mParallaxOffset.x, -mParallaxOffset.y);
                t.setWindowCrop(targetLeash, mTempRect);
            }
        }

        void adjustDimSurface(SurfaceControl.Transaction t,
                SurfaceControl dimLayer1, SurfaceControl dimLayer2) {
            SurfaceControl targetDimLayer;
            switch (mDismissingSide) {
                case DOCKED_TOP:
                case DOCKED_LEFT:
                    targetDimLayer = dimLayer1;
                    break;
                case DOCKED_BOTTOM:
                case DOCKED_RIGHT:
                    targetDimLayer = dimLayer2;
                    break;
                case DOCKED_INVALID:
                default:
                    t.setAlpha(dimLayer1, 0).hide(dimLayer1);
                    t.setAlpha(dimLayer2, 0).hide(dimLayer2);
                    return;
            }
            t.setAlpha(targetDimLayer, mDismissingDimValue)
                    .setVisibility(targetDimLayer, mDismissingDimValue > 0.001f);
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

        private boolean mHasImeFocus;
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
            if (displayId != mDisplayId || !mInitialized) {
                return 0;
            }

            final int imeTargetPosition = getImeTargetPosition();
            mHasImeFocus = imeTargetPosition != SPLIT_POSITION_UNDEFINED;
            if (!mHasImeFocus) {
                return 0;
            }

            mStartImeTop = showing ? hiddenTop : shownTop;
            mEndImeTop = showing ? shownTop : hiddenTop;
            mImeShown = showing;

            // Update target dim values
            mLastDim1 = mDimValue1;
            mTargetDim1 = imeTargetPosition == SPLIT_POSITION_BOTTOM_OR_RIGHT && mImeShown
                    && mDimNonImeSide ? ADJUSTED_NONFOCUS_DIM : 0.0f;
            mLastDim2 = mDimValue2;
            mTargetDim2 = imeTargetPosition == SPLIT_POSITION_TOP_OR_LEFT && mImeShown
                    && mDimNonImeSide ? ADJUSTED_NONFOCUS_DIM : 0.0f;

            // Calculate target bounds offset for IME
            mLastYOffset = mYOffsetForIme;
            final boolean needOffset = imeTargetPosition == SPLIT_POSITION_BOTTOM_OR_RIGHT
                    && !isFloating && !isLandscape(mRootBounds) && mImeShown;
            mTargetYOffset = needOffset ? getTargetYOffset() : 0;

            if (mTargetYOffset != mLastYOffset) {
                // Freeze the configuration size with offset to prevent app get a configuration
                // changed or relaunch. This is required to make sure client apps will calculate
                // insets properly after layout shifted.
                if (mTargetYOffset == 0) {
                    mSplitLayoutHandler.setLayoutOffsetTarget(0, 0, SplitLayout.this);
                } else {
                    mSplitLayoutHandler.setLayoutOffsetTarget(0, mTargetYOffset - mLastYOffset,
                            SplitLayout.this);
                }
            }

            // Make {@link DividerView} non-interactive while IME showing in split mode. Listen to
            // ImePositionProcessor#onImeVisibilityChanged directly in DividerView is not enough
            // because DividerView won't receive onImeVisibilityChanged callback after it being
            // re-inflated.
            mSplitWindowManager.setInteractive(!mImeShown || !mHasImeFocus);

            return needOffset ? IME_ANIMATION_NO_ALPHA : 0;
        }

        @Override
        public void onImePositionChanged(int displayId, int imeTop, SurfaceControl.Transaction t) {
            if (displayId != mDisplayId || !mHasImeFocus) return;
            onProgress(getProgress(imeTop));
            mSplitLayoutHandler.onLayoutPositionChanging(SplitLayout.this);
        }

        @Override
        public void onImeEndPositioning(int displayId, boolean cancel,
                SurfaceControl.Transaction t) {
            if (displayId != mDisplayId || !mHasImeFocus || cancel) return;
            onProgress(1.0f);
            mSplitLayoutHandler.onLayoutPositionChanging(SplitLayout.this);
        }

        @Override
        public void onImeControlTargetChanged(int displayId, boolean controlling) {
            if (displayId != mDisplayId) return;
            // Restore the split layout when wm-shell is not controlling IME insets anymore.
            if (!controlling && mImeShown) {
                reset();
                mSplitWindowManager.setInteractive(true);
                mSplitLayoutHandler.setLayoutOffsetTarget(0, 0, SplitLayout.this);
                mSplitLayoutHandler.onLayoutPositionChanging(SplitLayout.this);
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
            mHasImeFocus = false;
            mImeShown = false;
            mYOffsetForIme = mLastYOffset = mTargetYOffset = 0;
            mDimValue1 = mLastDim1 = mTargetDim1 = 0.0f;
            mDimValue2 = mLastDim2 = mTargetDim2 = 0.0f;
        }

        /**
         * Adjusts surface layout while showing IME.
         *
         * @return {@code false} if there's no need to adjust, otherwise {@code true}
         */
        boolean adjustSurfaceLayoutForIme(SurfaceControl.Transaction t,
                SurfaceControl dividerLeash, SurfaceControl leash1, SurfaceControl leash2,
                SurfaceControl dimLayer1, SurfaceControl dimLayer2) {
            final boolean showDim = mDimValue1 > 0.001f || mDimValue2 > 0.001f;
            boolean adjusted = false;
            if (mYOffsetForIme != 0) {
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
                adjusted = true;
            }

            if (showDim) {
                t.setAlpha(dimLayer1, mDimValue1).setVisibility(dimLayer1, mDimValue1 > 0.001f);
                t.setAlpha(dimLayer2, mDimValue2).setVisibility(dimLayer2, mDimValue2 > 0.001f);
                adjusted = true;
            }
            return adjusted;
        }
    }
}
