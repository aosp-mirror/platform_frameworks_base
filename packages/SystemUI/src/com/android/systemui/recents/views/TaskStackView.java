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
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.IntProperty;
import android.util.Log;
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
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.CancelEnterRecentsWindowAnimationEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;
import com.android.systemui.recents.events.activity.HideHistoryButtonEvent;
import com.android.systemui.recents.events.activity.HideHistoryEvent;
import com.android.systemui.recents.events.activity.IterateRecentsEvent;
import com.android.systemui.recents.events.activity.PackagesChangedEvent;
import com.android.systemui.recents.events.activity.ShowHistoryButtonEvent;
import com.android.systemui.recents.events.activity.ShowHistoryEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.events.ui.DismissTaskViewEvent;
import com.android.systemui.recents.events.ui.StackViewScrolledEvent;
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
import java.util.Collections;
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

    private final static String TAG = "TaskStackView";
    private final static boolean DEBUG = false;

    // The thresholds at which to show/hide the history button.
    private static final float SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD = 0.3f;
    private static final float HIDE_HISTORY_BUTTON_SCROLL_THRESHOLD = 0.3f;

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

    /** The TaskView callbacks */
    interface TaskStackViewCallbacks {
        public void onTaskViewClicked(TaskStackView stackView, TaskView tv, TaskStack stack, Task t,
                boolean lockToTask, Rect bounds, int destinationStack);
    }

    TaskStack mStack;
    TaskStackLayoutAlgorithm mLayoutAlgorithm;
    TaskStackViewScroller mStackScroller;
    TaskStackViewTouchHandler mTouchHandler;
    TaskStackViewCallbacks mCb;
    GradientDrawable mFreeformWorkspaceBackground;
    ObjectAnimator mFreeformWorkspaceBackgroundAnimator;
    ViewPool<TaskView, Task> mViewPool;
    ArrayList<TaskViewTransform> mCurrentTaskTransforms = new ArrayList<>();
    DozeTrigger mUIDozeTrigger;
    Task mFocusedTask;
    // Optimizations
    int mStackViewsAnimationDuration;
    int mTaskCornerRadiusPx;
    boolean mStackViewsDirty = true;
    boolean mStackViewsClipDirty = true;
    boolean mAwaitingFirstLayout = true;
    boolean mEnterAnimationComplete = false;
    boolean mStartEnterAnimationRequestedAfterLayout;
    ViewAnimation.TaskViewEnterContext mStartEnterAnimationContext;

    Rect mTaskStackBounds = new Rect();
    int[] mTmpVisibleRange = new int[2];
    Rect mTmpRect = new Rect();
    RectF mTmpTaskRect = new RectF();
    TaskViewTransform mTmpStackBackTransform = new TaskViewTransform();
    TaskViewTransform mTmpStackFrontTransform = new TaskViewTransform();
    HashMap<Task, TaskView> mTmpTaskViewMap = new HashMap<>();
    ArrayList<TaskView> mTaskViews = new ArrayList<>();
    List<TaskView> mImmutableTaskViews = new ArrayList<>();
    List<TaskView> mTmpTaskViews = new ArrayList<>();
    LayoutInflater mInflater;
    boolean mTouchExplorationEnabled;

    Interpolator mFastOutSlowInInterpolator;

    // A convenience update listener to request updating clipping of tasks
    private ValueAnimator.AnimatorUpdateListener mRequestUpdateClippingListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    requestUpdateStackViewsClip();
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
        mLayoutAlgorithm = new TaskStackLayoutAlgorithm(context, this);
        mStackScroller = new TaskStackViewScroller(context, mLayoutAlgorithm);
        mStackScroller.setCallbacks(this);
        mTouchHandler = new TaskStackViewTouchHandler(context, this, mStackScroller);
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
            setBackgroundColor(getContext().getColor(R.color.recents_freeform_workspace_bg_color));
        }
    }

    /** Sets the callbacks */
    void setCallbacks(TaskStackViewCallbacks cb) {
        mCb = cb;
    }

    @Override
    protected void onAttachedToWindow() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        mTouchExplorationEnabled = ssp.isTouchExplorationEnabled();
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
        mImmutableTaskViews = Collections.unmodifiableList(mTaskViews);
    }

    /** Gets the list of task views */
    List<TaskView> getTaskViews() {
        return mImmutableTaskViews;
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
        mStackViewsDirty = true;
        mStackViewsClipDirty = true;
        mAwaitingFirstLayout = true;
        mEnterAnimationComplete = false;
        if (mUIDozeTrigger != null) {
            mUIDozeTrigger.stopDozing();
            mUIDozeTrigger.resetTrigger();
        }
        mStackScroller.reset();
        mLayoutAlgorithm.reset();
        requestLayout();
    }

    /** Requests that the views be synchronized with the model */
    void requestSynchronizeStackViewsWithModel() {
        requestSynchronizeStackViewsWithModel(0);
    }
    void requestSynchronizeStackViewsWithModel(int duration) {
        if (!mStackViewsDirty) {
            invalidate();
            mStackViewsDirty = true;
        }
        if (mAwaitingFirstLayout) {
            // Skip the animation if we are awaiting first layout
            mStackViewsAnimationDuration = 0;
        } else {
            mStackViewsAnimationDuration = Math.max(mStackViewsAnimationDuration, duration);
        }
    }

    /** Requests that the views clipping be updated. */
    void requestUpdateStackViewsClip() {
        if (!mStackViewsClipDirty) {
            invalidate();
            mStackViewsClipDirty = true;
        }
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
            if (task.isFreeformTask()) {
                continue;
            }

            TaskViewTransform transform = mLayoutAlgorithm.getStackTransform(task, stackScroll,
                    taskTransforms.get(i), frontTransform);
            if (DEBUG) {
                Log.d(TAG, "updateStackTransform: " + i + ", " + transform.visible);
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

    /** Synchronizes the views with the model */
    boolean synchronizeStackViewsWithModel() {
        if (mStackViewsDirty) {
            // Get all the task transforms
            ArrayList<Task> tasks = mStack.getStackTasks();
            float stackScroll = mStackScroller.getStackScroll();
            int[] visibleStackRange = mTmpVisibleRange;
            boolean isValidVisibleStackRange = updateStackTransforms(mCurrentTaskTransforms, tasks,
                    stackScroll, visibleStackRange);
            boolean hasStackBackTransform = false;
            boolean hasStackFrontTransform = false;
            if (DEBUG) {
                Log.d(TAG, "visibleRange: " + visibleStackRange[0] + " to " + visibleStackRange[1]);
            }

            // Return all the invisible children to the pool
            mTmpTaskViewMap.clear();
            List<TaskView> taskViews = getTaskViews();
            int lastFocusedTaskIndex = -1;
            int taskViewCount = taskViews.size();
            for (int i = taskViewCount - 1; i >= 0; i--) {
                TaskView tv = taskViews.get(i);
                Task task = tv.getTask();
                int taskIndex = mStack.indexOfStackTask(task);
                if (task.isFreeformTask() ||
                        visibleStackRange[1] <= taskIndex && taskIndex <= visibleStackRange[0]) {
                    mTmpTaskViewMap.put(task, tv);
                } else {
                    if (mTouchExplorationEnabled) {
                        lastFocusedTaskIndex = taskIndex;
                        resetFocusedTask(task);
                    }
                    if (DEBUG) {
                        Log.d(TAG, "returning to pool: " + task.key);
                    }
                    mViewPool.returnViewToPool(tv);
                }
            }

            // Pick up all the freeform tasks
            int firstVisStackIndex = isValidVisibleStackRange ? visibleStackRange[0] : 0;
            for (int i = mStack.getStackTaskCount() - 1; i >= firstVisStackIndex; i--) {
                Task task = tasks.get(i);
                if (!task.isFreeformTask()) {
                    continue;
                }
                TaskViewTransform transform = mLayoutAlgorithm.getStackTransform(task, stackScroll,
                        mCurrentTaskTransforms.get(i), null);
                TaskView tv = mTmpTaskViewMap.get(task);
                if (tv == null) {
                    if (DEBUG) {
                        Log.d(TAG, "picking up from pool: " + task.key);
                    }
                    tv = mViewPool.pickUpViewFromPool(task, task);
                } else {
                    // Reattach it in the right z order
                    int taskIndex = mStack.indexOfStackTask(task);
                    int insertIndex = findTaskViewInsertIndex(task, taskIndex);
                    if (insertIndex != getTaskViews().indexOf(tv)){
                        detachViewFromParent(tv);
                        attachViewToParent(tv, insertIndex, tv.getLayoutParams());
                    }
                }

                // Animate the task into place
                tv.updateViewPropertiesToTaskTransform(transform, 0,
                        mStackViewsAnimationDuration, mFastOutSlowInInterpolator,
                        mRequestUpdateClippingListener);

                // Update the task views list after adding the new task view
                updateTaskViewsList();
            }

            // Pick up all the newly visible children and update all the existing children
            for (int i = visibleStackRange[0];
                    isValidVisibleStackRange && i >= visibleStackRange[1]; i--) {
                Task task = tasks.get(i);
                TaskViewTransform transform = mCurrentTaskTransforms.get(i);
                TaskView tv = mTmpTaskViewMap.get(task);

                if (tv == null) {
                    tv = mViewPool.pickUpViewFromPool(task, task);
                    if (mStackViewsAnimationDuration > 0) {
                        // For items in the list, put them in start animating them from the
                        // approriate ends of the list where they are expected to appear
                        if (Float.compare(transform.p, 0f) <= 0) {
                            if (!hasStackBackTransform) {
                                hasStackBackTransform = true;
                                mLayoutAlgorithm.getStackTransform(
                                        mLayoutAlgorithm.getStackBackTaskProgress(0f), 0f,
                                        mTmpStackBackTransform, null);
                            }
                            tv.updateViewPropertiesToTaskTransform(mTmpStackBackTransform, 0, 0,
                                    mFastOutSlowInInterpolator, mRequestUpdateClippingListener);
                        } else {
                            if (!hasStackFrontTransform) {
                                hasStackFrontTransform = true;
                                mLayoutAlgorithm.getStackTransform(
                                        mLayoutAlgorithm.getStackFrontTaskProgress(0f), 0f,
                                        mTmpStackFrontTransform, null);
                            }
                            tv.updateViewPropertiesToTaskTransform(mTmpStackFrontTransform, 0, 0,
                                    mFastOutSlowInInterpolator, mRequestUpdateClippingListener);
                        }
                    }
                }

                // Animate the task into place, the clip for stack tasks will be calculated in
                // clipTaskViews()
                tv.updateViewPropertiesToTaskTransform(transform,
                        tv.getViewBounds().getClipBottom(), mStackViewsAnimationDuration,
                        mFastOutSlowInInterpolator, mRequestUpdateClippingListener);
            }

            // Update the focus if the previous focused task was returned to the view pool
            if (lastFocusedTaskIndex != -1) {
                if (lastFocusedTaskIndex < visibleStackRange[1]) {
                    setFocusedTask(visibleStackRange[1], false /* animated */,
                            true /* requestViewFocus */);
                } else {
                    setFocusedTask(visibleStackRange[0], false /* animated */,
                            true /* requestViewFocus */);
                }
            }

            // Reset the request-synchronize params
            mStackViewsAnimationDuration = 0;
            mStackViewsDirty = false;
            mStackViewsClipDirty = true;
            return true;
        }
        return false;
    }

    /**
     * Updates the clip for each of the task views from back to front.
     */
    void clipTaskViews(boolean forceUpdate) {
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
            tv.getViewBounds().setClipBottom(clipBottom, forceUpdate);
            if (!config.useHardwareLayers) {
                tv.mThumbnailView.updateThumbnailVisibility(clipBottom - tv.getPaddingBottom());
            }
        }
        mStackViewsClipDirty = false;
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
    private boolean setFocusedTask(int taskIndex, boolean scrollToTask, final boolean animated) {
        return setFocusedTask(taskIndex, scrollToTask, animated, true);
    }

    /**
     * Sets the focused task to the provided (bounded taskIndex).
     *
     * @return whether or not the stack will scroll as a part of this focus change
     */
    private boolean setFocusedTask(int taskIndex, boolean scrollToTask, final boolean animated,
            final boolean requestViewFocus) {
        // Find the next task to focus
        int newFocusedTaskIndex = mStack.getStackTaskCount() > 0 ?
                Math.max(0, Math.min(mStack.getStackTaskCount() - 1, taskIndex)) : -1;
        final Task newFocusedTask = (newFocusedTaskIndex != -1) ?
                mStack.getStackTasks().get(newFocusedTaskIndex) : null;

        // Reset the last focused task state if changed
        if (mFocusedTask != null) {
            resetFocusedTask(mFocusedTask);
        }

        boolean willScroll = false;
        mFocusedTask = newFocusedTask;
        if (newFocusedTask != null) {
            Runnable focusTaskRunnable = new Runnable() {
                @Override
                public void run() {
                    TaskView tv = getChildViewForTask(newFocusedTask);
                    if (tv != null) {
                        tv.setFocusedState(true, animated, requestViewFocus);
                    }
                }
            };

            if (scrollToTask) {
                // TODO: Center the newly focused task view, only if not freeform
                RecentsDebugFlags debugFlags = Recents.getDebugFlags();
                float newScroll = mLayoutAlgorithm.getStackScrollForTask(newFocusedTask);
                if (!debugFlags.isFullscreenThumbnailsEnabled()) {
                    newScroll -= 0.5f;
                }
                newScroll = mStackScroller.getBoundedStackScroll(newScroll);
                if (Float.compare(newScroll, mStackScroller.getStackScroll()) != 0) {
                    mStackScroller.animateScroll(mStackScroller.getStackScroll(), newScroll,
                            focusTaskRunnable);
                    willScroll = true;

                    // Cancel any running enter animations at this point when we scroll as well
                    if (!mEnterAnimationComplete) {
                        final List<TaskView> taskViews = getTaskViews();
                        for (TaskView tv : taskViews) {
                            tv.cancelEnterRecentsAnimation();
                        }
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
     *                               happens
     */
    public void setRelativeFocusedTask(boolean forward, boolean stackTasksOnly, boolean animated,
                                       boolean cancelWindowAnimations) {
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
            boolean willScroll = setFocusedTask(newIndex, true, animated);
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
                tv.setFocusedState(false, false /* animated */, false /* requestViewFocus */);
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
            event.setContentDescription(frontMostTask.getTask().activityLabel);
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
        mStackScroller.computeScroll();
        // Synchronize the views
        synchronizeStackViewsWithModel();
        clipTaskViews(false /* forceUpdate */);
        // Notify accessibility
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
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

        // If this is the first layout, then scroll to the front of the stack and synchronize the
        // stack views immediately to load all the views
        if (mAwaitingFirstLayout) {
            mStackScroller.setStackScrollToInitialState();
            requestSynchronizeStackViewsWithModel();
            synchronizeStackViewsWithModel();
        }

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

        if (mAwaitingFirstLayout) {
            mAwaitingFirstLayout = false;
            onFirstLayout();
        }

        requestSynchronizeStackViewsWithModel();
        if (changed) {
            if (mStackScroller.isScrollOutOfBounds()) {
                mStackScroller.boundScroll();
            }
            synchronizeStackViewsWithModel();
            requestUpdateStackViewsClip();
            clipTaskViews(true /* forceUpdate */);
        }
    }

    /** Handler for the first layout. */
    void onFirstLayout() {
        int offscreenY = mLayoutAlgorithm.mStackRect.bottom;

        // Find the launch target task
        Task launchTargetTask = mStack.getLaunchTarget();
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();

        // Prepare the first view for its enter animation
        for (int i = taskViewCount - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            boolean hideTask = false;
            boolean occludesLaunchTarget = false;
            if (launchTargetTask != null) {
                occludesLaunchTarget = launchTargetTask.group.isTaskAboveTask(task,
                        launchTargetTask);
                hideTask = SystemServicesProxy.isFreeformStack(launchTargetTask.key.stackId) &&
                        SystemServicesProxy.isFreeformStack(task.key.stackId);
            }
            tv.prepareEnterRecentsAnimation(task.isLaunchTarget, hideTask, occludesLaunchTarget,
                    offscreenY);
        }

        // If the enter animation started already and we haven't completed a layout yet, do the
        // enter animation now
        if (mStartEnterAnimationRequestedAfterLayout) {
            startEnterRecentsAnimation(mStartEnterAnimationContext);
            mStartEnterAnimationRequestedAfterLayout = false;
            mStartEnterAnimationContext = null;
        }

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
            setFocusedTask(focusedTaskIndex, false /* scrollToTask */, false /* animated */,
                    false /* requestViewFocus */);
        }

        // Update the history button visibility
        if (mStackScroller.getStackScroll() < SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD) {
            EventBus.getDefault().send(new ShowHistoryButtonEvent());
        }

        // Start dozing
        mUIDozeTrigger.startDozing();
    }

    /** Requests this task stacks to start it's enter-recents animation */
    public void startEnterRecentsAnimation(ViewAnimation.TaskViewEnterContext ctx) {
        // If we are still waiting to layout, then just defer until then
        if (mAwaitingFirstLayout) {
            mStartEnterAnimationRequestedAfterLayout = true;
            mStartEnterAnimationContext = ctx;
            return;
        }

        if (mStack.getStackTaskCount() > 0) {
            // Find the launch target task
            Task launchTargetTask = mStack.getLaunchTarget();
            List<TaskView> taskViews = getTaskViews();
            int taskViewCount = taskViews.size();

            // Animate all the task views into view
            for (int i = taskViewCount - 1; i >= 0; i--) {
                TaskView tv = taskViews.get(i);
                Task task = tv.getTask();
                ctx.currentTaskTransform = new TaskViewTransform();
                ctx.currentStackViewIndex = i;
                ctx.currentStackViewCount = taskViewCount;
                ctx.currentTaskRect = mLayoutAlgorithm.mTaskRect;
                ctx.currentTaskOccludesLaunchTarget = (launchTargetTask != null) &&
                        launchTargetTask.group.isTaskAboveTask(task, launchTargetTask);
                ctx.updateListener = mRequestUpdateClippingListener;
                mLayoutAlgorithm.getStackTransform(task, mStackScroller.getStackScroll(),
                        ctx.currentTaskTransform, null);
                tv.startEnterRecentsAnimation(ctx);
            }

            // Add a runnable to the post animation ref counter to clear all the views
            ctx.postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    // Poke the dozer to restart the trigger after the animation completes
                    mUIDozeTrigger.poke();

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

    /** Requests this task stack to start it's exit-recents animation. */
    public void startExitToHomeAnimation(ViewAnimation.TaskViewExitContext ctx) {
        // Stop any scrolling
        mStackScroller.stopScroller();
        mStackScroller.stopBoundScrollAnimation();
        // Animate all the task views out of view
        ctx.offscreenTranslationY = mLayoutAlgorithm.mStackRect.bottom;
        // Dismiss the freeform workspace background
        int taskViewExitToHomeDuration = getResources().getInteger(
                R.integer.recents_task_exit_to_home_duration);
        animateFreeformWorkspaceBackgroundAlpha(0, taskViewExitToHomeDuration,
                mFastOutSlowInInterpolator);

        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            tv.startExitToHomeAnimation(ctx);
        }
    }

    /** Animates a task view in this stack as it launches. */
    public void startLaunchTaskAnimation(TaskView tv, Runnable r, boolean lockToTask) {
        Task launchTargetTask = tv.getTask();
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView t = taskViews.get(i);
            if (t == tv) {
                t.setClipViewInStack(false);
                t.startLaunchTaskAnimation(r, true, true, lockToTask);
            } else {
                boolean occludesLaunchTarget = launchTargetTask.group.isTaskAboveTask(t.getTask(),
                        launchTargetTask);
                t.startLaunchTaskAnimation(null, false, occludesLaunchTarget, lockToTask);
            }
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
        Task frontTask = mStack.getStackFrontMostTask();
        if (frontTask != null && frontTask.isFreeformTask()) {
            onTaskViewClicked(getChildViewForTask(frontTask), frontTask, false);
            return true;
        }
        return false;
    }

    /**** TaskStackCallbacks Implementation ****/

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

            // Animate all the tasks into place
            requestSynchronizeStackViewsWithModel(200);
        } else {
            // Remove the view associated with this task, we can't rely on updateTransforms
            // to work here because the task is no longer in the list
            TaskView tv = getChildViewForTask(removedTask);
            if (tv != null) {
                mViewPool.returnViewToPool(tv);
            }

            // Update the min/max scroll and animate other task views into their new positions
            updateLayout(true);

            // Animate all the tasks into place
            requestSynchronizeStackViewsWithModel(200);
        }

        // Update the new front most task
        if (newFrontMostTask != null) {
            TaskView frontTv = getChildViewForTask(newFrontMostTask);
            if (frontTv != null) {
                frontTv.onTaskBound(newFrontMostTask);
                frontTv.fadeInActionButton(getResources().getInteger(
                        R.integer.recents_task_enter_from_app_duration));
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
        Task task = tv.getTask();

        // Report that this tasks's data is no longer being used
        Recents.getTaskLoader().unloadTaskData(task);

        // Detach the view from the hierarchy
        detachViewFromParent(tv);
        // Update the task views list after removing the task view
        updateTaskViewsList();

        // Reset the view properties
        tv.resetViewProperties();

        // Reset the focused view state
        tv.setFocusedState(false, false /* animated */, false /* requestViewFocus */);

        // Reset the clip state of the task view
        tv.setClipViewInStack(false);
    }

    @Override
    public void prepareViewToLeavePool(TaskView tv, Task task, boolean isNewView) {
        // It is possible for a view to be returned to the view pool before it is laid out,
        // which means that we will need to relayout the view when it is first used next.
        boolean requiresRelayout = tv.getWidth() <= 0 && !isNewView;

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
            if (requiresRelayout) {
                tv.requestLayout();
            }
        }
        // Update the task views list after adding the new task view
        updateTaskViewsList();

        // Set the new state for this view, including the callbacks and view clipping
        tv.setCallbacks(this);
        tv.setTouchEnabled(true);
        tv.setClipViewInStack(true);
        if (mFocusedTask == task) {
            tv.setFocusedState(true, false /* animated */, false /* requestViewFocus */);
        }
    }

    @Override
    public boolean hasPreferredData(TaskView tv, Task preferredData) {
        return (tv.getTask() == preferredData);
    }

    /**** TaskViewCallbacks Implementation ****/

    @Override
    public void onTaskViewClicked(TaskView tv, Task task, boolean lockToTask) {
        // Cancel any doze triggers
        mUIDozeTrigger.stopDozing();

        if (mCb != null) {
            mCb.onTaskViewClicked(this, tv, mStack, task, lockToTask, null, INVALID_STACK_ID);
        }
    }

    @Override
    public void onTaskViewClipStateChanged(TaskView tv) {
        requestUpdateStackViewsClip();
    }

    /**** TaskStackViewScroller.TaskStackViewScrollerCallbacks ****/

    @Override
    public void onScrollChanged(float prevScroll, float curScroll) {
        mUIDozeTrigger.poke();
        requestSynchronizeStackViewsWithModel();
        postInvalidateOnAnimation();

        if (prevScroll > SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD &&
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
                    tv.startDeleteTaskAnimation(new Runnable() {
                        @Override
                        public void run() {
                            removeTaskViewFromStack(tv);
                        }
                    }, 0);
                } else {
                    // Otherwise, remove the task from the stack immediately
                    mStack.removeTask(t);
                }
            }
        }
    }

    public final void onBusEvent(DismissTaskViewEvent event) {
        removeTaskViewFromStack(event.taskView);
    }

    public final void onBusEvent(FocusNextTaskViewEvent event) {
        setRelativeFocusedTask(true, false /* stackTasksOnly */, true /* animated */);
    }

    public final void onBusEvent(FocusPreviousTaskViewEvent event) {
        setRelativeFocusedTask(false, false /* stackTasksOnly */, true /* animated */);
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

    public final void onBusEvent(UserInteractionEvent event) {
        // Poke the doze trigger on user interaction
        mUIDozeTrigger.poke();
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

        event.postAnimationTrigger.increment();
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
            event.postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    SystemServicesProxy ssp = Recents.getSystemServices();
                    ssp.moveTaskToStack(event.task.key.id, event.task.key.stackId);
                }
            });
        }
        event.taskView.animate()
                .withEndAction(event.postAnimationTrigger.decrementAsRunnable());

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

        // Animate the tack view back into position
        requestSynchronizeStackViewsWithModel(250);
    }

    public final void onBusEvent(StackViewScrolledEvent event) {
        mLayoutAlgorithm.updateFocusStateOnScroll(event.yMovement);
    }

    public final void onBusEvent(IterateRecentsEvent event) {
        mLayoutAlgorithm.animateFocusState(mLayoutAlgorithm.getDefaultFocusState());
    }

    public final void onBusEvent(EnterRecentsWindowAnimationCompletedEvent event) {
        mEnterAnimationComplete = true;
    }

    public final void onBusEvent(UpdateFreeformTaskViewVisibilityEvent event) {
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            if (SystemServicesProxy.isFreeformStack(task.key.stackId)) {
                tv.setVisibility(event.visible ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    public final void onBusEvent(ShowHistoryEvent event) {
        // The history view's animation will be deferred until all the stack task views are animated
        // away
        int historyTransitionDuration =
                getResources().getInteger(R.integer.recents_history_transition_duration);
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            tv.animate()
                    .alpha(0f)
                    .setDuration(historyTransitionDuration)
                    .setUpdateListener(null)
                    .setListener(null)
                    .withLayer()
                    .withEndAction(event.postHideStackAnimationTrigger.decrementAsRunnable())
                    .start();
            event.postHideStackAnimationTrigger.increment();
        }
    }

    public final void onBusEvent(HideHistoryEvent event) {
        // The stack task view animations will be deferred until the history view has been animated
        // away
        final int historyTransitionDuration =
                getResources().getInteger(R.integer.recents_history_transition_duration);
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            final TaskView tv = taskViews.get(i);
            event.postHideHistoryAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    tv.animate()
                            .alpha(1f)
                            .setDuration(historyTransitionDuration)
                            .setUpdateListener(null)
                            .setListener(null)
                            .withLayer()
                            .start();
                }
            });
        }
    }

    /**
     * Removes the task from the stack, and updates the focus to the next task in the stack if the
     * removed TaskView was focused.
     */
    private void removeTaskViewFromStack(TaskView tv) {
        Task task = tv.getTask();

        // Announce for accessibility
        tv.announceForAccessibility(getContext().getString(
                R.string.accessibility_recents_item_dismissed, tv.getTask().activityLabel));

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
}
