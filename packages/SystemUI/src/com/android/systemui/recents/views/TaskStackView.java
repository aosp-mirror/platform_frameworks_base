/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.views;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.util.IntProperty;
import android.util.Property;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.CancelEnterRecentsWindowAnimationEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;
import com.android.systemui.recents.events.activity.HideHistoryButtonEvent;
import com.android.systemui.recents.events.activity.HideHistoryEvent;
import com.android.systemui.recents.events.activity.IterateRecentsEvent;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.activity.LaunchTaskStartedEvent;
import com.android.systemui.recents.events.activity.PackagesChangedEvent;
import com.android.systemui.recents.events.activity.ShowHistoryButtonEvent;
import com.android.systemui.recents.events.activity.ShowHistoryEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.events.ui.DeleteTaskDataEvent;
import com.android.systemui.recents.events.ui.DismissTaskViewEvent;
import com.android.systemui.recents.events.ui.StackViewScrolledEvent;
import com.android.systemui.recents.events.ui.TaskViewDismissedEvent;
import com.android.systemui.recents.events.ui.UpdateFreeformTaskViewVisibilityEvent;
import com.android.systemui.recents.events.ui.UserInteractionEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragDropTargetChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartInitializeDropTargetsEvent;
import com.android.systemui.recents.events.ui.focus.DismissFocusedTaskViewEvent;
import com.android.systemui.recents.events.ui.focus.FocusNextTaskViewEvent;
import com.android.systemui.recents.events.ui.focus.FocusPreviousTaskViewEvent;
import com.android.systemui.recents.misc.DozeTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;


/* The visual representation of a task stack view */
public class TaskStackView extends FrameLayout implements TaskStack.TaskStackCallbacks,
        TaskView.TaskViewCallbacks, TaskStackViewScroller.TaskStackViewScrollerCallbacks,
        ViewPool.ViewPoolConsumer<TaskView, Task> {

    private final static String KEY_SAVED_STATE_SUPER = "saved_instance_state_super";
    private final static String KEY_SAVED_STATE_LAYOUT_FOCUSED_STATE =
            "saved_instance_state_layout_focused_state";
    private final static String KEY_SAVED_STATE_LAYOUT_STACK_SCROLL =
            "saved_instance_state_layout_stack_scroll";

    // The thresholds at which to show/hide the history button.
    private static final float SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD = 0.3f;
    private static final float HIDE_HISTORY_BUTTON_SCROLL_THRESHOLD = 0.3f;

    private static final int DEFAULT_SYNC_STACK_DURATION = 200;
    private static final int DRAG_SCALE_DURATION = 175;
    private static final float DRAG_SCALE_FACTOR = 1.05f;

    public static final Property<Drawable, Integer> DRAWABLE_ALPHA =
            new IntProperty<Drawable>("drawableAlpha") {
                @Override
                public void setValue(Drawable object, int alpha) {
                    object.setAlpha(alpha);
                }

                @Override
                public Integer get(Drawable object) {
                    return object.getAlpha();
                }
            };

    TaskStack mStack;
    TaskStackLayoutAlgorithm mLayoutAlgorithm;
    TaskStackViewScroller mStackScroller;
    TaskStackViewTouchHandler mTouchHandler;
    TaskStackAnimationHelper mAnimationHelper;
    GradientDrawable mFreeformWorkspaceBackground;
    ObjectAnimator mFreeformWorkspaceBackgroundAnimator;
    ViewPool<TaskView, Task> mViewPool;
    boolean mStartTimerIndicator;

    ArrayList<TaskView> mTaskViews = new ArrayList<>();
    ArrayList<TaskViewTransform> mCurrentTaskTransforms = new ArrayList<>();
    TaskViewAnimation mDeferredTaskViewUpdateAnimation = null;

    DozeTrigger mUIDozeTrigger;
    Task mFocusedTask;

    int mTaskCornerRadiusPx;

    boolean mTaskViewsClipDirty = true;
    boolean mAwaitingFirstLayout = true;
    boolean mEnterAnimationComplete = false;
    boolean mTouchExplorationEnabled;
    boolean mScreenPinningEnabled;

    Rect mTaskStackBounds = new Rect();
    int[] mTmpVisibleRange = new int[2];
    Rect mTmpRect = new Rect();
    HashMap<Task, TaskView> mTmpTaskViewMap = new HashMap<>();
    List<TaskView> mTmpTaskViews = new ArrayList<>();
    TaskViewTransform mTmpTransform = new TaskViewTransform();
    LayoutInflater mInflater;

    Interpolator mFastOutSlowInInterpolator;

    // A convenience update listener to request updating clipping of tasks
    private ValueAnimator.AnimatorUpdateListener mRequestUpdateClippingListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mTaskViewsClipDirty = true;
                    invalidate();
                }
            };

    // The drop targets for a task drag
    private DropTarget mFreeformWorkspaceDropTarget = new DropTarget() {
        @Override
        public boolean acceptsDrop(int x, int y, int width, int height) {
            return mLayoutAlgorithm.mFreeformRect.contains(x, y);
        }
    };

    private DropTarget mStackDropTarget = new DropTarget() {
        @Override
        public boolean acceptsDrop(int x, int y, int width, int height) {
            return mLayoutAlgorithm.mStackRect.contains(x, y);
        }
    };

    public TaskStackView(Context context, TaskStack stack) {
        super(context);
        SystemServicesProxy ssp = Recents.getSystemServices();
        Resources res = context.getResources();

        // Set the stack first
        setStack(stack);
        mViewPool = new ViewPool<>(context, this);
        mInflater = LayoutInflater.from(context);
        mLayoutAlgorithm = new TaskStackLayoutAlgorithm(context);
        mStackScroller = new TaskStackViewScroller(context, mLayoutAlgorithm);
        mStackScroller.setCallbacks(this);
        mTouchHandler = new TaskStackViewTouchHandler(context, this, mStackScroller);
        mAnimationHelper = new TaskStackAnimationHelper(context, this);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        mTaskCornerRadiusPx = res.getDimensionPixelSize(
                R.dimen.recents_task_view_rounded_corners_radius);

        int taskBarDismissDozeDelaySeconds = getResources().getInteger(
                R.integer.recents_task_bar_dismiss_delay_seconds);
        mUIDozeTrigger = new DozeTrigger(taskBarDismissDozeDelaySeconds, new Runnable() {
            @Override
            public void run() {
                // Show the task bar dismiss buttons
                List<TaskView> taskViews = getTaskViews();
                int taskViewCount = taskViews.size();
                for (int i = 0; i < taskViewCount; i++) {
                    TaskView tv = taskViews.get(i);
                    tv.startNoUserInteractionAnimation();
                }
            }
        });
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        mFreeformWorkspaceBackground = (GradientDrawable) getContext().getDrawable(
                R.drawable.recents_freeform_workspace_bg);
        mFreeformWorkspaceBackground.setCallback(this);
        if (ssp.hasFreeformWorkspaceSupport()) {
            mFreeformWorkspaceBackground.setColor(
                    getContext().getColor(R.color.recents_freeform_workspace_bg_color));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        mTouchExplorationEnabled = ssp.isTouchExplorationEnabled();
        mScreenPinningEnabled = ssp.getSystemSetting(getContext(),
                Settings.System.LOCK_TO_APP_ENABLED) != 0;
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    /** Sets the task stack */
    void setStack(TaskStack stack) {
        // Set the new stack
        mStack = stack;
        if (mStack != null) {
            mStack.setCallbacks(this);
        }
        // Layout again with the new stack
        requestLayout();
    }

    /** Returns the task stack. */
    TaskStack getStack() {
        return mStack;
    }

    /** Updates the list of task views */
    void updateTaskViewsList() {
        mTaskViews.clear();
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View v = getChildAt(i);
            if (v instanceof TaskView) {
                mTaskViews.add((TaskView) v);
            }
        }
    }

    /** Gets the list of task views */
    List<TaskView> getTaskViews() {
        return mTaskViews;
    }

    /**
     * Returns the front most task view.
     *
     * @param stackTasksOnly if set, will return the front most task view in the stack (by default
     *                       the front most task view will be freeform since they are placed above
     *                       stack tasks)
     */
    private TaskView getFrontMostTaskView(boolean stackTasksOnly) {
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            if (stackTasksOnly && task.isFreeformTask()) {
                continue;
            }
            return tv;
        }
        return null;
    }

    /**
     * Finds the child view given a specific {@param task}.
     */
    public TaskView getChildViewForTask(Task t) {
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            if (tv.getTask() == t) {
                return tv;
            }
        }
        return null;
    }

    /** Resets this TaskStackView for reuse. */
    void reset() {
        // Reset the focused task
        resetFocusedTask(getFocusedTask());

        // Return all the views to the pool
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            mViewPool.returnViewToPool(taskViews.get(i));
        }

        // Mark each task view for relayout
        List<TaskView> poolViews = mViewPool.getViews();
        for (TaskView tv : poolViews) {
            tv.reset();
        }

        // Reset the stack state
        mStack.reset();
        mTaskViewsClipDirty = true;
        mAwaitingFirstLayout = true;
        mEnterAnimationComplete = false;
        mUIDozeTrigger.stopDozing();
        mUIDozeTrigger.resetTrigger();
        mStackScroller.reset();
        mLayoutAlgorithm.reset();
        requestLayout();
    }

    /** Returns the stack algorithm for this task stack. */
    public TaskStackLayoutAlgorithm getStackAlgorithm() {
        return mLayoutAlgorithm;
    }

    /**
     * Gets the stack transforms of a list of tasks, and returns the visible range of tasks.
     * This call ignores freeform tasks.
     */
    private boolean updateStackTransforms(ArrayList<TaskViewTransform> taskTransforms,
                                       ArrayList<Task> tasks,
                                       float stackScroll,
                                       int[] visibleRangeOut) {
        int taskTransformCount = taskTransforms.size();
        int taskCount = tasks.size();
        int frontMostVisibleIndex = -1;
        int backMostVisibleIndex = -1;

        // We can reuse the task transforms where possible to reduce object allocation
        if (taskTransformCount < taskCount) {
            // If there are less transforms than tasks, then add as many transforms as necessary
            for (int i = taskTransformCount; i < taskCount; i++) {
                taskTransforms.add(new TaskViewTransform());
            }
        } else if (taskTransformCount > taskCount) {
            // If there are more transforms than tasks, then just subset the transform list
            taskTransforms.subList(0, taskCount);
        }

        // Update the stack transforms
        TaskViewTransform frontTransform = null;
        for (int i = taskCount - 1; i >= 0; i--) {
            Task task = tasks.get(i);
            TaskViewTransform transform = mLayoutAlgorithm.getStackTransform(task, stackScroll,
                    taskTransforms.get(i), frontTransform);

            // For freeform tasks, only calculate the stack transform and skip the calculation of
            // the visible stack indices
            if (task.isFreeformTask()) {
                continue;
            }

            if (transform.visible) {
                if (frontMostVisibleIndex < 0) {
                    frontMostVisibleIndex = i;
                }
                backMostVisibleIndex = i;
            } else {
                if (backMostVisibleIndex != -1) {
                    // We've reached the end of the visible range, so going down the rest of the
                    // stack, we can just reset the transforms accordingly
                    while (i >= 0) {
                        taskTransforms.get(i).reset();
                        i--;
                    }
                    break;
                }
            }
            frontTransform = transform;
        }
        if (visibleRangeOut != null) {
            visibleRangeOut[0] = frontMostVisibleIndex;
            visibleRangeOut[1] = backMostVisibleIndex;
        }
        return frontMostVisibleIndex != -1 && backMostVisibleIndex != -1;
    }

    /**
     * Updates the children {@link TaskView}s to match the tasks in the current {@link TaskStack}.
     * This call does not update the {@link TaskView}s to their position in the layout except when
     * they are initially picked up from the pool, when they will be placed in a suitable initial
     * position.
     */
    private void bindTaskViewsWithStack() {
        final float stackScroll = mStackScroller.getStackScroll();
        final int[] visibleStackRange = mTmpVisibleRange;

        // Get all the task transforms
        final ArrayList<Task> tasks = mStack.getStackTasks();
        final boolean isValidVisibleStackRange = updateStackTransforms(mCurrentTaskTransforms, tasks,
                stackScroll, visibleStackRange);

        // Return all the invisible children to the pool
        mTmpTaskViewMap.clear();
        final List<TaskView> taskViews = getTaskViews();
        final int taskViewCount = taskViews.size();
        int lastFocusedTaskIndex = -1;
        for (int i = taskViewCount - 1; i >= 0; i--) {
            final TaskView tv = taskViews.get(i);
            final Task task = tv.getTask();
            final int taskIndex = mStack.indexOfStackTask(task);

            if (task.isFreeformTask() ||
                    visibleStackRange[1] <= taskIndex && taskIndex <= visibleStackRange[0]) {
                mTmpTaskViewMap.put(task, tv);
            } else {
                if (mTouchExplorationEnabled) {
                    lastFocusedTaskIndex = taskIndex;
                    resetFocusedTask(task);
                }
                mViewPool.returnViewToPool(tv);
            }
        }

        // Pick up all the newly visible children
        int lastVisStackIndex = isValidVisibleStackRange ? visibleStackRange[1] : 0;
        for (int i = mStack.getStackTaskCount() - 1; i >= lastVisStackIndex; i--) {
            final Task task = tasks.get(i);
            final TaskViewTransform transform = mCurrentTaskTransforms.get(i);

            // Skip the invisible non-freeform stack tasks
            if (i > visibleStackRange[0] && !task.isFreeformTask()) {
                continue;
            }

            TaskView tv = mTmpTaskViewMap.get(task);
            if (tv == null) {
                tv = mViewPool.pickUpViewFromPool(task, task);
                if (task.isFreeformTask()) {
                    tv.updateViewPropertiesToTaskTransform(transform, TaskViewAnimation.IMMEDIATE,
                            mRequestUpdateClippingListener);
                } else {
                    if (Float.compare(transform.p, 0f) <= 0) {
                        tv.updateViewPropertiesToTaskTransform(
                                mLayoutAlgorithm.getBackOfStackTransform(),
                                TaskViewAnimation.IMMEDIATE, mRequestUpdateClippingListener);
                    } else {
                        tv.updateViewPropertiesToTaskTransform(
                                mLayoutAlgorithm.getFrontOfStackTransform(),
                                TaskViewAnimation.IMMEDIATE, mRequestUpdateClippingListener);
                    }
                }
            } else {
                // Reattach it in the right z order
                final int taskIndex = mStack.indexOfStackTask(task);
                final int insertIndex = findTaskViewInsertIndex(task, taskIndex);
                if (insertIndex != getTaskViews().indexOf(tv)){
                    detachViewFromParent(tv);
                    attachViewToParent(tv, insertIndex, tv.getLayoutParams());
                    updateTaskViewsList();
                }
            }
        }

        // Update the focus if the previous focused task was returned to the view pool
        if (lastFocusedTaskIndex != -1) {
            if (lastFocusedTaskIndex < visibleStackRange[1]) {
                setFocusedTask(visibleStackRange[1], false /* scrollToTask */,
                        true /* requestViewFocus */);
            } else {
                setFocusedTask(visibleStackRange[0], false /* scrollToTask */,
                        true /* requestViewFocus */);
            }
        }
    }

    /**
     * Cancels any existing {@link TaskView} animations, and updates each {@link TaskView} to its
     * current position as defined by the {@link TaskStackLayoutAlgorithm}.
     */
    private void updateTaskViewsToLayout(TaskViewAnimation animation) {
        // If we had a deferred animation, cancel that
        mDeferredTaskViewUpdateAnimation = null;

        // Cancel all task view animations
        cancelAllTaskViewAnimations();

        // Fetch the current set of TaskViews
        bindTaskViewsWithStack();

        // Animate them to their final transforms with the given animation
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            final TaskView tv = taskViews.get(i);
            final int taskIndex = mStack.indexOfStackTask(tv.getTask());
            final TaskViewTransform transform = mCurrentTaskTransforms.get(taskIndex);

            updateTaskViewToTransform(tv, transform, animation);
        }
    }

    /**
     * Posts an update to synchronize the {@link TaskView}s with the stack on the next frame.
     */
    private void updateTaskViewsToLayoutOnNextFrame(TaskViewAnimation animation) {
        mDeferredTaskViewUpdateAnimation = animation;
        postInvalidateOnAnimation();
    }

    /**
     * Called to update a specific {@link TaskView} to a given {@link TaskViewTransform} with a
     * given set of {@link TaskViewAnimation} properties.
     */
    public void updateTaskViewToTransform(TaskView taskView, TaskViewTransform transform,
            TaskViewAnimation animation) {
        taskView.updateViewPropertiesToTaskTransform(transform, animation,
                mRequestUpdateClippingListener);
    }

    /**
     * Cancels all {@link TaskView} animations.
     */
    private void cancelAllTaskViewAnimations() {
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            final TaskView tv = taskViews.get(i);
            tv.cancelTransformAnimation();
        }
    }

    /**
     * Updates the clip for each of the task views from back to front.
     */
    private void clipTaskViews() {
        RecentsConfiguration config = Recents.getConfiguration();

        // Update the clip on each task child
        List<TaskView> taskViews = getTaskViews();
        TaskView tmpTv = null;
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            TaskView frontTv = null;
            int clipBottom = 0;
            if (i < (taskViewCount - 1) && tv.shouldClipViewInStack()) {
                // Find the next view to clip against
                for (int j = i + 1; j < taskViewCount; j++) {
                    tmpTv = taskViews.get(j);

                    if (tmpTv.shouldClipViewInStack()) {
                        frontTv = tmpTv;
                        break;
                    }
                }

                // Clip against the next view, this is just an approximation since we are
                // stacked and we can make assumptions about the visibility of the this
                // task relative to the ones in front of it.
                if (frontTv != null) {
                    float taskBottom = tv.getBottom();
                    float frontTaskTop = frontTv.getTop();
                    if (frontTaskTop < taskBottom) {
                        // Map the stack view space coordinate (the rects) to view space
                        clipBottom = (int) (taskBottom - frontTaskTop) - mTaskCornerRadiusPx;
                    }
                }
            }
            tv.getViewBounds().setClipBottom(clipBottom);
            if (!config.useHardwareLayers) {
                tv.mThumbnailView.updateThumbnailVisibility(clipBottom - tv.getPaddingBottom());
            }
        }
        mTaskViewsClipDirty = false;
    }

    /** Updates the min and max virtual scroll bounds */
    void updateLayout(boolean boundScrollToNewMinMax) {
        // Compute the min and max scroll values
        mLayoutAlgorithm.update(mStack);

        // Update the freeform workspace
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.hasFreeformWorkspaceSupport()) {
            mTmpRect.set(mLayoutAlgorithm.mFreeformRect);
            mFreeformWorkspaceBackground.setBounds(mTmpRect);
        }

        // Debug logging
        if (boundScrollToNewMinMax) {
            mStackScroller.boundScroll();
        }
    }

    /** Returns the scroller. */
    public TaskStackViewScroller getScroller() {
        return mStackScroller;
    }

    /**
     * Sets the focused task to the provided (bounded taskIndex).
     *
     * @return whether or not the stack will scroll as a part of this focus change
     */
    private boolean setFocusedTask(int taskIndex, boolean scrollToTask,
            final boolean requestViewFocus) {
        return setFocusedTask(taskIndex, scrollToTask, requestViewFocus, false);
    }

    /**
     * Sets the focused task to the provided (bounded taskIndex).
     *
     * @return whether or not the stack will scroll as a part of this focus change
     */
    private boolean setFocusedTask(int taskIndex, boolean scrollToTask,
            final boolean requestViewFocus, final boolean showTimerIndicator) {
        // Find the next task to focus
        int newFocusedTaskIndex = mStack.getStackTaskCount() > 0 ?
                Math.max(0, Math.min(mStack.getStackTaskCount() - 1, taskIndex)) : -1;
        final Task newFocusedTask = (newFocusedTaskIndex != -1) ?
                mStack.getStackTasks().get(newFocusedTaskIndex) : null;

        // Reset the last focused task state if changed
        if (mFocusedTask != null) {
            resetFocusedTask(mFocusedTask);

            // Cancel the timer indicator, if applicable
            if (showTimerIndicator) {
                final TaskView tv = getChildViewForTask(mFocusedTask);
                if (tv != null) {
                    tv.getHeaderView().cancelFocusTimerIndicator();
                }
            }
        }

        boolean willScroll = false;

        mFocusedTask = newFocusedTask;

        if (newFocusedTask != null) {
            // Start the timer indicator, if applicable
            if (showTimerIndicator) {
                final TaskView tv = getChildViewForTask(mFocusedTask);
                if (tv != null) {
                    tv.getHeaderView().startFocusTimerIndicator();
                } else {
                    // The view is null; set a flag for later
                    mStartTimerIndicator = true;
                }
            }

            Runnable focusTaskRunnable = new Runnable() {
                @Override
                public void run() {
                    final TaskView tv = getChildViewForTask(newFocusedTask);
                    if (tv != null) {
                        tv.setFocusedState(true, requestViewFocus);
                    }
                }
            };

            if (scrollToTask) {
                // TODO: Center the newly focused task view, only if not freeform
                float newScroll = mLayoutAlgorithm.getStackScrollForTask(newFocusedTask);
                if (Float.compare(newScroll, mStackScroller.getStackScroll()) != 0) {
                    mStackScroller.animateScroll(mStackScroller.getStackScroll(), newScroll,
                            focusTaskRunnable);
                    willScroll = true;

                    // Cancel any running enter animations at this point when we scroll as well
                    if (!mEnterAnimationComplete) {
                        cancelAllTaskViewAnimations();
                    }
                } else {
                    focusTaskRunnable.run();
                }
                mLayoutAlgorithm.animateFocusState(TaskStackLayoutAlgorithm.STATE_FOCUSED);
            } else {
                focusTaskRunnable.run();
            }
        }
        return willScroll;
    }

    /**
     * Sets the focused task relative to the currently focused task.
     *
     * @param forward whether to go to the next task in the stack (along the curve) or the previous
     * @param stackTasksOnly if set, will ensure that the traversal only goes along stack tasks, and
     *                       if the currently focused task is not a stack task, will set the focus
     *                       to the first visible stack task
     * @param animated determines whether to actually draw the highlight along with the change in
     *                            focus.
     */
    public void setRelativeFocusedTask(boolean forward, boolean stackTasksOnly, boolean animated) {
        setRelativeFocusedTask(forward, stackTasksOnly, animated, false);
    }

    /**
     * Sets the focused task relative to the currently focused task.
     *
     * @param forward whether to go to the next task in the stack (along the curve) or the previous
     * @param stackTasksOnly if set, will ensure that the traversal only goes along stack tasks, and
     *                       if the currently focused task is not a stack task, will set the focus
     *                       to the first visible stack task
     * @param animated determines whether to actually draw the highlight along with the change in
     *                            focus.
     * @param cancelWindowAnimations if set, will attempt to cancel window animations if a scroll
     *                               happens.
     */
    public void setRelativeFocusedTask(boolean forward, boolean stackTasksOnly, boolean animated,
                                       boolean cancelWindowAnimations) {
        setRelativeFocusedTask(forward, stackTasksOnly, animated, false, false);
    }

    /**
     * Sets the focused task relative to the currently focused task.
     *
     * @param forward whether to go to the next task in the stack (along the curve) or the previous
     * @param stackTasksOnly if set, will ensure that the traversal only goes along stack tasks, and
     *                       if the currently focused task is not a stack task, will set the focus
     *                       to the first visible stack task
     * @param animated determines whether to actually draw the highlight along with the change in
     *                            focus.
     * @param cancelWindowAnimations if set, will attempt to cancel window animations if a scroll
     *                               happens.
     * @param showTimerIndicator determines whether or not to show an indicator for the task auto-advance.
     */
    public void setRelativeFocusedTask(boolean forward, boolean stackTasksOnly, boolean animated,
                                       boolean cancelWindowAnimations, boolean showTimerIndicator) {
        int newIndex = mStack.indexOfStackTask(mFocusedTask);
        if (mFocusedTask != null) {
            if (stackTasksOnly) {
                List<Task> tasks =  mStack.getStackTasks();
                if (mFocusedTask.isFreeformTask()) {
                    // Try and focus the front most stack task
                    TaskView tv = getFrontMostTaskView(stackTasksOnly);
                    if (tv != null) {
                        newIndex = mStack.indexOfStackTask(tv.getTask());
                    }
                } else {
                    // Try the next task if it is a stack task
                    int tmpNewIndex = newIndex + (forward ? -1 : 1);
                    if (0 <= tmpNewIndex && tmpNewIndex < tasks.size()) {
                        Task t = tasks.get(tmpNewIndex);
                        if (!t.isFreeformTask()) {
                            newIndex = tmpNewIndex;
                        }
                    }
                }
            } else {
                // No restrictions, lets just move to the new task (looping forward/backwards if
                // necessary)
                int taskCount = mStack.getStackTaskCount();
                newIndex = (newIndex + (forward ? -1 : 1) + taskCount) % taskCount;
            }
        } else {
            // We don't have a focused task, so focus the first visible task view
            TaskView tv = getFrontMostTaskView(stackTasksOnly);
            if (tv != null) {
                newIndex = mStack.indexOfStackTask(tv.getTask());
            }
        }
        if (newIndex != -1) {
            boolean willScroll = setFocusedTask(newIndex, true /* scrollToTask */,
                    true /* requestViewFocus */, showTimerIndicator);
            if (willScroll && cancelWindowAnimations) {
                // As we iterate to the next/previous task, cancel any current/lagging window
                // transition animations
                EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(null));
            }
        }
    }

    /**
     * Resets the focused task.
     */
    void resetFocusedTask(Task task) {
        if (task != null) {
            TaskView tv = getChildViewForTask(task);
            if (tv != null) {
                tv.setFocusedState(false, false /* requestViewFocus */);
            }
        }
        mFocusedTask = null;
    }

    /**
     * Returns the focused task.
     */
    Task getFocusedTask() {
        return mFocusedTask;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        if (taskViewCount > 0) {
            TaskView backMostTask = taskViews.get(0);
            TaskView frontMostTask = taskViews.get(taskViewCount - 1);
            event.setFromIndex(mStack.indexOfStackTask(backMostTask.getTask()));
            event.setToIndex(mStack.indexOfStackTask(frontMostTask.getTask()));
            event.setContentDescription(frontMostTask.getTask().title);
        }
        event.setItemCount(mStack.getStackTaskCount());
        event.setScrollY(mStackScroller.mScroller.getCurrY());
        event.setMaxScrollY(mStackScroller.progressToScrollRange(mLayoutAlgorithm.mMaxScrollP));
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        if (taskViewCount > 1 && mFocusedTask != null) {
            info.setScrollable(true);
            int focusedTaskIndex = mStack.indexOfStackTask(mFocusedTask);
            if (focusedTaskIndex > 0) {
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
            if (focusedTaskIndex < mStack.getStackTaskCount() - 1) {
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
        }
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle savedState = new Bundle();
        savedState.putParcelable(KEY_SAVED_STATE_SUPER, super.onSaveInstanceState());
        savedState.putFloat(KEY_SAVED_STATE_LAYOUT_FOCUSED_STATE, mLayoutAlgorithm.getFocusState());
        savedState.putFloat(KEY_SAVED_STATE_LAYOUT_STACK_SCROLL, mStackScroller.getStackScroll());
        return super.onSaveInstanceState();
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        Bundle savedState = (Bundle) state;
        super.onRestoreInstanceState(savedState.getParcelable(KEY_SAVED_STATE_SUPER));

        mLayoutAlgorithm.setFocusState(savedState.getFloat(KEY_SAVED_STATE_LAYOUT_FOCUSED_STATE));
        mStackScroller.setStackScroll(savedState.getFloat(KEY_SAVED_STATE_LAYOUT_STACK_SCROLL));
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return TaskStackView.class.getName();
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (super.performAccessibilityAction(action, arguments)) {
            return true;
        }
        switch (action) {
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: {
                setRelativeFocusedTask(true, false /* stackTasksOnly */, false /* animated */);
                return true;
            }
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: {
                setRelativeFocusedTask(false, false /* stackTasksOnly */, false /* animated */);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return mTouchHandler.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return mTouchHandler.onTouchEvent(ev);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        return mTouchHandler.onGenericMotionEvent(ev);
    }

    @Override
    public void computeScroll() {
        if (mStackScroller.computeScroll()) {
            // Notify accessibility
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        }
        if (mDeferredTaskViewUpdateAnimation != null) {
            updateTaskViewsToLayout(mDeferredTaskViewUpdateAnimation);
            mTaskViewsClipDirty = true;
            mDeferredTaskViewUpdateAnimation = null;
        }
        if (mTaskViewsClipDirty) {
            clipTaskViews();
        }
    }

    /** Computes the stack and task rects */
    public void computeRects(Rect taskStackBounds) {
        // Compute the rects in the stack algorithm
        mLayoutAlgorithm.initialize(taskStackBounds,
                TaskStackLayoutAlgorithm.StackState.getStackStateForStack(mStack));

        // Update the scroll bounds
        updateLayout(false);
    }

    /**
     * This is ONLY used from the Recents component to update the dummy stack view for purposes
     * of getting the task rect to animate to.
     */
    public void updateLayoutForStack(TaskStack stack) {
        mStack = stack;
        updateLayout(false);
    }

    /**
     * Computes the maximum number of visible tasks and thumbnails.  Requires that
     * updateLayoutForStack() is called first.
     */
    public TaskStackLayoutAlgorithm.VisibilityReport computeStackVisibilityReport() {
        return mLayoutAlgorithm.computeStackVisibilityReport(mStack.getStackTasks());
    }

    public void setTaskStackBounds(Rect taskStackBounds, Rect systemInsets) {
        mTaskStackBounds.set(taskStackBounds);
        mLayoutAlgorithm.setSystemInsets(systemInsets);
    }

    /**
     * This is called with the full window width and height to allow stack view children to
     * perform the full screen transition down.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Compute our stack/task rects
        computeRects(mTaskStackBounds);

        // If this is the first layout, then scroll to the front of the stack, then update the
        // TaskViews with the stack so that we can lay them out
        if (mAwaitingFirstLayout) {
            mStackScroller.setStackScrollToInitialState();
        }
        bindTaskViewsWithStack();

        // Measure each of the TaskViews
        mTmpTaskViews.clear();
        mTmpTaskViews.addAll(getTaskViews());
        mTmpTaskViews.addAll(mViewPool.getViews());
        int taskViewCount = mTmpTaskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = mTmpTaskViews.get(i);
            if (tv.getBackground() != null) {
                tv.getBackground().getPadding(mTmpRect);
            } else {
                mTmpRect.setEmpty();
            }
            tv.measure(
                    MeasureSpec.makeMeasureSpec(
                            mLayoutAlgorithm.mTaskRect.width() + mTmpRect.left + mTmpRect.right,
                            MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(
                            mLayoutAlgorithm.mTaskRect.height() + mTmpRect.top + mTmpRect.bottom,
                            MeasureSpec.EXACTLY));
        }

        setMeasuredDimension(width, height);
    }

    /**
     * This is called with the size of the space not including the top or right insets, or the
     * search bar height in portrait (but including the search bar width in landscape, since we want
     * to draw under it.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // Layout each of the TaskViews
        mTmpTaskViews.clear();
        mTmpTaskViews.addAll(getTaskViews());
        mTmpTaskViews.addAll(mViewPool.getViews());
        int taskViewCount = mTmpTaskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = mTmpTaskViews.get(i);
            if (tv.getBackground() != null) {
                tv.getBackground().getPadding(mTmpRect);
            } else {
                mTmpRect.setEmpty();
            }
            Rect taskRect = mLayoutAlgorithm.mTaskRect;
            tv.layout(taskRect.left - mTmpRect.left, taskRect.top - mTmpRect.top,
                    taskRect.right + mTmpRect.right, taskRect.bottom + mTmpRect.bottom);
        }

        if (changed) {
            if (mStackScroller.isScrollOutOfBounds()) {
                mStackScroller.boundScroll();
            }
        }
        updateTaskViewsToLayout(TaskViewAnimation.IMMEDIATE);
        clipTaskViews();

        if (mAwaitingFirstLayout || !mEnterAnimationComplete) {
            mAwaitingFirstLayout = false;
            onFirstLayout();
            return;
        }
    }

    /** Handler for the first layout. */
    void onFirstLayout() {
        // Setup the view for the enter animation
        mAnimationHelper.prepareForEnterAnimation();

        // Animate in the freeform workspace
        animateFreeformWorkspaceBackgroundAlpha(
                mLayoutAlgorithm.getStackState().freeformBackgroundAlpha, 150,
                mFastOutSlowInInterpolator);

        // Set the task focused state without requesting view focus, and leave the focus animations
        // until after the enter-animation
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        int focusedTaskIndex = launchState.getInitialFocusTaskIndex(mStack.getStackTaskCount());
        if (focusedTaskIndex != -1) {
            setFocusedTask(focusedTaskIndex, false /* scrollToTask */,
                    false /* requestViewFocus */);
        }

        // Update the history button visibility
        if (shouldShowHistoryButton() &&
                mStackScroller.getStackScroll() < SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD) {
            EventBus.getDefault().send(new ShowHistoryButtonEvent());
        } else {
            EventBus.getDefault().send(new HideHistoryButtonEvent());
        }
    }

    public boolean isTransformedTouchPointInView(float x, float y, TaskView tv) {
        final float[] point = new float[2];
        point[0] = x;
        point[1] = y;
        transformPointToViewLocal(point, tv);
        x = point[0];
        y = point[1];
        return (0 <= x) && (x < tv.getWidth()) && (0 <= y) && (y < tv.getHeight());
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Draw the freeform workspace background
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.hasFreeformWorkspaceSupport()) {
            if (mFreeformWorkspaceBackground.getAlpha() > 0) {
                mFreeformWorkspaceBackground.draw(canvas);
            }
        }

        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        if (who == mFreeformWorkspaceBackground) {
            return true;
        }
        return super.verifyDrawable(who);
    }

    /**
     * Launches the freeform tasks.
     */
    public boolean launchFreeformTasks() {
        ArrayList<Task> tasks = mStack.getFreeformTasks();
        if (!tasks.isEmpty()) {
            Task frontTask = tasks.get(tasks.size() - 1);
            if (frontTask != null && frontTask.isFreeformTask()) {
                EventBus.getDefault().send(new LaunchTaskEvent(getChildViewForTask(frontTask),
                        frontTask, null, INVALID_STACK_ID, false));
                return true;
            }
        }
        return false;
    }

    /**** TaskStackCallbacks Implementation ****/

    @Override
    public void onStackTaskAdded(TaskStack stack, Task newTask) {
        // Update the min/max scroll and animate other task views into their new positions
        updateLayout(true);

        // Animate all the tasks into place
        updateTaskViewsToLayout(new TaskViewAnimation(DEFAULT_SYNC_STACK_DURATION,
                mFastOutSlowInInterpolator));
    }

    @Override
    public void onStackTaskRemoved(TaskStack stack, Task removedTask, boolean wasFrontMostTask,
            Task newFrontMostTask) {
        if (mFocusedTask == removedTask) {
            resetFocusedTask(removedTask);
        }

        if (!removedTask.isFreeformTask()) {
            // Remove the view associated with this task, we can't rely on updateTransforms
            // to work here because the task is no longer in the list
            TaskView tv = getChildViewForTask(removedTask);
            if (tv != null) {
                mViewPool.returnViewToPool(tv);
            }

            // Get the stack scroll of the task to anchor to (since we are removing something, the
            // front most task will be our anchor task)
            Task anchorTask = null;
            float prevAnchorTaskScroll = 0;
            boolean pullStackForward = stack.getStackTaskCount() > 0;
            if (pullStackForward) {
                anchorTask = mStack.getStackFrontMostTask();
                prevAnchorTaskScroll = mLayoutAlgorithm.getStackScrollForTask(anchorTask);
            }

            // Update the min/max scroll and animate other task views into their new positions
            updateLayout(true);

            if (wasFrontMostTask) {
                // Since the max scroll progress is offset from the bottom of the stack, just scroll
                // to ensure that the new front most task is now fully visible
                mStackScroller.setStackScroll(mLayoutAlgorithm.mMaxScrollP);
            } else if (pullStackForward) {
                // Otherwise, offset the scroll by the movement of the anchor task
                float anchorTaskScroll = mLayoutAlgorithm.getStackScrollForTask(anchorTask);
                float stackScrollOffset = (anchorTaskScroll - prevAnchorTaskScroll);
                if (mLayoutAlgorithm.getFocusState() != TaskStackLayoutAlgorithm.STATE_FOCUSED) {
                    // If we are focused, we don't want the front task to move, but otherwise, we
                    // allow the back task to move up, and the front task to move back
                    stackScrollOffset /= 2;
                }
                mStackScroller.setStackScroll(mStackScroller.getStackScroll() + stackScrollOffset);
                mStackScroller.boundScroll();
            }
        } else {
            // Remove the view associated with this task, we can't rely on updateTransforms
            // to work here because the task is no longer in the list
            TaskView tv = getChildViewForTask(removedTask);
            if (tv != null) {
                mViewPool.returnViewToPool(tv);
            }

            // Update the min/max scroll and animate other task views into their new positions
            updateLayout(true);
        }

        // Animate all the tasks into place
        updateTaskViewsToLayout(new TaskViewAnimation(DEFAULT_SYNC_STACK_DURATION,
                mFastOutSlowInInterpolator));

        // Update the new front most task's action button
        if (mScreenPinningEnabled && newFrontMostTask != null) {
            TaskView frontTv = getChildViewForTask(newFrontMostTask);
            if (frontTv != null) {
                frontTv.showActionButton(true /* fadeIn */, DEFAULT_SYNC_STACK_DURATION);
            }
        }

        // If there are no remaining tasks, then just close recents
        if (mStack.getStackTaskCount() == 0) {
            boolean shouldFinishActivity = (mStack.getStackTaskCount() == 0);
            if (shouldFinishActivity) {
                EventBus.getDefault().send(new AllTaskViewsDismissedEvent());
            }
        }
    }

    @Override
    public void onHistoryTaskRemoved(TaskStack stack, Task removedTask) {
        // To be implemented
    }

    /**** ViewPoolConsumer Implementation ****/

    @Override
    public TaskView createView(Context context) {
        return (TaskView) mInflater.inflate(R.layout.recents_task_view, this, false);
    }

    @Override
    public void prepareViewToEnterPool(TaskView tv) {
        final Task task = tv.getTask();

        // Report that this tasks's data is no longer being used
        Recents.getTaskLoader().unloadTaskData(task);

        // Detach the view from the hierarchy
        detachViewFromParent(tv);
        // Update the task views list after removing the task view
        updateTaskViewsList();

        // Reset the view properties and view state
        tv.resetViewProperties();
        tv.setFocusedState(false, false /* requestViewFocus */);
        tv.setClipViewInStack(false);
        if (mScreenPinningEnabled) {
            tv.hideActionButton(false /* fadeOut */, 0 /* duration */, false /* scaleDown */, null);
        }
    }

    @Override
    public void prepareViewToLeavePool(TaskView tv, Task task, boolean isNewView) {
        // Rebind the task and request that this task's data be filled into the TaskView
        tv.onTaskBound(task);

        // Load the task data
        Recents.getTaskLoader().loadTaskData(task);

        // If the doze trigger has already fired, then update the state for this task view
        tv.setNoUserInteractionState();

        // Find the index where this task should be placed in the stack
        int taskIndex = mStack.indexOfStackTask(task);
        int insertIndex = findTaskViewInsertIndex(task, taskIndex);

        // Add/attach the view to the hierarchy
        if (isNewView) {
            addView(tv, insertIndex);
        } else {
            attachViewToParent(tv, insertIndex, tv.getLayoutParams());
        }
        // Update the task views list after adding the new task view
        updateTaskViewsList();

        // Set the new state for this view, including the callbacks and view clipping
        tv.setCallbacks(this);
        tv.setTouchEnabled(true);
        tv.setClipViewInStack(true);
        if (mFocusedTask == task) {
            tv.setFocusedState(true, false /* requestViewFocus */);
            if (mStartTimerIndicator) {
                // The timer indicator couldn't be started before, so start it now
                tv.getHeaderView().startFocusTimerIndicator();
                mStartTimerIndicator = false;
            }
        }

        // Restore the action button visibility if it is the front most task view
        if (mScreenPinningEnabled && tv.getTask() == mStack.getStackFrontMostTask()) {
            tv.showActionButton(false /* fadeIn */, 0 /* fadeInDuration */);
        }
    }

    @Override
    public boolean hasPreferredData(TaskView tv, Task preferredData) {
        return (tv.getTask() == preferredData);
    }

    /**** TaskViewCallbacks Implementation ****/

    @Override
    public void onTaskViewClipStateChanged(TaskView tv) {
        clipTaskViews();
    }

    /**** TaskStackViewScroller.TaskStackViewScrollerCallbacks ****/

    @Override
    public void onScrollChanged(float prevScroll, float curScroll, TaskViewAnimation animation) {
        mUIDozeTrigger.poke();
        updateTaskViewsToLayoutOnNextFrame(animation);

        if (shouldShowHistoryButton() &&
                prevScroll > SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD &&
                curScroll <= SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD) {
            EventBus.getDefault().send(new ShowHistoryButtonEvent());
        } else if (prevScroll < HIDE_HISTORY_BUTTON_SCROLL_THRESHOLD &&
                curScroll >= HIDE_HISTORY_BUTTON_SCROLL_THRESHOLD) {
            EventBus.getDefault().send(new HideHistoryButtonEvent());
        }
    }

    /**** EventBus Events ****/

    public final void onBusEvent(PackagesChangedEvent event) {
        // Compute which components need to be removed
        HashSet<ComponentName> removedComponents = mStack.computeComponentsRemoved(
                event.packageName, event.userId);

        // For other tasks, just remove them directly if they no longer exist
        ArrayList<Task> tasks = mStack.getStackTasks();
        for (int i = tasks.size() - 1; i >= 0; i--) {
            final Task t = tasks.get(i);
            if (removedComponents.contains(t.key.getComponent())) {
                final TaskView tv = getChildViewForTask(t);
                if (tv != null) {
                    // For visible children, defer removing the task until after the animation
                    tv.dismissTask();
                } else {
                    // Otherwise, remove the task from the stack immediately
                    mStack.removeTask(t);
                }
            }
        }
    }

    public final void onBusEvent(LaunchTaskEvent event) {
        // Cancel any doze triggers once a task is launched
        mUIDozeTrigger.stopDozing();
    }

    public final void onBusEvent(LaunchTaskStartedEvent event) {
        mAnimationHelper.startLaunchTaskAnimation(event.taskView, event.screenPinningRequested,
                event.getAnimationTrigger());
    }

    public final void onBusEvent(DismissRecentsToHomeAnimationStarted event) {
        // Stop any scrolling
        mStackScroller.stopScroller();
        mStackScroller.stopBoundScrollAnimation();

        // Start the task animations
        mAnimationHelper.startExitToHomeAnimation(event.animated, event.getAnimationTrigger());

        // Dismiss the freeform workspace background
        int taskViewExitToHomeDuration = getResources().getInteger(
                R.integer.recents_task_exit_to_home_duration);
        animateFreeformWorkspaceBackgroundAlpha(0, taskViewExitToHomeDuration,
                mFastOutSlowInInterpolator);
    }

    public final void onBusEvent(DismissFocusedTaskViewEvent event) {
        if (mFocusedTask != null) {
            TaskView tv = getChildViewForTask(mFocusedTask);
            if (tv != null) {
                tv.dismissTask();
            }
            resetFocusedTask(mFocusedTask);
        }
    }

    public final void onBusEvent(final DismissTaskViewEvent event) {
        // For visible children, defer removing the task until after the animation
        mAnimationHelper.startDeleteTaskAnimation(event.task, event.taskView,
                event.getAnimationTrigger());
    }

    public final void onBusEvent(TaskViewDismissedEvent event) {
        removeTaskViewFromStack(event.taskView);
        EventBus.getDefault().send(new DeleteTaskDataEvent(event.task));
    }

    public final void onBusEvent(FocusNextTaskViewEvent event) {
        setRelativeFocusedTask(true, false /* stackTasksOnly */, true /* animated */, false,
                event.showTimerIndicator);
    }

    public final void onBusEvent(FocusPreviousTaskViewEvent event) {
        setRelativeFocusedTask(false, false /* stackTasksOnly */, true /* animated */);
    }

    public final void onBusEvent(UserInteractionEvent event) {
        // Poke the doze trigger on user interaction
        mUIDozeTrigger.poke();
        if (event.showTimerIndicator && mFocusedTask != null) {
            getChildViewForTask(mFocusedTask).getHeaderView().cancelFocusTimerIndicator();
        }
    }

    public final void onBusEvent(RecentsVisibilityChangedEvent event) {
        if (!event.visible) {
            reset();
        }
    }

    public final void onBusEvent(DragStartEvent event) {
        if (event.task.isFreeformTask()) {
            // Animate to the front of the stack
            mStackScroller.animateScroll(mStackScroller.getStackScroll(),
                    mLayoutAlgorithm.mInitialScrollP, null);
        }

        // Enlarge the dragged view slightly
        float finalScale = event.taskView.getScaleX() * DRAG_SCALE_FACTOR;
        mLayoutAlgorithm.getStackTransform(event.task, getScroller().getStackScroll(),
                mTmpTransform, null);
        mTmpTransform.scale = finalScale;
        updateTaskViewToTransform(event.taskView, mTmpTransform,
                new TaskViewAnimation(DRAG_SCALE_DURATION, mFastOutSlowInInterpolator));
    }

    public final void onBusEvent(DragStartInitializeDropTargetsEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.hasFreeformWorkspaceSupport()) {
            event.handler.registerDropTargetForCurrentDrag(mStackDropTarget);
            event.handler.registerDropTargetForCurrentDrag(mFreeformWorkspaceDropTarget);
        }
    }

    public final void onBusEvent(DragDropTargetChangedEvent event) {
        // TODO: Animate the freeform workspace background etc.
    }

    public final void onBusEvent(final DragEndEvent event) {
        // We don't handle drops on the dock regions
        if (event.dropTarget instanceof TaskStack.DockState) {
            return;
        }

        boolean isFreeformTask = event.task.isFreeformTask();
        boolean hasChangedStacks =
                (!isFreeformTask && event.dropTarget == mFreeformWorkspaceDropTarget) ||
                        (isFreeformTask && event.dropTarget == mStackDropTarget);

        if (hasChangedStacks) {
            // Move the task to the right position in the stack (ie. the front of the stack if
            // freeform or the front of the stack if fullscreen).  Note, we MUST move the tasks
            // before we update their stack ids, otherwise, the keys will have changed.
            if (event.dropTarget == mFreeformWorkspaceDropTarget) {
                mStack.moveTaskToStack(event.task, FREEFORM_WORKSPACE_STACK_ID);
            } else if (event.dropTarget == mStackDropTarget) {
                mStack.moveTaskToStack(event.task, FULLSCREEN_WORKSPACE_STACK_ID);
            }
            updateLayout(true);

            // Move the task to the new stack in the system after the animation completes
            event.addPostAnimationCallback(new Runnable() {
                @Override
                public void run() {
                    SystemServicesProxy ssp = Recents.getSystemServices();
                    ssp.moveTaskToStack(event.task.key.id, event.task.key.stackId);
                }
            });
        }

        // We translated the view but we need to animate it back from the current layout-space rect
        // to its final layout-space rect
        int x = (int) event.taskView.getTranslationX();
        int y = (int) event.taskView.getTranslationY();
        Rect taskViewRect = new Rect(event.taskView.getLeft(), event.taskView.getTop(),
                event.taskView.getRight(), event.taskView.getBottom());
        taskViewRect.offset(x, y);
        event.taskView.setTranslationX(0);
        event.taskView.setTranslationY(0);
        event.taskView.setLeftTopRightBottom(taskViewRect.left, taskViewRect.top,
                taskViewRect.right, taskViewRect.bottom);

        // Animate all the TaskViews back into position
        mLayoutAlgorithm.getStackTransform(event.task, getScroller().getStackScroll(),
                mTmpTransform, null);
        event.getAnimationTrigger().increment();
        updateTaskViewsToLayout(new TaskViewAnimation(DEFAULT_SYNC_STACK_DURATION,
                mFastOutSlowInInterpolator));
        updateTaskViewToTransform(event.taskView, mTmpTransform,
                new TaskViewAnimation(DEFAULT_SYNC_STACK_DURATION, mFastOutSlowInInterpolator,
                        event.getAnimationTrigger().decrementOnAnimationEnd()));
    }

    public final void onBusEvent(StackViewScrolledEvent event) {
        mLayoutAlgorithm.updateFocusStateOnScroll(event.yMovement);
    }

    public final void onBusEvent(IterateRecentsEvent event) {
        if (!mEnterAnimationComplete) {
            // Cancel the previous task's window transition before animating the focused state
            EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(null));
        }
    }

    public final void onBusEvent(EnterRecentsWindowAnimationCompletedEvent event) {
        mEnterAnimationComplete = true;

        if (mStack.getStackTaskCount() > 0) {
            // Start the task enter animations
            mAnimationHelper.startEnterAnimation(event.getAnimationTrigger());

            // Add a runnable to the post animation ref counter to clear all the views
            event.addPostAnimationCallback(new Runnable() {
                @Override
                public void run() {
                    // Start the dozer to trigger to trigger any UI that shows after a timeout
                    mUIDozeTrigger.startDozing();

                    // Update the focused state here -- since we only set the focused task without
                    // requesting view focus in onFirstLayout(), actually request view focus and
                    // animate the focused state if we are alt-tabbing now, after the window enter
                    // animation is completed
                    if (mFocusedTask != null) {
                        RecentsConfiguration config = Recents.getConfiguration();
                        RecentsActivityLaunchState launchState = config.getLaunchState();
                        setFocusedTask(mStack.indexOfStackTask(mFocusedTask),
                                false /* scrollToTask */, launchState.launchedWithAltTab);
                    }
                }
            });
        }
    }

    public final void onBusEvent(UpdateFreeformTaskViewVisibilityEvent event) {
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            if (task.isFreeformTask()) {
                tv.setVisibility(event.visible ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    public final void onBusEvent(ShowHistoryEvent event) {
        mAnimationHelper.startShowHistoryAnimation(event.getAnimationTrigger());
    }

    public final void onBusEvent(HideHistoryEvent event) {
        mAnimationHelper.startHideHistoryAnimation(event.getAnimationTrigger());
    }

    /**
     * Removes the task from the stack, and updates the focus to the next task in the stack if the
     * removed TaskView was focused.
     */
    private void removeTaskViewFromStack(TaskView tv) {
        Task task = tv.getTask();

        // Announce for accessibility
        tv.announceForAccessibility(getContext().getString(
                R.string.accessibility_recents_item_dismissed, tv.getTask().title));

        // Remove the task from the stack
        mStack.removeTask(task);
    }

    /**
     * Starts an alpha animation on the freeform workspace background.
     */
    private void animateFreeformWorkspaceBackgroundAlpha(int targetAlpha, int duration,
            Interpolator interpolator) {
        if (mFreeformWorkspaceBackground.getAlpha() == targetAlpha) {
            return;
        }

        Utilities.cancelAnimationWithoutCallbacks(mFreeformWorkspaceBackgroundAnimator);
        mFreeformWorkspaceBackgroundAnimator = ObjectAnimator.ofInt(mFreeformWorkspaceBackground,
                DRAWABLE_ALPHA, mFreeformWorkspaceBackground.getAlpha(), targetAlpha);
        mFreeformWorkspaceBackgroundAnimator.setDuration(duration);
        mFreeformWorkspaceBackgroundAnimator.setInterpolator(interpolator);
        mFreeformWorkspaceBackgroundAnimator.start();
    }

    /**
     * Returns the insert index for the task in the current set of task views.  If the given task
     * is already in the task view list, then this method returns the insert index assuming it
     * is first removed at the previous index.
     *
     * @param task the task we are finding the index for
     * @param taskIndex the index of the task in the stack
     */
    private int findTaskViewInsertIndex(Task task, int taskIndex) {
        if (taskIndex != -1) {
            List<TaskView> taskViews = getTaskViews();
            boolean foundTaskView = false;
            int taskViewCount = taskViews.size();
            for (int i = 0; i < taskViewCount; i++) {
                Task tvTask = taskViews.get(i).getTask();
                if (tvTask == task) {
                    foundTaskView = true;
                } else if (taskIndex < mStack.indexOfStackTask(tvTask)) {
                    if (foundTaskView) {
                        return i - 1;
                    } else {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * @return whether the history button should be visible
     */
    private boolean shouldShowHistoryButton() {
        return !mStack.getHistoricalTasks().isEmpty();
    }
}
