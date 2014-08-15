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

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.DozeTrigger;
import com.android.systemui.recents.model.RecentsPackageMonitor;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;


/* The visual representation of a task stack view */
public class TaskStackView extends FrameLayout implements TaskStack.TaskStackCallbacks,
        TaskView.TaskViewCallbacks, TaskStackViewScroller.TaskStackViewScrollerCallbacks,
        ViewPool.ViewPoolConsumer<TaskView, Task>, RecentsPackageMonitor.PackageCallbacks {

    /** The TaskView callbacks */
    interface TaskStackViewCallbacks {
        public void onTaskViewClicked(TaskStackView stackView, TaskView tv, TaskStack stack, Task t,
                                      boolean lockToTask);
        public void onTaskViewAppInfoClicked(Task t);
        public void onTaskViewDismissed(Task t);
        public void onAllTaskViewsDismissed();
        public void onTaskStackFilterTriggered();
        public void onTaskStackUnfilterTriggered();
    }

    RecentsConfiguration mConfig;

    TaskStack mStack;
    TaskStackViewLayoutAlgorithm mLayoutAlgorithm;
    TaskStackViewFilterAlgorithm mFilterAlgorithm;
    TaskStackViewScroller mStackScroller;
    TaskStackViewTouchHandler mTouchHandler;
    TaskStackViewCallbacks mCb;
    ViewPool<TaskView, Task> mViewPool;
    ArrayList<TaskViewTransform> mCurrentTaskTransforms = new ArrayList<TaskViewTransform>();
    DozeTrigger mUIDozeTrigger;
    DebugOverlayView mDebugOverlay;
    Rect mTaskStackBounds = new Rect();
    int mFocusedTaskIndex = -1;

    // Optimizations
    int mStackViewsAnimationDuration;
    boolean mStackViewsDirty = true;
    boolean mAwaitingFirstLayout = true;
    boolean mStartEnterAnimationRequestedAfterLayout;
    boolean mStartEnterAnimationCompleted;
    ViewAnimation.TaskViewEnterContext mStartEnterAnimationContext;
    int[] mTmpVisibleRange = new int[2];
    TaskViewTransform mTmpTransform = new TaskViewTransform();
    HashMap<Task, TaskView> mTmpTaskViewMap = new HashMap<Task, TaskView>();
    LayoutInflater mInflater;

    // A convenience runnable to return all views to the pool
    Runnable mReturnAllViewsToPoolRunnable = new Runnable() {
        @Override
        public void run() {
            int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                TaskView tv = (TaskView) getChildAt(i);
                mViewPool.returnViewToPool(tv);
                // Also hide the view since we don't need it anymore
                tv.setVisibility(View.INVISIBLE);
            }
        }
    };

    public TaskStackView(Context context, TaskStack stack) {
        super(context);
        mConfig = RecentsConfiguration.getInstance();
        mStack = stack;
        mStack.setCallbacks(this);
        mViewPool = new ViewPool<TaskView, Task>(context, this);
        mInflater = LayoutInflater.from(context);
        mLayoutAlgorithm = new TaskStackViewLayoutAlgorithm(mConfig);
        mFilterAlgorithm = new TaskStackViewFilterAlgorithm(mConfig, this, mViewPool);
        mStackScroller = new TaskStackViewScroller(context, mConfig, mLayoutAlgorithm);
        mStackScroller.setCallbacks(this);
        mTouchHandler = new TaskStackViewTouchHandler(context, this, mStackScroller);
        mUIDozeTrigger = new DozeTrigger(mConfig.taskBarDismissDozeDelaySeconds, new Runnable() {
            @Override
            public void run() {
                // Show the task bar dismiss buttons
                int childCount = getChildCount();
                for (int i = 0; i < childCount; i++) {
                    TaskView tv = (TaskView) getChildAt(i);
                    tv.startNoUserInteractionAnimation();
                }
            }
        });
    }

    /** Sets the callbacks */
    void setCallbacks(TaskStackViewCallbacks cb) {
        mCb = cb;
    }

    /** Sets the debug overlay */
    public void setDebugOverlay(DebugOverlayView overlay) {
        mDebugOverlay = overlay;
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

    /** Finds the child view given a specific task. */
    public TaskView getChildViewForTask(Task t) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView tv = (TaskView) getChildAt(i);
            if (tv.getTask() == t) {
                return tv;
            }
        }
        return null;
    }

    /** Returns the stack algorithm for this task stack. */
    public TaskStackViewLayoutAlgorithm getStackAlgorithm() {
        return mLayoutAlgorithm;
    }

    /**
     * Gets the stack transforms of a list of tasks, and returns the visible range of tasks.
     */
    private boolean updateStackTransforms(ArrayList<TaskViewTransform> taskTransforms,
                                       ArrayList<Task> tasks,
                                       float stackScroll,
                                       int[] visibleRangeOut,
                                       boolean boundTranslationsToRect) {
        // XXX: We should be intelligent about where to look for the visible stack range using the
        //      current stack scroll.
        // XXX: We should log extra cases like the ones below where we don't expect to hit very often
        // XXX: Print out approximately how many indices we have to go through to find the first visible transform

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
        TaskViewTransform prevTransform = null;
        for (int i = taskCount - 1; i >= 0; i--) {
            TaskViewTransform transform = mLayoutAlgorithm.getStackTransform(tasks.get(i),
                    stackScroll, taskTransforms.get(i), prevTransform);
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

            if (boundTranslationsToRect) {
                transform.translationY = Math.min(transform.translationY,
                        mLayoutAlgorithm.mViewRect.bottom);
            }
            prevTransform = transform;
        }
        if (visibleRangeOut != null) {
            visibleRangeOut[0] = frontMostVisibleIndex;
            visibleRangeOut[1] = backMostVisibleIndex;
        }
        return frontMostVisibleIndex != -1 && backMostVisibleIndex != -1;
    }

    /**
     * Gets the stack transforms of a list of tasks, and returns the visible range of tasks. This
     * call is less optimal than calling updateStackTransforms directly.
     */
    private ArrayList<TaskViewTransform> getStackTransforms(ArrayList<Task> tasks,
                                                            float stackScroll,
                                                            int[] visibleRangeOut,
                                                            boolean boundTranslationsToRect) {
        ArrayList<TaskViewTransform> taskTransforms = new ArrayList<TaskViewTransform>();
        updateStackTransforms(taskTransforms, tasks, stackScroll, visibleRangeOut,
                boundTranslationsToRect);
        return taskTransforms;
    }

    /** Synchronizes the views with the model */
    boolean synchronizeStackViewsWithModel() {
        if (mStackViewsDirty) {
            // Get all the task transforms
            ArrayList<Task> tasks = mStack.getTasks();
            float stackScroll = mStackScroller.getStackScroll();
            int[] visibleRange = mTmpVisibleRange;
            boolean isValidVisibleRange = updateStackTransforms(mCurrentTaskTransforms, tasks,
                    stackScroll, visibleRange, false);
            if (mDebugOverlay != null) {
                mDebugOverlay.setText("vis[" + visibleRange[1] + "-" + visibleRange[0] + "]");
            }

            // Return all the invisible children to the pool
            mTmpTaskViewMap.clear();
            int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                TaskView tv = (TaskView) getChildAt(i);
                Task task = tv.getTask();
                int taskIndex = mStack.indexOfTask(task);
                if (visibleRange[1] <= taskIndex && taskIndex <= visibleRange[0]) {
                    mTmpTaskViewMap.put(task, tv);
                } else {
                    mViewPool.returnViewToPool(tv);
                }
            }

            // Pick up all the newly visible children and update all the existing children
            for (int i = visibleRange[0]; isValidVisibleRange && i >= visibleRange[1]; i--) {
                Task task = tasks.get(i);
                TaskViewTransform transform = mCurrentTaskTransforms.get(i);
                TaskView tv = mTmpTaskViewMap.get(task);
                int taskIndex = mStack.indexOfTask(task);

                if (tv == null) {
                    tv = mViewPool.pickUpViewFromPool(task, task);

                    if (mStackViewsAnimationDuration > 0) {
                        // For items in the list, put them in start animating them from the
                        // approriate ends of the list where they are expected to appear
                        if (Float.compare(transform.p, 0f) <= 0) {
                            mLayoutAlgorithm.getStackTransform(0f, 0f, mTmpTransform, null);
                        } else {
                            mLayoutAlgorithm.getStackTransform(1f, 0f, mTmpTransform, null);
                        }
                        tv.updateViewPropertiesToTaskTransform(mTmpTransform, 0);
                    }
                }

                // Animate the task into place
                tv.updateViewPropertiesToTaskTransform(mCurrentTaskTransforms.get(taskIndex),
                        mStackViewsAnimationDuration);
            }

            // Reset the request-synchronize params
            mStackViewsAnimationDuration = 0;
            mStackViewsDirty = false;
            return true;
        }
        return false;
    }

    /** Updates the clip for each of the task views. */
    void clipTaskViews() {
        // Update the clip on each task child
        if (Constants.DebugFlags.App.EnableTaskStackClipping) {
            int childCount = getChildCount();
            for (int i = 0; i < childCount - 1; i++) {
                TaskView tv = (TaskView) getChildAt(i);
                TaskView nextTv = null;
                TaskView tmpTv = null;
                int clipBottom = 0;
                if (tv.shouldClipViewInStack()) {
                    // Find the next view to clip against
                    int nextIndex = i;
                    while (nextIndex < getChildCount()) {
                        tmpTv = (TaskView) getChildAt(++nextIndex);
                        if (tmpTv != null && tmpTv.shouldClipViewInStack()) {
                            nextTv = tmpTv;
                            break;
                        }
                    }

                    // Clip against the next view, this is just an approximation since we are
                    // stacked and we can make assumptions about the visibility of the this
                    // task relative to the ones in front of it.
                    if (nextTv != null) {
                        // We can reuse the current task transforms to find the task rects
                        TaskViewTransform transform = mCurrentTaskTransforms.get(mStack.indexOfTask(tv.getTask()));
                        TaskViewTransform nextTransform = mCurrentTaskTransforms.get(mStack.indexOfTask(nextTv.getTask()));
                        clipBottom = transform.rect.bottom - nextTransform.rect.top;
                    }
                }
                tv.getViewBounds().setClipBottom(clipBottom);
            }
            if (getChildCount() > 0) {
                // The front most task should never be clipped
                TaskView tv = (TaskView) getChildAt(getChildCount() - 1);
                tv.getViewBounds().setClipBottom(0);
            }
        }
    }

    /** The stack insets to apply to the stack contents */
    public void setStackInsetRect(Rect r) {
        mTaskStackBounds.set(r);
    }

    /** Updates the min and max virtual scroll bounds */
    void updateMinMaxScroll(boolean boundScrollToNewMinMax, boolean launchedWithAltTab,
            boolean launchedFromHome) {
        // Compute the min and max scroll values
        mLayoutAlgorithm.computeMinMaxScroll(mStack.getTasks(), launchedWithAltTab, launchedFromHome);

        // Debug logging
        if (boundScrollToNewMinMax) {
            mStackScroller.boundScroll();
        }
    }

    /** Returns the scroller. */
    public TaskStackViewScroller getScroller() {
        return mStackScroller;
    }

    /** Focuses the task at the specified index in the stack */
    void focusTask(int taskIndex, boolean scrollToNewPosition) {
        // Return early if the task is already focused
        if (taskIndex == mFocusedTaskIndex) return;

        if (0 <= taskIndex && taskIndex < mStack.getTaskCount()) {
            mFocusedTaskIndex = taskIndex;

            // Focus the view if possible, otherwise, focus the view after we scroll into position
            Task t = mStack.getTasks().get(taskIndex);
            TaskView tv = getChildViewForTask(t);
            Runnable postScrollRunnable = null;
            if (tv != null) {
                tv.setFocusedTask();
            } else {
                postScrollRunnable = new Runnable() {
                    @Override
                    public void run() {
                        Task t = mStack.getTasks().get(mFocusedTaskIndex);
                        TaskView tv = getChildViewForTask(t);
                        if (tv != null) {
                            tv.setFocusedTask();
                        }
                    }
                };
            }

            // Scroll the view into position (just center it in the curve)
            if (scrollToNewPosition) {
                float newScroll = mLayoutAlgorithm.getStackScrollForTask(t) - 0.5f;
                newScroll = mStackScroller.getBoundedStackScroll(newScroll);
                mStackScroller.animateScroll(mStackScroller.getStackScroll(), newScroll, postScrollRunnable);
            } else {
                if (postScrollRunnable != null) {
                    postScrollRunnable.run();
                }
            }

        }
    }

    /** Focuses the next task in the stack */
    void focusNextTask(boolean forward) {
        // Find the next index to focus
        int numTasks = mStack.getTaskCount();
        if (numTasks == 0) return;

        int nextFocusIndex = numTasks - 1;
        if (0 <= mFocusedTaskIndex && mFocusedTaskIndex < numTasks) {
            nextFocusIndex = Math.max(0, Math.min(numTasks - 1,
                    mFocusedTaskIndex + (forward ? -1 : 1)));
        }
        focusTask(nextFocusIndex, true);
    }

    /** Dismisses the focused task. */
    public void dismissFocusedTask() {
        // Return early if there is no focused task index
        if (mFocusedTaskIndex < 0) return;

        Task t = mStack.getTasks().get(mFocusedTaskIndex);
        TaskView tv = getChildViewForTask(t);
        tv.dismissTask();
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
    public void computeScroll() {
        mStackScroller.computeScroll();
        // Synchronize the views
        synchronizeStackViewsWithModel();
        clipTaskViews();
    }

    /** Computes the stack and task rects */
    public void computeRects(int windowWidth, int windowHeight, Rect taskStackBounds,
            boolean launchedWithAltTab, boolean launchedFromHome) {
        // Compute the rects in the stack algorithm
        mLayoutAlgorithm.computeRects(windowWidth, windowHeight, taskStackBounds);

        // Update the scroll bounds
        updateMinMaxScroll(false, launchedWithAltTab, launchedFromHome);
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
        Rect taskStackBounds = new Rect(mTaskStackBounds);
        taskStackBounds.bottom -= mConfig.systemInsets.bottom;
        computeRects(width, height, taskStackBounds, mConfig.launchedWithAltTab,
                mConfig.launchedFromHome);

        // If this is the first layout, then scroll to the front of the stack and synchronize the
        // stack views immediately to load all the views
        if (mAwaitingFirstLayout) {
            mStackScroller.setStackScrollToInitialState();
            requestSynchronizeStackViewsWithModel();
            synchronizeStackViewsWithModel();
        }

        // Measure each of the TaskViews
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView tv = (TaskView) getChildAt(i);
            if (tv.isFullScreenView()) {
                tv.measure(widthMeasureSpec, heightMeasureSpec);
            } else {
                tv.measure(
                    MeasureSpec.makeMeasureSpec(mLayoutAlgorithm.mTaskRect.width(),
                            MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(mLayoutAlgorithm.mTaskRect.height() +
                            tv.getMaxFooterHeight(), MeasureSpec.EXACTLY));
            }
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
        // Layout each of the children
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView tv = (TaskView) getChildAt(i);
            if (tv.isFullScreenView()) {
                tv.layout(left, top, left + tv.getMeasuredWidth(), top + tv.getMeasuredHeight());
            } else {
                tv.layout(mLayoutAlgorithm.mTaskRect.left, mLayoutAlgorithm.mTaskRect.top,
                        mLayoutAlgorithm.mTaskRect.right, mLayoutAlgorithm.mTaskRect.bottom +
                                tv.getMaxFooterHeight());
            }
        }

        if (mAwaitingFirstLayout) {
            mAwaitingFirstLayout = false;
            onFirstLayout();
        }
    }

    /** Handler for the first layout. */
    void onFirstLayout() {
        int offscreenY = mLayoutAlgorithm.mViewRect.bottom -
                (mLayoutAlgorithm.mTaskRect.top - mLayoutAlgorithm.mViewRect.top);

        // Find the launch target task
        Task launchTargetTask = null;
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            TaskView tv = (TaskView) getChildAt(i);
            Task task = tv.getTask();
            if (task.isLaunchTarget) {
                launchTargetTask = task;
                break;
            }
        }

        // Prepare the first view for its enter animation
        for (int i = childCount - 1; i >= 0; i--) {
            TaskView tv = (TaskView) getChildAt(i);
            Task task = tv.getTask();
            boolean occludesLaunchTarget = (launchTargetTask != null) &&
                    launchTargetTask.group.isTaskAboveTask(task, launchTargetTask);
            tv.prepareEnterRecentsAnimation(task.isLaunchTarget, occludesLaunchTarget, offscreenY);
        }

        // If the enter animation started already and we haven't completed a layout yet, do the
        // enter animation now
        if (mStartEnterAnimationRequestedAfterLayout) {
            startEnterRecentsAnimation(mStartEnterAnimationContext);
            mStartEnterAnimationRequestedAfterLayout = false;
            mStartEnterAnimationContext = null;
        }

        // When Alt-Tabbing, we scroll to and focus the previous task
        if (mConfig.launchedWithAltTab) {
            if (mConfig.launchedFromHome) {
                focusTask(Math.max(0, mStack.getTaskCount() - 1), false);
            } else {
                focusTask(Math.max(0, mStack.getTaskCount() - 2), false);
            }
        }
    }

    /** Requests this task stacks to start it's enter-recents animation */
    public void startEnterRecentsAnimation(ViewAnimation.TaskViewEnterContext ctx) {
        // If we are still waiting to layout, then just defer until then
        if (mAwaitingFirstLayout) {
            mStartEnterAnimationRequestedAfterLayout = true;
            mStartEnterAnimationContext = ctx;
            return;
        }

        if (mStack.getTaskCount() > 0) {
            // Find the launch target task
            Task launchTargetTask = null;
            int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                TaskView tv = (TaskView) getChildAt(i);
                Task task = tv.getTask();
                if (task.isLaunchTarget) {
                    launchTargetTask = task;
                    break;
                }
            }

            // Animate all the task views into view
            for (int i = childCount - 1; i >= 0; i--) {
                TaskView tv = (TaskView) getChildAt(i);
                Task task = tv.getTask();
                ctx.currentTaskTransform = new TaskViewTransform();
                ctx.currentStackViewIndex = i;
                ctx.currentStackViewCount = childCount;
                ctx.currentTaskRect = mLayoutAlgorithm.mTaskRect;
                ctx.currentTaskOccludesLaunchTarget = (launchTargetTask != null) &&
                        launchTargetTask.group.isTaskAboveTask(task, launchTargetTask);
                mLayoutAlgorithm.getStackTransform(task, mStackScroller.getStackScroll(), ctx.currentTaskTransform, null);
                tv.startEnterRecentsAnimation(ctx);
            }

            // Add a runnable to the post animation ref counter to clear all the views
            ctx.postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    mStartEnterAnimationCompleted = true;
                    // Start dozing
                    mUIDozeTrigger.startDozing();
                }
            });
        }
    }

    /** Requests this task stacks to start it's exit-recents animation. */
    public void startExitToHomeAnimation(ViewAnimation.TaskViewExitContext ctx) {
        // Stop any scrolling
        mStackScroller.stopScroller();
        mStackScroller.stopBoundScrollAnimation();
        // Animate all the task views out of view
        ctx.offscreenTranslationY = mLayoutAlgorithm.mViewRect.bottom -
                (mLayoutAlgorithm.mTaskRect.top - mLayoutAlgorithm.mViewRect.top);
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView tv = (TaskView) getChildAt(i);
            tv.startExitToHomeAnimation(ctx);
        }

        // Add a runnable to the post animation ref counter to clear all the views
        ctx.postAnimationTrigger.addLastDecrementRunnable(mReturnAllViewsToPoolRunnable);
    }

    /** Animates a task view in this stack as it launches. */
    public void startLaunchTaskAnimation(TaskView tv, final Runnable r) {
        Task launchTargetTask = tv.getTask();
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView t = (TaskView) getChildAt(i);
            if (t == tv) {
                t.startLaunchTaskAnimation(r, true, true);
            } else {
                boolean occludesLaunchTarget = launchTargetTask.group.isTaskAboveTask(t.getTask(),
                        launchTargetTask);
                t.startLaunchTaskAnimation(null, false, occludesLaunchTarget);
            }
        }
    }

    public boolean isTransformedTouchPointInView(float x, float y, View child) {
        return isTransformedTouchPointInView(x, y, child, null);
    }

    /** Pokes the dozer on user interaction. */
    void onUserInteraction() {
        // Poke the doze trigger if it is dozing
        mUIDozeTrigger.poke();
    }

    /**** TaskStackCallbacks Implementation ****/

    @Override
    public void onStackTaskAdded(TaskStack stack, Task t) {
        requestSynchronizeStackViewsWithModel();
    }

    @Override
    public void onStackTaskRemoved(TaskStack stack, Task removedTask, Task newFrontMostTask) {
        // Remove the view associated with this task, we can't rely on updateTransforms
        // to work here because the task is no longer in the list
        TaskView tv = getChildViewForTask(removedTask);
        if (tv != null) {
            mViewPool.returnViewToPool(tv);
        }

        // Notify the callback that we've removed the task and it can clean up after it
        mCb.onTaskViewDismissed(removedTask);

        // Get the stack scroll of the task to anchor to (since we are removing something, the front
        // most task will be our anchor task)
        Task anchorTask = null;
        float prevAnchorTaskScroll = 0;
        boolean pullStackForward = stack.getTaskCount() > 0;
        if (pullStackForward) {
            anchorTask = mStack.getFrontMostTask();
            prevAnchorTaskScroll = mLayoutAlgorithm.getStackScrollForTask(anchorTask);
        }

        // Update the min/max scroll and animate other task views into their new positions
        updateMinMaxScroll(true, mConfig.launchedWithAltTab, mConfig.launchedFromHome);

        // Offset the stack by as much as the anchor task would otherwise move back
        if (pullStackForward) {
            float anchorTaskScroll = mLayoutAlgorithm.getStackScrollForTask(anchorTask);
            mStackScroller.setStackScroll(mStackScroller.getStackScroll() + (anchorTaskScroll
                    - prevAnchorTaskScroll));
            mStackScroller.boundScroll();
        }

        // Animate all the tasks into place
        requestSynchronizeStackViewsWithModel(200);

        // Update the new front most task
        if (newFrontMostTask != null) {
            TaskView frontTv = getChildViewForTask(newFrontMostTask);
            if (frontTv != null) {
                frontTv.onTaskBound(newFrontMostTask);
            }
        }

        // If there are no remaining tasks, then either unfilter the current stack, or just close
        // the activity if there are no filtered stacks
        if (mStack.getTaskCount() == 0) {
            boolean shouldFinishActivity = true;
            if (mStack.hasFilteredTasks()) {
                mStack.unfilterTasks();
                shouldFinishActivity = (mStack.getTaskCount() == 0);
            }
            if (shouldFinishActivity) {
                mCb.onAllTaskViewsDismissed();
            }
        }
    }

    @Override
    public void onStackFiltered(TaskStack newStack, final ArrayList<Task> curTasks,
                                Task filteredTask) {
        /*
        // Stash the scroll and filtered task for us to restore to when we unfilter
        mStashedScroll = getStackScroll();

        // Calculate the current task transforms
        ArrayList<TaskViewTransform> curTaskTransforms =
                getStackTransforms(curTasks, getStackScroll(), null, true);

        // Update the task offsets
        mLayoutAlgorithm.updateTaskOffsets(mStack.getTasks());

        // Scroll the item to the top of the stack (sans-peek) rect so that we can see it better
        updateMinMaxScroll(false);
        float overlapHeight = mLayoutAlgorithm.getTaskOverlapHeight();
        setStackScrollRaw((int) (newStack.indexOfTask(filteredTask) * overlapHeight));
        boundScrollRaw();

        // Compute the transforms of the items in the new stack after setting the new scroll
        final ArrayList<Task> tasks = mStack.getTasks();
        final ArrayList<TaskViewTransform> taskTransforms =
                getStackTransforms(mStack.getTasks(), getStackScroll(), null, true);

        // Animate
        mFilterAlgorithm.startFilteringAnimation(curTasks, curTaskTransforms, tasks, taskTransforms);

        // Notify any callbacks
        mCb.onTaskStackFilterTriggered();
        */
    }

    @Override
    public void onStackUnfiltered(TaskStack newStack, final ArrayList<Task> curTasks) {
        /*
        // Calculate the current task transforms
        final ArrayList<TaskViewTransform> curTaskTransforms =
                getStackTransforms(curTasks, getStackScroll(), null, true);

        // Update the task offsets
        mLayoutAlgorithm.updateTaskOffsets(mStack.getTasks());

        // Restore the stashed scroll
        updateMinMaxScroll(false);
        setStackScrollRaw(mStashedScroll);
        boundScrollRaw();

        // Compute the transforms of the items in the new stack after restoring the stashed scroll
        final ArrayList<Task> tasks = mStack.getTasks();
        final ArrayList<TaskViewTransform> taskTransforms =
                getStackTransforms(tasks, getStackScroll(), null, true);

        // Animate
        mFilterAlgorithm.startFilteringAnimation(curTasks, curTaskTransforms, tasks, taskTransforms);

        // Clear the saved vars
        mStashedScroll = 0;

        // Notify any callbacks
        mCb.onTaskStackUnfilterTriggered();
        */
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
        RecentsTaskLoader.getInstance().unloadTaskData(task);

        // Detach the view from the hierarchy
        detachViewFromParent(tv);

        // Reset the view properties
        tv.resetViewProperties();
    }

    @Override
    public void prepareViewToLeavePool(TaskView tv, Task task, boolean isNewView) {
        // Rebind the task and request that this task's data be filled into the TaskView
        tv.onTaskBound(task);

        // Mark the launch task as fullscreen
        if (Constants.DebugFlags.App.EnableScreenshotAppTransition && mAwaitingFirstLayout) {
            if (task.isLaunchTarget) {
                tv.setIsFullScreen(true);
            }
        }

        // Load the task data
        RecentsTaskLoader.getInstance().loadTaskData(task);

        // Sanity check, the task view should always be clipping against the stack at this point,
        // but just in case, re-enable it here
        tv.setClipViewInStack(true);

        // If the doze trigger has already fired, then update the state for this task view
        if (mUIDozeTrigger.hasTriggered()) {
            tv.setNoUserInteractionState();
        }

        // If we've finished the start animation, then ensure we always enable the focus animations
        if (mStartEnterAnimationCompleted) {
            tv.enableFocusAnimations();
        }

        // Find the index where this task should be placed in the stack
        int insertIndex = -1;
        int taskIndex = mStack.indexOfTask(task);
        if (taskIndex != -1) {
            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                Task tvTask = ((TaskView) getChildAt(i)).getTask();
                if (taskIndex < mStack.indexOfTask(tvTask)) {
                    insertIndex = i;
                    break;
                }
            }
        }

        // Add/attach the view to the hierarchy
        if (isNewView) {
            addView(tv, insertIndex);

            // Set the callbacks and listeners for this new view
            tv.setTouchEnabled(true);
            tv.setCallbacks(this);
        } else {
            attachViewToParent(tv, insertIndex, tv.getLayoutParams());
        }
    }

    @Override
    public boolean hasPreferredData(TaskView tv, Task preferredData) {
        return (tv.getTask() == preferredData);
    }

    /**** TaskViewCallbacks Implementation ****/

    @Override
    public void onTaskViewAppIconClicked(TaskView tv) {
        if (Constants.DebugFlags.App.EnableTaskFiltering) {
            if (mStack.hasFilteredTasks()) {
                mStack.unfilterTasks();
            } else {
                mStack.filterTasks(tv.getTask());
            }
        }
    }

    @Override
    public void onTaskViewAppInfoClicked(TaskView tv) {
        if (mCb != null) {
            mCb.onTaskViewAppInfoClicked(tv.getTask());
        }
    }

    @Override
    public void onTaskViewClicked(TaskView tv, Task task, boolean lockToTask) {
        // Cancel any doze triggers
        mUIDozeTrigger.stopDozing();

        if (mCb != null) {
            mCb.onTaskViewClicked(this, tv, mStack, task, lockToTask);
        }
    }

    @Override
    public void onTaskViewDismissed(TaskView tv) {
        Task task = tv.getTask();
        int taskIndex = mStack.indexOfTask(task);
        boolean taskWasFocused = tv.isFocusedTask();
        // Announce for accessibility
        tv.announceForAccessibility(getContext().getString(R.string.accessibility_recents_item_dismissed,
                tv.getTask().activityLabel));
        // Remove the task from the view
        mStack.removeTask(task);
        // If the dismissed task was focused, then we should focus the next task in front
        if (taskWasFocused) {
            ArrayList<Task> tasks = mStack.getTasks();
            int nextTaskIndex = Math.min(tasks.size() - 1, taskIndex);
            if (nextTaskIndex >= 0) {
                Task nextTask = tasks.get(nextTaskIndex);
                TaskView nextTv = getChildViewForTask(nextTask);
                nextTv.setFocusedTask();
            }
        }
    }

    @Override
    public void onTaskViewClipStateChanged(TaskView tv) {
        invalidate();
    }

    @Override
    public void onTaskViewFullScreenTransitionCompleted() {
        requestSynchronizeStackViewsWithModel();
    }

    @Override
    public void onTaskViewFocusChanged(TaskView tv, boolean focused) {
        if (focused) {
            mFocusedTaskIndex = mStack.indexOfTask(tv.getTask());
        }
    }

    /**** TaskStackViewScroller.TaskStackViewScrollerCallbacks ****/

    @Override
    public void onScrollChanged(float p) {
        mUIDozeTrigger.poke();
        requestSynchronizeStackViewsWithModel();
        postInvalidateOnAnimation();
    }

    /**** RecentsPackageMonitor.PackageCallbacks Implementation ****/

    @Override
    public void onComponentRemoved(HashSet<ComponentName> cns) {
        // For other tasks, just remove them directly if they no longer exist
        ArrayList<Task> tasks = mStack.getTasks();
        for (int i = tasks.size() - 1; i >= 0; i--) {
            final Task t = tasks.get(i);
            if (cns.contains(t.key.baseIntent.getComponent())) {
                TaskView tv = getChildViewForTask(t);
                if (tv != null) {
                    // For visible children, defer removing the task until after the animation
                    tv.startDeleteTaskAnimation(new Runnable() {
                        @Override
                        public void run() {
                            mStack.removeTask(t);
                        }
                    });
                } else {
                    // Otherwise, remove the task from the stack immediately
                    mStack.removeTask(t);
                }
            }
        }
    }
}
