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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

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
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * Animated visual indicator for Desktop Mode windowing transitions.
 */
public class DesktopModeVisualIndicator {
    public enum IndicatorType {
        /** To be used when we don't want to indicate any transition */
        NO_INDICATOR,
        /** Indicates impending transition into desktop mode */
        TO_DESKTOP_INDICATOR,
        /** Indicates impending transition into fullscreen */
        TO_FULLSCREEN_INDICATOR,
        /** Indicates impending transition into split select on the left side */
        TO_SPLIT_LEFT_INDICATOR,
        /** Indicates impending transition into split select on the right side */
        TO_SPLIT_RIGHT_INDICATOR
    }

    private final Context mContext;
    private final DisplayController mDisplayController;
    private final RootTaskDisplayAreaOrganizer mRootTdaOrganizer;
    private final ActivityManager.RunningTaskInfo mTaskInfo;
    private final SurfaceControl mTaskSurface;
    private SurfaceControl mLeash;

    private final SyncTransactionQueue mSyncQueue;
    private SurfaceControlViewHost mViewHost;

    private View mView;
    private IndicatorType mCurrentType;

    public DesktopModeVisualIndicator(SyncTransactionQueue syncQueue,
            ActivityManager.RunningTaskInfo taskInfo, DisplayController displayController,
            Context context, SurfaceControl taskSurface,
            RootTaskDisplayAreaOrganizer taskDisplayAreaOrganizer) {
        mSyncQueue = syncQueue;
        mTaskInfo = taskInfo;
        mDisplayController = displayController;
        mContext = context;
        mTaskSurface = taskSurface;
        mRootTdaOrganizer = taskDisplayAreaOrganizer;
        mCurrentType = IndicatorType.NO_INDICATOR;
        createView();
    }

    /**
     * Based on the coordinates of the current drag event, determine which indicator type we should
     * display, including no visible indicator.
     * TODO(b/280828642): Update drag zones per starting windowing mode.
     */
    IndicatorType updateIndicatorType(PointF inputCoordinates) {
        final DisplayLayout layout = mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        // If we are in freeform, we don't want a visible indicator in the "freeform" drag zone.
        IndicatorType result = mTaskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM
                ? IndicatorType.NO_INDICATOR : IndicatorType.TO_DESKTOP_INDICATOR;
        int transitionAreaHeight = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.desktop_mode_transition_area_height);
        int transitionAreaWidth = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.desktop_mode_transition_area_width);
        if (inputCoordinates.y <= transitionAreaHeight) {
            result = IndicatorType.TO_FULLSCREEN_INDICATOR;
        } else if (inputCoordinates.x <= transitionAreaWidth) {
            result = IndicatorType.TO_SPLIT_LEFT_INDICATOR;
        } else if (inputCoordinates.x >= layout.width() - transitionAreaWidth) {
            result = IndicatorType.TO_SPLIT_RIGHT_INDICATOR;
        }
        transitionIndicator(result);
        return result;
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
        switch (mCurrentType) {
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
     * Fade indicator in as provided type. Animator fades it in while expanding the bounds outwards.
     */
    private void fadeInIndicator(IndicatorType type) {
        mView.setBackgroundResource(R.drawable.desktop_windowing_transition_background);
        final VisualIndicatorAnimator animator = VisualIndicatorAnimator
                .fadeBoundsIn(mView, type,
                        mDisplayController.getDisplayLayout(mTaskInfo.displayId));
        animator.start();
        mCurrentType = type;
    }

    /**
     * Fade out indicator without fully releasing it. Animator fades it out while shrinking bounds.
     */
    private void fadeOutIndicator() {
        final VisualIndicatorAnimator animator = VisualIndicatorAnimator
                .fadeBoundsOut(mView, mCurrentType,
                        mDisplayController.getDisplayLayout(mTaskInfo.displayId));
        animator.start();
        mCurrentType = IndicatorType.NO_INDICATOR;

    }

    /**
     * Takes existing indicator and animates it to bounds reflecting a new indicator type.
     */
    private void transitionIndicator(IndicatorType newType) {
        if (mCurrentType == newType) return;
        if (mCurrentType == IndicatorType.NO_INDICATOR) {
            fadeInIndicator(newType);
        } else if (newType == IndicatorType.NO_INDICATOR) {
            fadeOutIndicator();
        } else {
            final VisualIndicatorAnimator animator = VisualIndicatorAnimator.animateIndicatorType(
                    mView, mDisplayController.getDisplayLayout(mTaskInfo.displayId), mCurrentType,
                    newType);
            mCurrentType = newType;
            animator.start();
        }
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
     * Animator for Desktop Mode transitions which supports bounds and alpha animation.
     */
    private static class VisualIndicatorAnimator extends ValueAnimator {
        private static final int FULLSCREEN_INDICATOR_DURATION = 200;
        private static final float FULLSCREEN_SCALE_ADJUSTMENT_PERCENT = 0.015f;
        private static final float INDICATOR_FINAL_OPACITY = 0.7f;

        /** Determines how this animator will interact with the view's alpha:
         *  Fade in, fade out, or no change to alpha
         */
        private enum AlphaAnimType{
            ALPHA_FADE_IN_ANIM, ALPHA_FADE_OUT_ANIM, ALPHA_NO_CHANGE_ANIM
        }

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

        private static VisualIndicatorAnimator fadeBoundsIn(
                @NonNull View view, IndicatorType type, @NonNull DisplayLayout displayLayout) {
            final Rect startBounds = getIndicatorBounds(displayLayout, type);
            view.getBackground().setBounds(startBounds);

            final VisualIndicatorAnimator animator = new VisualIndicatorAnimator(
                    view, startBounds, getMaxBounds(startBounds));
            animator.setInterpolator(new DecelerateInterpolator());
            setupIndicatorAnimation(animator, AlphaAnimType.ALPHA_FADE_IN_ANIM);
            return animator;
        }

        private static VisualIndicatorAnimator fadeBoundsOut(
                @NonNull View view, IndicatorType type, @NonNull DisplayLayout displayLayout) {
            final Rect endBounds = getIndicatorBounds(displayLayout, type);
            final Rect startBounds = getMaxBounds(endBounds);
            view.getBackground().setBounds(startBounds);

            final VisualIndicatorAnimator animator = new VisualIndicatorAnimator(
                    view, startBounds, endBounds);
            animator.setInterpolator(new DecelerateInterpolator());
            setupIndicatorAnimation(animator, AlphaAnimType.ALPHA_FADE_OUT_ANIM);
            return animator;
        }

        /**
         * Create animator for visual indicator changing type (i.e., fullscreen to freeform,
         * freeform to split, etc.)
         *
         * @param view the view for this indicator
         * @param displayLayout information about the display the transitioning task is currently on
         * @param origType the original indicator type
         * @param newType the new indicator type
         */
        private static VisualIndicatorAnimator animateIndicatorType(@NonNull View view,
                @NonNull DisplayLayout displayLayout, IndicatorType origType,
                IndicatorType newType) {
            final Rect startBounds = getIndicatorBounds(displayLayout, origType);
            final Rect endBounds = getIndicatorBounds(displayLayout, newType);
            final VisualIndicatorAnimator animator = new VisualIndicatorAnimator(
                    view, startBounds, endBounds);
            animator.setInterpolator(new DecelerateInterpolator());
            setupIndicatorAnimation(animator, AlphaAnimType.ALPHA_NO_CHANGE_ANIM);
            return animator;
        }

        private static Rect getIndicatorBounds(DisplayLayout layout, IndicatorType type) {
            final int padding = layout.stableInsets().top;
            switch (type) {
                case TO_FULLSCREEN_INDICATOR:
                    return new Rect(padding, padding,
                            layout.width() - padding,
                            layout.height() - padding);
                case TO_DESKTOP_INDICATOR:
                    final float adjustmentPercentage = 1f - FINAL_FREEFORM_SCALE;
                    return new Rect((int) (adjustmentPercentage * layout.width() / 2),
                            (int) (adjustmentPercentage * layout.height() / 2),
                            (int) (layout.width() - (adjustmentPercentage * layout.width() / 2)),
                            (int) (layout.height() - (adjustmentPercentage * layout.height() / 2)));
                case TO_SPLIT_LEFT_INDICATOR:
                    return new Rect(padding, padding,
                            layout.width() / 2 - padding,
                            layout.height() - padding);
                case TO_SPLIT_RIGHT_INDICATOR:
                    return new Rect(layout.width() / 2 + padding, padding,
                            layout.width() - padding,
                            layout.height() - padding);
                default:
                    throw new IllegalArgumentException("Invalid indicator type provided.");
            }
        }

        /**
         * Add necessary listener for animation of indicator
         */
        private static void setupIndicatorAnimation(@NonNull VisualIndicatorAnimator animator,
                AlphaAnimType animType) {
            animator.addUpdateListener(a -> {
                if (animator.mView != null) {
                    animator.updateBounds(a.getAnimatedFraction(), animator.mView);
                    if (animType == AlphaAnimType.ALPHA_FADE_IN_ANIM) {
                        animator.updateIndicatorAlpha(a.getAnimatedFraction(), animator.mView);
                    } else if (animType == AlphaAnimType.ALPHA_FADE_OUT_ANIM) {
                        animator.updateIndicatorAlpha(1 - a.getAnimatedFraction(), animator.mView);
                    }
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
            final Rect currentBounds = mRectEvaluator.evaluate(fraction, mStartBounds, mEndBounds);
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
