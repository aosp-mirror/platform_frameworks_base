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

import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_TOP;

import static com.android.internal.policy.DividerSnapAlgorithm.SnapTarget.FLAG_DISMISS_END;
import static com.android.internal.policy.DividerSnapAlgorithm.SnapTarget.FLAG_DISMISS_START;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.WindowContainerToken;

import androidx.annotation.Nullable;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.common.DisplayImeController;

/**
 * Records and handles layout of splits. Helps to calculate proper bounds when configuration or
 * divide position changes.
 */
public final class SplitLayout {
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

    private final Rect mRootBounds = new Rect();
    private final Rect mDividerBounds = new Rect();
    private final Rect mBounds1 = new Rect();
    private final Rect mBounds2 = new Rect();
    private final SplitLayoutHandler mSplitLayoutHandler;
    private final SplitWindowManager mSplitWindowManager;
    private final DisplayImeController mDisplayImeController;
    private final ImePositionProcessor mImePositionProcessor;
    private final ShellTaskOrganizer mTaskOrganizer;

    private Context mContext;
    private DividerSnapAlgorithm mDividerSnapAlgorithm;
    private int mDividePosition;
    private boolean mInitialized = false;

    public SplitLayout(String windowName, Context context, Configuration configuration,
            SplitLayoutHandler splitLayoutHandler,
            SplitWindowManager.ParentContainerCallbacks parentContainerCallbacks,
            DisplayImeController displayImeController, ShellTaskOrganizer taskOrganizer) {
        mContext = context.createConfigurationContext(configuration);
        mSplitLayoutHandler = splitLayoutHandler;
        mDisplayImeController = displayImeController;
        mSplitWindowManager = new SplitWindowManager(
                windowName, mContext, configuration, parentContainerCallbacks);
        mTaskOrganizer = taskOrganizer;
        mImePositionProcessor = new ImePositionProcessor(mContext.getDisplayId());

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
        return mBounds1;
    }

    /** Gets bounds of the secondary split. */
    public Rect getBounds2() {
        return mBounds2;
    }

    /** Gets bounds of divider window. */
    public Rect getDividerBounds() {
        return mDividerBounds;
    }

    /** Returns leash of the current divider bar. */
    @Nullable
    public SurfaceControl getDividerLeash() {
        return mSplitWindowManager == null ? null : mSplitWindowManager.getSurfaceControl();
    }

    int getDividePosition() {
        return mDividePosition;
    }

    /** Applies new configuration, returns {@code false} if there's no effect to the layout. */
    public boolean updateConfiguration(Configuration configuration) {
        final Rect rootBounds = configuration.windowConfiguration.getBounds();
        if (mRootBounds.equals(rootBounds)) {
            return false;
        }

        mContext = mContext.createConfigurationContext(configuration);
        mSplitWindowManager.setConfiguration(configuration);
        mRootBounds.set(rootBounds);
        mDividerSnapAlgorithm = getSnapAlgorithm(mContext, mRootBounds);
        resetDividerPosition();

        // Don't inflate divider bar if it is not initialized.
        if (!mInitialized) {
            return false;
        }

        release();
        init();
        return true;
    }

    /** Updates recording bounds of divider window and both of the splits. */
    private void updateBounds(int position) {
        mDividerBounds.set(mRootBounds);
        mBounds1.set(mRootBounds);
        mBounds2.set(mRootBounds);
        if (isLandscape(mRootBounds)) {
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
    }

    /** Inflates {@link DividerView} on the root surface. */
    public void init() {
        if (mInitialized) return;
        mInitialized = true;
        mSplitWindowManager.init(this);
        mDisplayImeController.addPositionProcessor(mImePositionProcessor);
    }

    /** Releases the surface holding the current {@link DividerView}. */
    public void release() {
        if (!mInitialized) return;
        mInitialized = false;
        mSplitWindowManager.release();
        mDisplayImeController.removePositionProcessor(mImePositionProcessor);
    }

    /**
     * Updates bounds with the passing position. Usually used to update recording bounds while
     * performing animation or dragging divider bar to resize the splits.
     */
    void updateDivideBounds(int position) {
        updateBounds(position);
        mSplitWindowManager.setResizingSplits(true);
        mSplitLayoutHandler.onBoundsChanging(this);
    }

    void setDividePosition(int position) {
        mDividePosition = position;
        updateBounds(mDividePosition);
        mSplitLayoutHandler.onBoundsChanged(this);
        mSplitWindowManager.setResizingSplits(false);
    }

    /** Resets divider position. */
    public void resetDividerPosition() {
        mDividePosition = mDividerSnapAlgorithm.getMiddleTarget().position;
        updateBounds(mDividePosition);
    }

    /**
     * Sets new divide position and updates bounds correspondingly. Notifies listener if the new
     * target indicates dismissing split.
     */
    public void snapToTarget(int currentPosition, DividerSnapAlgorithm.SnapTarget snapTarget) {
        switch (snapTarget.flag) {
            case FLAG_DISMISS_START:
                mSplitLayoutHandler.onSnappedToDismiss(false /* bottomOrRight */);
                mSplitWindowManager.setResizingSplits(false);
                break;
            case FLAG_DISMISS_END:
                mSplitLayoutHandler.onSnappedToDismiss(true /* bottomOrRight */);
                mSplitWindowManager.setResizingSplits(false);
                break;
            default:
                flingDividePosition(currentPosition, snapTarget.position);
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

    private void flingDividePosition(int from, int to) {
        if (from == to) return;
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
                .getInsets(WindowInsets.Type.navigationBars()
                        | WindowInsets.Type.statusBars()
                        | WindowInsets.Type.displayCutout()).toRect();
    }

    private static boolean isLandscape(Rect bounds) {
        return bounds.width() > bounds.height();
    }

    /** Handles layout change event. */
    public interface SplitLayoutHandler {
        /** Calls when dismissing split. */
        void onSnappedToDismiss(boolean snappedToEnd);

        /** Calls when the bounds is changing due to animation or dragging divider bar. */
        void onBoundsChanging(SplitLayout layout);

        /** Calls when the target bounds changed. */
        void onBoundsChanged(SplitLayout layout);

        /** Calls when user double tapped on the divider bar. */
        default void onDoubleTappedDivider() {
        }

        /** Returns split position of the token. */
        @SplitPosition
        int getSplitItemPosition(WindowContainerToken token);
    }

    /** Records IME top offset changes and updates SplitLayout correspondingly. */
    private class ImePositionProcessor implements DisplayImeController.ImePositionProcessor {

        private final int mDisplayId;

        private ImePositionProcessor(int displayId) {
            mDisplayId = displayId;
        }

        @Override
        public int onImeStartPositioning(int displayId, int hiddenTop, int shownTop,
                boolean showing, boolean isFloating, SurfaceControl.Transaction t) {
            if (displayId != mDisplayId) return 0;
            final int imeTargetPosition = getImeTargetPosition();
            if (!mInitialized || imeTargetPosition == SPLIT_POSITION_UNDEFINED) return 0;

            // Make {@link DividerView} non-interactive while IME showing in split mode. Listen to
            // ImePositionProcessor#onImeVisibilityChanged directly in DividerView is not enough
            // because DividerView won't receive onImeVisibilityChanged callback after it being
            // re-inflated.
            mSplitWindowManager.setInteractive(
                    !showing || imeTargetPosition == SPLIT_POSITION_UNDEFINED);

            return 0;
        }

        @SplitPosition
        private int getImeTargetPosition() {
            final WindowContainerToken token = mTaskOrganizer.getImeTarget(mDisplayId);
            return mSplitLayoutHandler.getSplitItemPosition(token);
        }
    }
}
