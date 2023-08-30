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
import android.graphics.PointF;
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
    public static final int INVALID_INDICATOR = -1;
    /** Indicates impending transition into desktop mode */
    public static final int TO_DESKTOP_INDICATOR = 1;
    /** Indicates impending transition into fullscreen */
    public static final int TO_FULLSCREEN_INDICATOR = 2;
    /** Indicates impending transition into split select on the left side */
    public static final int TO_SPLIT_LEFT_INDICATOR = 3;
    /** Indicates impending transition into split select on the right side */
    public static final int TO_SPLIT_RIGHT_INDICATOR = 4;

    private final Context mContext;
    private final DisplayController mDisplayController;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final RootTaskDisplayAreaOrganizer mRootTdaOrganizer;
    private final ActivityManager.RunningTaskInfo mTaskInfo;
    private final SurfaceControl mTaskSurface;
    private final Rect mIndicatorRange = new Rect();
    private SurfaceControl mLeash;

    private final SyncTransactionQueue mSyncQueue;
    private SurfaceControlViewHost mViewHost;

    private View mView;
    private boolean mIsFullscreen;
    private int mType;

    public DesktopModeVisualIndicator(SyncTransactionQueue syncQueue,
            ActivityManager.RunningTaskInfo taskInfo, DisplayController displayController,
            Context context, SurfaceControl taskSurface, ShellTaskOrganizer taskOrganizer,
            RootTaskDisplayAreaOrganizer taskDisplayAreaOrganizer, int type) {
        mSyncQueue = syncQueue;
        mTaskInfo = taskInfo;
        mDisplayController = displayController;
        mContext = context;
        mTaskSurface = taskSurface;
        mTaskOrganizer = taskOrganizer;
        mRootTdaOrganizer = taskDisplayAreaOrganizer;
        mType = type;
        defineIndicatorRange();
        createView();
    }

    /**
     * If an indicator is warranted based on the input and task bounds, return the type of
     * indicator that should be created.
     */
    public static int determineIndicatorType(PointF inputCoordinates, Rect taskBounds,
            DisplayLayout layout, Context context) {
        int transitionAreaHeight = context.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.desktop_mode_transition_area_height);
        int transitionAreaWidth = context.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.desktop_mode_transition_area_width);
        if (taskBounds.top <= transitionAreaHeight) return TO_FULLSCREEN_INDICATOR;
        if (inputCoordinates.x <= transitionAreaWidth) return TO_SPLIT_LEFT_INDICATOR;
        if (inputCoordinates.x >= layout.width() - transitionAreaWidth) {
            return TO_SPLIT_RIGHT_INDICATOR;
        }
        return INVALID_INDICATOR;
    }

    /**
     * Determine range of inputs that will keep this indicator displaying.
     */
    private void defineIndicatorRange() {
        DisplayLayout layout = mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        int captionHeight = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.freeform_decor_caption_height);
        int transitionAreaHeight = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.desktop_mode_transition_area_height);
        int transitionAreaWidth = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.desktop_mode_transition_area_width);
        switch (mType) {
            case TO_DESKTOP_INDICATOR:
                // TO_DESKTOP indicator is only dismissed on release; entire display is valid.
                mIndicatorRange.set(0, 0, layout.width(), layout.height());
                break;
            case TO_FULLSCREEN_INDICATOR:
                // If drag results in caption going above the top edge of the display, we still
                // want to transition to fullscreen.
                mIndicatorRange.set(0, -captionHeight, layout.width(), transitionAreaHeight);
                break;
            case TO_SPLIT_LEFT_INDICATOR:
                mIndicatorRange.set(0, transitionAreaHeight, transitionAreaWidth, layout.height());
                break;
            case TO_SPLIT_RIGHT_INDICATOR:
                mIndicatorRange.set(layout.width() - transitionAreaWidth, transitionAreaHeight,
                        layout.width(), layout.height());
                break;
            default:
                break;
        }
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
        String description;
        switch (mType) {
            case TO_DESKTOP_INDICATOR:
                description = "Desktop indicator";
                break;
            case TO_FULLSCREEN_INDICATOR:
                description = "Fullscreen indicator";
                break;
            case TO_SPLIT_LEFT_INDICATOR:
                description = "Split Left indicator";
                break;
            case TO_SPLIT_RIGHT_INDICATOR:
                description = "Split Right indicator";
                break;
            default:
                description = "Invalid indicator";
                break;
        }
        mLeash = builder
                .setName(description)
                .setContainerLayer()
                .build();
        t.show(mLeash);
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(screenWidth, screenHeight,
                        WindowManager.LayoutParams.TYPE_APPLICATION,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSPARENT);
        lp.setTitle(description + " for Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        final WindowlessWindowManager windowManager = new WindowlessWindowManager(
                mTaskInfo.configuration, mLeash,
                null /* hostInputToken */);
        mViewHost = new SurfaceControlViewHost(mContext,
                mDisplayController.getDisplay(mTaskInfo.displayId), windowManager,
                "DesktopModeVisualIndicator");
        mViewHost.setView(mView, lp);
        // We want this indicator to be behind the dragged task, but in front of all others.
        t.setRelativeLayer(mLeash, mTaskSurface, -1);

        mSyncQueue.runInSync(transaction -> {
            transaction.merge(t);
            t.close();
        });
    }

    /**
     * Create an indicator. Animator fades it in while expanding the bounds outwards.
     */
    public void createIndicatorWithAnimatedBounds() {
        mIsFullscreen = mType == TO_FULLSCREEN_INDICATOR;
        mView.setBackgroundResource(R.drawable.desktop_windowing_transition_background);
        final VisualIndicatorAnimator animator = VisualIndicatorAnimator
                .animateBounds(mView, mType,
                        mDisplayController.getDisplayLayout(mTaskInfo.displayId));
        animator.start();
    }

    /**
     * Takes existing fullscreen indicator and animates it to freeform bounds
     */
    public void transitionFullscreenIndicatorToFreeform() {
        mIsFullscreen = false;
        mType = TO_DESKTOP_INDICATOR;
        final VisualIndicatorAnimator animator = VisualIndicatorAnimator.toFreeformAnimator(
                mView, mDisplayController.getDisplayLayout(mTaskInfo.displayId));
        animator.start();
    }

    /**
     * Takes the existing freeform indicator and animates it to fullscreen
     */
    public void transitionFreeformIndicatorToFullscreen() {
        mIsFullscreen = true;
        mType = TO_FULLSCREEN_INDICATOR;
        final VisualIndicatorAnimator animator =
                VisualIndicatorAnimator.toFullscreenAnimatorWithAnimatedBounds(
                mView, mDisplayController.getDisplayLayout(mTaskInfo.displayId));
        animator.start();
    }

    /**
     * Determine if a MotionEvent is in the same range that enabled the indicator.
     * Used to dismiss the indicator when a transition will no longer result from releasing.
     */
    public boolean eventOutsideRange(float x, float y) {
        return !mIndicatorRange.contains((int) x, (int) y);
    }

    /**
     * Release the indicator and its components when it is no longer needed.
     */
    public void releaseVisualIndicator(SurfaceControl.Transaction t) {
        if (mViewHost == null) return;
        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }

        if (mLeash != null) {
            t.remove(mLeash);
            mLeash = null;
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
        public static VisualIndicatorAnimator toFullscreenAnimatorWithAnimatedBounds(
                @NonNull View view, @NonNull DisplayLayout displayLayout) {
            final int padding = displayLayout.stableInsets().top;
            Rect startBounds = new Rect(padding, padding,
                    displayLayout.width() - padding, displayLayout.height() - padding);
            view.getBackground().setBounds(startBounds);

            final VisualIndicatorAnimator animator = new VisualIndicatorAnimator(
                    view, startBounds, getMaxBounds(startBounds));
            animator.setInterpolator(new DecelerateInterpolator());
            setupIndicatorAnimation(animator);
            return animator;
        }

        public static VisualIndicatorAnimator animateBounds(
                @NonNull View view, int type, @NonNull DisplayLayout displayLayout) {
            final int padding = displayLayout.stableInsets().top;
            Rect startBounds = new Rect();
            switch (type) {
                case TO_FULLSCREEN_INDICATOR:
                    startBounds.set(padding, padding,
                            displayLayout.width() - padding,
                            displayLayout.height() - padding);
                    break;
                case TO_SPLIT_LEFT_INDICATOR:
                    startBounds.set(padding, padding,
                            displayLayout.width() / 2 - padding,
                            displayLayout.height() - padding);
                    break;
                case TO_SPLIT_RIGHT_INDICATOR:
                    startBounds.set(displayLayout.width() / 2 + padding, padding,
                            displayLayout.width() - padding,
                            displayLayout.height() - padding);
                    break;
            }
            view.getBackground().setBounds(startBounds);

            final VisualIndicatorAnimator animator = new VisualIndicatorAnimator(
                    view, startBounds, getMaxBounds(startBounds));
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
            Rect startBounds = new Rect(0, 0, width, height);
            Rect endBounds = new Rect((int) (adjustmentPercentage * width / 2),
                    (int) (adjustmentPercentage * height / 2),
                    (int) (displayLayout.width() - (adjustmentPercentage * width / 2)),
                    (int) (displayLayout.height() - (adjustmentPercentage * height / 2)));
            final VisualIndicatorAnimator animator = new VisualIndicatorAnimator(
                    view, startBounds, endBounds);
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
         * Return the max bounds of a visual indicator
         */
        private static Rect getMaxBounds(Rect startBounds) {
            return new Rect((int) (startBounds.left
                            - (FULLSCREEN_SCALE_ADJUSTMENT_PERCENT * startBounds.width())),
                    (int) (startBounds.top
                            - (FULLSCREEN_SCALE_ADJUSTMENT_PERCENT * startBounds.height())),
                    (int) (startBounds.right
                            + (FULLSCREEN_SCALE_ADJUSTMENT_PERCENT * startBounds.width())),
                    (int) (startBounds.bottom
                            + (FULLSCREEN_SCALE_ADJUSTMENT_PERCENT * startBounds.height())));
        }
    }
}
