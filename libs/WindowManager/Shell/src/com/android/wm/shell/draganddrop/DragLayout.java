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

package com.android.wm.shell.draganddrop;

import static android.app.StatusBarManager.DISABLE_NONE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.DragEvent;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.widget.LinearLayout;

import com.android.internal.logging.InstanceId;
import com.android.internal.protolog.common.ProtoLog;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.R;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.splitscreen.SplitScreenController;

import java.util.ArrayList;

/**
 * Coordinates the visible drop targets for the current drag.
 */
public class DragLayout extends LinearLayout {

    // While dragging the status bar is hidden.
    private static final int HIDE_STATUS_BAR_FLAGS = StatusBarManager.DISABLE_NOTIFICATION_ICONS
            | StatusBarManager.DISABLE_NOTIFICATION_ALERTS
            | StatusBarManager.DISABLE_CLOCK
            | StatusBarManager.DISABLE_SYSTEM_INFO;

    private final DragAndDropPolicy mPolicy;
    private final SplitScreenController mSplitScreenController;
    private final IconProvider mIconProvider;
    private final StatusBarManager mStatusBarManager;

    private DragAndDropPolicy.Target mCurrentTarget = null;
    private DropZoneView mDropZoneView1;
    private DropZoneView mDropZoneView2;

    private int mDisplayMargin;
    private int mDividerSize;
    private Insets mInsets = Insets.NONE;

    private boolean mIsShowing;
    private boolean mHasDropped;

    @SuppressLint("WrongConstant")
    public DragLayout(Context context, SplitScreenController splitScreenController,
            IconProvider iconProvider) {
        super(context);
        mSplitScreenController = splitScreenController;
        mIconProvider = iconProvider;
        mPolicy = new DragAndDropPolicy(context, splitScreenController);
        mStatusBarManager = context.getSystemService(StatusBarManager.class);

        mDisplayMargin = context.getResources().getDimensionPixelSize(
                R.dimen.drop_layout_display_margin);
        mDividerSize = context.getResources().getDimensionPixelSize(
                R.dimen.split_divider_bar_width);

        // Always use LTR because we assume dropZoneView1 is on the left and 2 is on the right when
        // showing the highlight.
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        mDropZoneView1 = new DropZoneView(context);
        mDropZoneView2 = new DropZoneView(context);
        addView(mDropZoneView1, new LinearLayout.LayoutParams(MATCH_PARENT,
                MATCH_PARENT));
        addView(mDropZoneView2, new LinearLayout.LayoutParams(MATCH_PARENT,
                MATCH_PARENT));
        ((LayoutParams) mDropZoneView1.getLayoutParams()).weight = 1;
        ((LayoutParams) mDropZoneView2.getLayoutParams()).weight = 1;
        int orientation = getResources().getConfiguration().orientation;
        setOrientation(orientation == Configuration.ORIENTATION_LANDSCAPE
                ? LinearLayout.HORIZONTAL
                : LinearLayout.VERTICAL);
        updateContainerMargins(getResources().getConfiguration().orientation);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mInsets = insets.getInsets(Type.systemBars() | Type.displayCutout());
        recomputeDropTargets();

        final int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mDropZoneView1.setBottomInset(mInsets.bottom);
            mDropZoneView2.setBottomInset(mInsets.bottom);
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            mDropZoneView1.setBottomInset(0);
            mDropZoneView2.setBottomInset(mInsets.bottom);
        }
        return super.onApplyWindowInsets(insets);
    }

    public void onThemeChange() {
        mDropZoneView1.onThemeChange();
        mDropZoneView2.onThemeChange();
    }

    public void onConfigChanged(Configuration newConfig) {
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
                && getOrientation() != HORIZONTAL) {
            setOrientation(LinearLayout.HORIZONTAL);
            updateContainerMargins(newConfig.orientation);
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
                && getOrientation() != VERTICAL) {
            setOrientation(LinearLayout.VERTICAL);
            updateContainerMargins(newConfig.orientation);
        }
    }

    private void updateContainerMarginsForSingleTask() {
        mDropZoneView1.setContainerMargin(
                mDisplayMargin, mDisplayMargin, mDisplayMargin, mDisplayMargin);
        mDropZoneView2.setContainerMargin(0, 0, 0, 0);
    }

    private void updateContainerMargins(int orientation) {
        final float halfMargin = mDisplayMargin / 2f;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mDropZoneView1.setContainerMargin(
                    mDisplayMargin, mDisplayMargin, halfMargin, mDisplayMargin);
            mDropZoneView2.setContainerMargin(
                    halfMargin, mDisplayMargin, mDisplayMargin, mDisplayMargin);
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            mDropZoneView1.setContainerMargin(
                    mDisplayMargin, mDisplayMargin, mDisplayMargin, halfMargin);
            mDropZoneView2.setContainerMargin(
                    mDisplayMargin, halfMargin, mDisplayMargin, mDisplayMargin);
        }
    }

    public boolean hasDropped() {
        return mHasDropped;
    }

    public void prepare(DisplayLayout displayLayout, ClipData initialData,
            InstanceId loggerSessionId) {
        mPolicy.start(displayLayout, initialData, loggerSessionId);
        mHasDropped = false;
        mCurrentTarget = null;

        boolean alreadyInSplit = mSplitScreenController != null
                && mSplitScreenController.isSplitScreenVisible();
        if (!alreadyInSplit) {
            ActivityManager.RunningTaskInfo taskInfo1 = mPolicy.getLatestRunningTask();
            if (taskInfo1 != null) {
                final int activityType = taskInfo1.getActivityType();
                if (activityType == ACTIVITY_TYPE_STANDARD) {
                    Drawable icon1 = mIconProvider.getIcon(taskInfo1.topActivityInfo);
                    int bgColor1 = getResizingBackgroundColor(taskInfo1);
                    mDropZoneView1.setAppInfo(bgColor1, icon1);
                    mDropZoneView2.setAppInfo(bgColor1, icon1);
                    updateDropZoneSizes(null, null); // passing null splits the views evenly
                } else {
                    // We use the first drop zone to show the fullscreen highlight, and don't need
                    // to set additional info
                    mDropZoneView1.setForceIgnoreBottomMargin(true);
                    updateDropZoneSizesForSingleTask();
                    updateContainerMarginsForSingleTask();
                }
            }
        } else {
            // We're already in split so get taskInfo from the controller to populate icon / color.
            ActivityManager.RunningTaskInfo topOrLeftTask =
                    mSplitScreenController.getTaskInfo(SPLIT_POSITION_TOP_OR_LEFT);
            ActivityManager.RunningTaskInfo bottomOrRightTask =
                    mSplitScreenController.getTaskInfo(SPLIT_POSITION_BOTTOM_OR_RIGHT);
            if (topOrLeftTask != null && bottomOrRightTask != null) {
                Drawable topOrLeftIcon = mIconProvider.getIcon(topOrLeftTask.topActivityInfo);
                int topOrLeftColor = getResizingBackgroundColor(topOrLeftTask);
                Drawable bottomOrRightIcon = mIconProvider.getIcon(
                        bottomOrRightTask.topActivityInfo);
                int bottomOrRightColor = getResizingBackgroundColor(bottomOrRightTask);
                mDropZoneView1.setAppInfo(topOrLeftColor, topOrLeftIcon);
                mDropZoneView2.setAppInfo(bottomOrRightColor, bottomOrRightIcon);
            }

            // Update the dropzones to match existing split sizes
            Rect topOrLeftBounds = new Rect();
            Rect bottomOrRightBounds = new Rect();
            mSplitScreenController.getStageBounds(topOrLeftBounds, bottomOrRightBounds);
            updateDropZoneSizes(topOrLeftBounds, bottomOrRightBounds);
        }
    }

    private void updateDropZoneSizesForSingleTask() {
        final LinearLayout.LayoutParams dropZoneView1 =
                (LayoutParams) mDropZoneView1.getLayoutParams();
        final LinearLayout.LayoutParams dropZoneView2 =
                (LayoutParams) mDropZoneView2.getLayoutParams();
        dropZoneView1.width = MATCH_PARENT;
        dropZoneView1.height = MATCH_PARENT;
        dropZoneView2.width = 0;
        dropZoneView2.height = 0;
        dropZoneView1.weight = 1;
        dropZoneView2.weight = 0;
        mDropZoneView1.setLayoutParams(dropZoneView1);
        mDropZoneView2.setLayoutParams(dropZoneView2);
    }

    /**
     * Sets the size of the two drop zones based on the provided bounds. The divider sits between
     * the views and its size is included in the calculations.
     *
     * @param bounds1 bounds to apply to the first dropzone view, null if split in half.
     * @param bounds2 bounds to apply to the second dropzone view, null if split in half.
     */
    private void updateDropZoneSizes(Rect bounds1, Rect bounds2) {
        final int orientation = getResources().getConfiguration().orientation;
        final boolean isPortrait = orientation == Configuration.ORIENTATION_PORTRAIT;
        final int halfDivider = mDividerSize / 2;
        final LinearLayout.LayoutParams dropZoneView1 =
                (LayoutParams) mDropZoneView1.getLayoutParams();
        final LinearLayout.LayoutParams dropZoneView2 =
                (LayoutParams) mDropZoneView2.getLayoutParams();
        if (isPortrait) {
            dropZoneView1.width = MATCH_PARENT;
            dropZoneView2.width = MATCH_PARENT;
            dropZoneView1.height = bounds1 != null ? bounds1.height() + halfDivider : MATCH_PARENT;
            dropZoneView2.height = bounds2 != null ? bounds2.height() + halfDivider : MATCH_PARENT;
        } else {
            dropZoneView1.width = bounds1 != null ? bounds1.width() + halfDivider : MATCH_PARENT;
            dropZoneView2.width = bounds2 != null ? bounds2.width() + halfDivider : MATCH_PARENT;
            dropZoneView1.height = MATCH_PARENT;
            dropZoneView2.height = MATCH_PARENT;
        }
        dropZoneView1.weight = bounds1 != null ? 0 : 1;
        dropZoneView2.weight = bounds2 != null ? 0 : 1;
        mDropZoneView1.setLayoutParams(dropZoneView1);
        mDropZoneView2.setLayoutParams(dropZoneView2);
    }

    public void show() {
        mIsShowing = true;
        recomputeDropTargets();
    }

    /**
     * Recalculates the drop targets based on the current policy.
     */
    private void recomputeDropTargets() {
        if (!mIsShowing) {
            return;
        }
        final ArrayList<DragAndDropPolicy.Target> targets = mPolicy.getTargets(mInsets);
        for (int i = 0; i < targets.size(); i++) {
            final DragAndDropPolicy.Target target = targets.get(i);
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Add target: %s", target);
            // Inset the draw region by a little bit
            target.drawRegion.inset(mDisplayMargin, mDisplayMargin);
        }
    }

    /**
     * Updates the visible drop target as the user drags.
     */
    public void update(DragEvent event) {
        if (mHasDropped) {
            return;
        }
        // Find containing region, if the same as mCurrentRegion, then skip, otherwise, animate the
        // visibility of the current region
        DragAndDropPolicy.Target target = mPolicy.getTargetAtLocation(
                (int) event.getX(), (int) event.getY());
        if (mCurrentTarget != target) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Current target: %s", target);
            if (target == null) {
                // Animating to no target
                animateSplitContainers(false, null /* animCompleteCallback */);
            } else if (mCurrentTarget == null) {
                if (mPolicy.getNumTargets() == 1) {
                    animateFullscreenContainer(true);
                } else {
                    animateSplitContainers(true, null /* animCompleteCallback */);
                    animateHighlight(target);
                }
            } else {
                // Switching between targets
                mDropZoneView1.animateSwitch();
                mDropZoneView2.animateSwitch();
            }
            mCurrentTarget = target;
        }
    }

    /**
     * Hides the drag layout and animates out the visible drop targets.
     */
    public void hide(DragEvent event, Runnable hideCompleteCallback) {
        mIsShowing = false;
        animateSplitContainers(false, hideCompleteCallback);
        // Reset the state if we previously force-ignore the bottom margin
        mDropZoneView1.setForceIgnoreBottomMargin(false);
        mDropZoneView2.setForceIgnoreBottomMargin(false);
        updateContainerMargins(getResources().getConfiguration().orientation);
        mCurrentTarget = null;
    }

    /**
     * Handles the drop onto a target and animates out the visible drop targets.
     */
    public boolean drop(DragEvent event, SurfaceControl dragSurface,
            Runnable dropCompleteCallback) {
        final boolean handledDrop = mCurrentTarget != null;
        mHasDropped = true;

        // Process the drop
        mPolicy.handleDrop(mCurrentTarget, event.getClipData());

        // Start animating the drop UI out with the drag surface
        hide(event, dropCompleteCallback);
        hideDragSurface(dragSurface);
        return handledDrop;
    }

    private void hideDragSurface(SurfaceControl dragSurface) {
        final SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        final ValueAnimator dragSurfaceAnimator = ValueAnimator.ofFloat(0f, 1f);
        // Currently the splash icon animation runs with the default ValueAnimator duration of
        // 300ms
        dragSurfaceAnimator.setDuration(300);
        dragSurfaceAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        dragSurfaceAnimator.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            float alpha = 1f - t;
            // TODO: Scale the drag surface as well once we make all the source surfaces
            //       consistent
            tx.setAlpha(dragSurface, alpha);
            tx.apply();
        });
        dragSurfaceAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCanceled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cleanUpSurface();
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCanceled) {
                    // Already handled above
                    return;
                }
                cleanUpSurface();
            }

            private void cleanUpSurface() {
                // Clean up the drag surface
                tx.remove(dragSurface);
                tx.apply();
            }
        });
        dragSurfaceAnimator.start();
    }

    private void animateFullscreenContainer(boolean visible) {
        mStatusBarManager.disable(visible
                ? HIDE_STATUS_BAR_FLAGS
                : DISABLE_NONE);
        // We're only using the first drop zone if there is one fullscreen target
        mDropZoneView1.setShowingMargin(visible);
        mDropZoneView1.setShowingHighlight(visible);
    }

    private void animateSplitContainers(boolean visible, Runnable animCompleteCallback) {
        mStatusBarManager.disable(visible
                ? HIDE_STATUS_BAR_FLAGS
                : DISABLE_NONE);
        mDropZoneView1.setShowingMargin(visible);
        mDropZoneView2.setShowingMargin(visible);
        Animator animator = mDropZoneView1.getAnimator();
        if (animCompleteCallback != null) {
            if (animator != null) {
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        animCompleteCallback.run();
                    }
                });
            } else {
                // If there's no animator the animation is done so run immediately
                animCompleteCallback.run();
            }
        }
    }

    private void animateHighlight(DragAndDropPolicy.Target target) {
        if (target.type == DragAndDropPolicy.Target.TYPE_SPLIT_LEFT
                || target.type == DragAndDropPolicy.Target.TYPE_SPLIT_TOP) {
            mDropZoneView1.setShowingHighlight(true);
            mDropZoneView2.setShowingHighlight(false);
        } else if (target.type == DragAndDropPolicy.Target.TYPE_SPLIT_RIGHT
                || target.type == DragAndDropPolicy.Target.TYPE_SPLIT_BOTTOM) {
            mDropZoneView1.setShowingHighlight(false);
            mDropZoneView2.setShowingHighlight(true);
        }
    }

    private static int getResizingBackgroundColor(ActivityManager.RunningTaskInfo taskInfo) {
        final int taskBgColor = taskInfo.taskDescription.getBackgroundColor();
        return Color.valueOf(taskBgColor == -1 ? Color.WHITE : taskBgColor).toArgb();
    }
}
