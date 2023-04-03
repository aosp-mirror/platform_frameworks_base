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

package com.android.wm.shell.desktopmode;

import static com.android.wm.shell.desktopmode.EnterDesktopTaskTransitionHandler.FINAL_FREEFORM_SCALE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.view.animation.DecelerateInterpolator;

import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * Animated visual indicator for Desktop Mode windowing transitions.
 */
public class DesktopModeVisualIndicator {

    private final Context mContext;
    private final DisplayController mDisplayController;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final RootTaskDisplayAreaOrganizer mRootTdaOrganizer;
    private final ActivityManager.RunningTaskInfo mTaskInfo;
    private final SurfaceControl mTaskSurface;
    private SurfaceControl mLeash;

    private final SyncTransactionQueue mSyncQueue;
    private SurfaceControlViewHost mViewHost;

    private View mView;
    private boolean mIsFullscreen;

    public DesktopModeVisualIndicator(SyncTransactionQueue syncQueue,
            ActivityManager.RunningTaskInfo taskInfo, DisplayController displayController,
            Context context, SurfaceControl taskSurface, ShellTaskOrganizer taskOrganizer,
            RootTaskDisplayAreaOrganizer taskDisplayAreaOrganizer) {
        mSyncQueue = syncQueue;
        mTaskInfo = taskInfo;
        mDisplayController = displayController;
        mContext = context;
        mTaskSurface = taskSurface;
        mTaskOrganizer = taskOrganizer;
        mRootTdaOrganizer = taskDisplayAreaOrganizer;
        createView();
    }

    /**
     * Create a fullscreen indicator with no animation
     */
    private void createView() {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        final Resources resources = mContext.getResources();
        final DisplayMetrics metrics = resources.getDisplayMetrics();
        final int screenWidth = metrics.widthPixels;
        final int screenHeight = metrics.heightPixels;
        mView = new View(mContext);
        final SurfaceControl.Builder builder = new SurfaceControl.Builder();
        mRootTdaOrganizer.attachToDisplayArea(mTaskInfo.displayId, builder);
        mLeash = builder
                .setName("Fullscreen Indicator")
                .setContainerLayer()
                .build();
        t.show(mLeash);
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(screenWidth, screenHeight,
                        WindowManager.LayoutParams.TYPE_APPLICATION,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSPARENT);
        lp.setTitle("Fullscreen indicator for Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        final WindowlessWindowManager windowManager = new WindowlessWindowManager(
                mTaskInfo.configuration, mLeash,
                null /* hostInputToken */);
        mViewHost = new SurfaceControlViewHost(mContext,
                mDisplayController.getDisplay(mTaskInfo.displayId), windowManager,
                "FullscreenVisualIndicator");
        mViewHost.setView(mView, lp);
        // We want this indicator to be behind the dragged task, but in front of all others.
        t.setRelativeLayer(mLeash, mTaskSurface, -1);

        mSyncQueue.runInSync(transaction -> {
            transaction.merge(t);
            t.close();
        });
    }

    /**
     * Create fullscreen indicator and fades it in.
     */
    public void createFullscreenIndicator() {
        mIsFullscreen = true;
        mView.setBackgroundResource(R.drawable.desktop_windowing_transition_background);
        final VisualIndicatorAnimator animator = VisualIndicatorAnimator.toFullscreenAnimator(
                mView, mDisplayController.getDisplayLayout(mTaskInfo.displayId));
        animator.start();
    }

    /**
     * Create a fullscreen indicator. Animator fades it in while expanding the bounds outwards.
     */
    public void createFullscreenIndicatorWithAnimatedBounds() {
        mIsFullscreen = true;
        mView.setBackgroundResource(R.drawable.desktop_windowing_transition_background);
        final VisualIndicatorAnimator animator = VisualIndicatorAnimator
                .toFullscreenAnimatorWithAnimatedBounds(mView,
                        mDisplayController.getDisplayLayout(mTaskInfo.displayId));
        animator.start();
    }

    /**
     * Takes existing fullscreen indicator and animates it to freeform bounds
     */
    public void transitionFullscreenIndicatorToFreeform() {
        mIsFullscreen = false;
        final VisualIndicatorAnimator animator = VisualIndicatorAnimator.toFreeformAnimator(
                mView, mDisplayController.getDisplayLayout(mTaskInfo.displayId));
        animator.start();
    }

    /**
     * Takes the existing freeform indicator and animates it to fullscreen
     */
    public void transitionFreeformIndicatorToFullscreen() {
        mIsFullscreen = true;
        final VisualIndicatorAnimator animator =
                VisualIndicatorAnimator.toFullscreenAnimatorWithAnimatedBounds(
                mView, mDisplayController.getDisplayLayout(mTaskInfo.displayId));
        animator.start();
    }

    /**
     * Release the indicator and its components when it is no longer needed.
     */
    public void releaseVisualIndicator() {
        if (mViewHost == null) return;
        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }

        if (mLeash != null) {
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.remove(mLeash);
            mLeash = null;
            mSyncQueue.runInSync(transaction -> {
                transaction.merge(t);
                t.close();
            });
        }
    }

    /**
     * Returns true if visual indicator is fullscreen
     */
    public boolean isFullscreen() {
        return mIsFullscreen;
    }

    /**
     * Animator for Desktop Mode transitions which supports bounds and alpha animation.
     */
    private static class VisualIndicatorAnimator extends ValueAnimator {
        private static final int FULLSCREEN_INDICATOR_DURATION = 200;
        private static final float FULLSCREEN_SCALE_ADJUSTMENT_PERCENT = 0.015f;
        private static final float INDICATOR_FINAL_OPACITY = 0.7f;

        private final View mView;
        private final Rect mStartBounds;
        private final Rect mEndBounds;
        private final RectEvaluator mRectEvaluator;

        private VisualIndicatorAnimator(View view, Rect startBounds,
                Rect endBounds) {
            mView = view;
            mStartBounds = new Rect(startBounds);
            mEndBounds = endBounds;
            setFloatValues(0, 1);
            mRectEvaluator = new RectEvaluator(new Rect());
        }

        /**
         * Create animator for visual indicator of fullscreen transition
         *
         * @param view the view for this indicator
         * @param displayLayout information about the display the transitioning task is currently on
         */
        public static VisualIndicatorAnimator toFullscreenAnimator(@NonNull View view,
                @NonNull DisplayLayout displayLayout) {
            final Rect bounds = getMaxBounds(displayLayout);
            final VisualIndicatorAnimator animator = new VisualIndicatorAnimator(
                    view, bounds, bounds);
            animator.setInterpolator(new DecelerateInterpolator());
            setupIndicatorAnimation(animator);
            return animator;
        }


        /**
         * Create animator for visual indicator of fullscreen transition
         *
         * @param view the view for this indicator
         * @param displayLayout information about the display the transitioning task is currently on
         */
        public static VisualIndicatorAnimator toFullscreenAnimatorWithAnimatedBounds(
                @NonNull View view, @NonNull DisplayLayout displayLayout) {
            final int padding = displayLayout.stableInsets().top;
            Rect startBounds = new Rect(padding, padding,
                    displayLayout.width() - padding, displayLayout.height() - padding);
            view.getBackground().setBounds(startBounds);

            final VisualIndicatorAnimator animator = new VisualIndicatorAnimator(
                    view, startBounds, getMaxBounds(displayLayout));
            animator.setInterpolator(new DecelerateInterpolator());
            setupIndicatorAnimation(animator);
            return animator;
        }

        /**
         * Create animator for visual indicator of freeform transition
         *
         * @param view the view for this indicator
         * @param displayLayout information about the display the transitioning task is currently on
         */
        public static VisualIndicatorAnimator toFreeformAnimator(@NonNull View view,
                @NonNull DisplayLayout displayLayout) {
            final float adjustmentPercentage = 1f - FINAL_FREEFORM_SCALE;
            final int width = displayLayout.width();
            final int height = displayLayout.height();
            Rect endBounds = new Rect((int) (adjustmentPercentage * width / 2),
                    (int) (adjustmentPercentage * height / 2),
                    (int) (displayLayout.width() - (adjustmentPercentage * width / 2)),
                    (int) (displayLayout.height() - (adjustmentPercentage * height / 2)));
            final VisualIndicatorAnimator animator = new VisualIndicatorAnimator(
                    view, getMaxBounds(displayLayout), endBounds);
            animator.setInterpolator(new DecelerateInterpolator());
            setupIndicatorAnimation(animator);
            return animator;
        }

        /**
         * Add necessary listener for animation of indicator
         */
        private static void setupIndicatorAnimation(@NonNull VisualIndicatorAnimator animator) {
            animator.addUpdateListener(a -> {
                if (animator.mView != null) {
                    animator.updateBounds(a.getAnimatedFraction(), animator.mView);
                    animator.updateIndicatorAlpha(a.getAnimatedFraction(), animator.mView);
                } else {
                    animator.cancel();
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    animator.mView.getBackground().setBounds(animator.mEndBounds);
                }
            });
            animator.setDuration(FULLSCREEN_INDICATOR_DURATION);
        }

        /**
         * Update bounds of view based on current animation fraction.
         * Use of delta is to animate bounds independently, in case we need to
         * run multiple animations simultaneously.
         *
         * @param fraction fraction to use, compared against previous fraction
         * @param view     the view to update
         */
        private void updateBounds(float fraction, View view) {
            if (mStartBounds.equals(mEndBounds)) {
                return;
            }
            Rect currentBounds = mRectEvaluator.evaluate(fraction, mStartBounds, mEndBounds);
            view.getBackground().setBounds(currentBounds);
        }

        /**
         * Fade in the fullscreen indicator
         *
         * @param fraction current animation fraction
         */
        private void updateIndicatorAlpha(float fraction, View view) {
            view.setAlpha(fraction * INDICATOR_FINAL_OPACITY);
        }

        /**
         * Return the max bounds of a fullscreen indicator
         */
        private static Rect getMaxBounds(@NonNull DisplayLayout displayLayout) {
            final int padding = displayLayout.stableInsets().top;
            final int width = displayLayout.width() - 2 * padding;
            final int height = displayLayout.height() - 2 * padding;
            Rect endBounds = new Rect((int) (padding
                            - (FULLSCREEN_SCALE_ADJUSTMENT_PERCENT * width)),
                    (int) (padding
                            - (FULLSCREEN_SCALE_ADJUSTMENT_PERCENT * height)),
                    (int) (displayLayout.width() - padding
                            + (FULLSCREEN_SCALE_ADJUSTMENT_PERCENT * width)),
                    (int) (displayLayout.height() - padding
                            + (FULLSCREEN_SCALE_ADJUSTMENT_PERCENT * height)));
            return endBounds;
        }
    }
}
