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

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.view.DragEvent;
import android.view.SurfaceControl;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.widget.LinearLayout;

import com.android.internal.logging.InstanceId;
import com.android.internal.protolog.common.ProtoLog;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.splitscreen.SplitScreenController;

import java.util.ArrayList;
import java.util.List;

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

        mDropZoneView1 = new DropZoneView(context);
        mDropZoneView2 = new DropZoneView(context);
        addView(mDropZoneView1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        addView(mDropZoneView2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ((LayoutParams) mDropZoneView1.getLayoutParams()).weight = 1;
        ((LayoutParams) mDropZoneView2.getLayoutParams()).weight = 1;
        updateContainerMargins();
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
        final int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE
                && getOrientation() != HORIZONTAL) {
            setOrientation(LinearLayout.HORIZONTAL);
            updateContainerMargins();
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT
                && getOrientation() != VERTICAL) {
            setOrientation(LinearLayout.VERTICAL);
            updateContainerMargins();
        }
    }

    private void updateContainerMargins() {
        final int orientation = getResources().getConfiguration().orientation;
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

    public boolean hasDropTarget() {
        return mCurrentTarget != null;
    }

    public boolean hasDropped() {
        return mHasDropped;
    }

    public void prepare(DisplayLayout displayLayout, ClipData initialData,
            InstanceId loggerSessionId) {
        mPolicy.start(displayLayout, initialData, loggerSessionId);
        mHasDropped = false;
        mCurrentTarget = null;

        List<ActivityManager.RunningTaskInfo> tasks = null;
        // Figure out the splashscreen info for the existing task(s).
        try {
            tasks = ActivityTaskManager.getService().getTasks(2,
                            false /* filterOnlyVisibleRecents */,
                            false /* keepIntentExtra */);
        } catch (RemoteException e) {
            // don't show an icon / will just use the defaults
        }
        if (tasks != null && !tasks.isEmpty()) {
            ActivityManager.RunningTaskInfo taskInfo1 = tasks.get(0);
            Drawable icon1 = mIconProvider.getIcon(taskInfo1.topActivityInfo);
            int bgColor1 = getResizingBackgroundColor(taskInfo1);

            boolean alreadyInSplit = mSplitScreenController != null
                    && mSplitScreenController.isSplitScreenVisible();
            if (alreadyInSplit && tasks.size() > 1) {
                ActivityManager.RunningTaskInfo taskInfo2 = tasks.get(1);
                Drawable icon2 = mIconProvider.getIcon(taskInfo2.topActivityInfo);
                int bgColor2 = getResizingBackgroundColor(taskInfo2);

                // figure out which task is on which side
                int splitPosition1 = mSplitScreenController.getSplitPosition(taskInfo1.taskId);
                boolean isTask1TopOrLeft = splitPosition1 == SPLIT_POSITION_TOP_OR_LEFT;
                if (isTask1TopOrLeft) {
                    mDropZoneView1.setAppInfo(bgColor1, icon1);
                    mDropZoneView2.setAppInfo(bgColor2, icon2);
                } else {
                    mDropZoneView2.setAppInfo(bgColor1, icon1);
                    mDropZoneView1.setAppInfo(bgColor2, icon2);
                }
            } else {
                mDropZoneView1.setAppInfo(bgColor1, icon1);
                mDropZoneView2.setAppInfo(bgColor1, icon1);
            }
        }
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
                // Animating to first target
                animateSplitContainers(true, null /* animCompleteCallback */);
                animateHighlight(target);
            } else {
                // Switching between targets
                animateHighlight(target);
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

        // TODO(b/169894807): Coordinate with dragSurface
        hide(event, dropCompleteCallback);
        return handledDrop;
    }

    private void animateSplitContainers(boolean visible, Runnable animCompleteCallback) {
        mStatusBarManager.disable(visible
                ? HIDE_STATUS_BAR_FLAGS
                : DISABLE_NONE);
        mDropZoneView1.setShowingMargin(visible);
        mDropZoneView2.setShowingMargin(visible);
        ObjectAnimator animator = mDropZoneView1.getAnimator();
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
            mDropZoneView1.setShowingSplash(false);

            mDropZoneView2.setShowingHighlight(false);
            mDropZoneView2.setShowingSplash(true);
        } else if (target.type == DragAndDropPolicy.Target.TYPE_SPLIT_RIGHT
                || target.type == DragAndDropPolicy.Target.TYPE_SPLIT_BOTTOM) {
            mDropZoneView1.setShowingHighlight(false);
            mDropZoneView1.setShowingSplash(true);

            mDropZoneView2.setShowingHighlight(true);
            mDropZoneView2.setShowingSplash(false);
        }
    }

    private static int getResizingBackgroundColor(ActivityManager.RunningTaskInfo taskInfo) {
        final int taskBgColor = taskInfo.taskDescription.getBackgroundColor();
        return Color.valueOf(taskBgColor == -1 ? Color.WHITE : taskBgColor).toArgb();
    }
}
