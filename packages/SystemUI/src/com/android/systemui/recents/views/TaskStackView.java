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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.OverScroller;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.Console;
import com.android.systemui.recents.misc.DozeTrigger;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.RecentsPackageMonitor;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;


/* The visual representation of a task stack view */
public class TaskStackView extends FrameLayout implements TaskStack.TaskStackCallbacks,
        TaskView.TaskViewCallbacks, ViewPool.ViewPoolConsumer<TaskView, Task>,
        View.OnClickListener, RecentsPackageMonitor.PackageCallbacks {

    /** The TaskView callbacks */
    interface TaskStackViewCallbacks {
        public void onTaskViewClicked(TaskStackView stackView, TaskView tv, TaskStack stack, Task t);
        public void onTaskViewAppInfoClicked(Task t);
        public void onTaskViewDismissed(Task t);
        public void onAllTaskViewsDismissed();
        public void onTaskStackFilterTriggered();
        public void onTaskStackUnfilterTriggered();
    }

    RecentsConfiguration mConfig;

    TaskStack mStack;
    TaskStackViewLayoutAlgorithm mStackAlgorithm;
    TaskStackViewFilterAlgorithm mFilterAlgorithm;
    TaskStackViewTouchHandler mTouchHandler;
    TaskStackViewCallbacks mCb;
    ViewPool<TaskView, Task> mViewPool;
    ArrayList<TaskViewTransform> mCurrentTaskTransforms = new ArrayList<TaskViewTransform>();
    DozeTrigger mUIDozeTrigger;

    // The virtual stack scroll that we use for the card layout
    int mStackScroll;
    int mMinScroll;
    int mMaxScroll;
    int mStashedScroll;
    int mFocusedTaskIndex = -1;
    OverScroller mScroller;
    ObjectAnimator mScrollAnimator;

    // Optimizations
    ReferenceCountedTrigger mHwLayersTrigger;
    int mStackViewsAnimationDuration;
    boolean mStackViewsDirty = true;
    boolean mAwaitingFirstLayout = true;
    boolean mStartEnterAnimationRequestedAfterLayout;
    ViewAnimation.TaskViewEnterContext mStartEnterAnimationContext;
    int[] mTmpVisibleRange = new int[2];
    Rect mTmpRect = new Rect();
    Rect mTmpRect2 = new Rect();
    LayoutInflater mInflater;

    Runnable mReturnAllViewsToPoolRunnable = new Runnable() {
        @Override
        public void run() {
            int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                mViewPool.returnViewToPool((TaskView) getChildAt(i));
            }
        }
    };

    public TaskStackView(Context context, TaskStack stack) {
        super(context);
        mConfig = RecentsConfiguration.getInstance();
        mStack = stack;
        mStack.setCallbacks(this);
        mScroller = new OverScroller(context);
        mTouchHandler = new TaskStackViewTouchHandler(context, this);
        mViewPool = new ViewPool<TaskView, Task>(context, this);
        mInflater = LayoutInflater.from(context);
        mStackAlgorithm = new TaskStackViewLayoutAlgorithm(mConfig);
        mFilterAlgorithm = new TaskStackViewFilterAlgorithm(mConfig, this, mViewPool);
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
        mHwLayersTrigger = new ReferenceCountedTrigger(getContext(), new Runnable() {
            @Override
            public void run() {
                // Enable hw layers on each of the children
                int childCount = getChildCount();
                for (int i = 0; i < childCount; i++) {
                    TaskView tv = (TaskView) getChildAt(i);
                    tv.enableHwLayers();
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                // Disable hw layers on each of the children
                int childCount = getChildCount();
                for (int i = 0; i < childCount; i++) {
                    TaskView tv = (TaskView) getChildAt(i);
                    tv.disableHwLayers();
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                new Throwable("Invalid hw layers ref count").printStackTrace();
                Console.logError(getContext(), "Invalid HW layers ref count");
            }
        });
    }

    /** Sets the callbacks */
    void setCallbacks(TaskStackViewCallbacks cb) {
        mCb = cb;
    }

    /** Requests that the views be synchronized with the model */
    void requestSynchronizeStackViewsWithModel() {
        requestSynchronizeStackViewsWithModel(0);
    }
    void requestSynchronizeStackViewsWithModel(int duration) {
        if (Console.Enabled) {
            Console.log(Constants.Log.TaskStack.SynchronizeViewsWithModel,
                    "[TaskStackView|requestSynchronize]", "" + duration + "ms", Console.AnsiYellow);
        }
        if (!mStackViewsDirty) {
            invalidate(mStackAlgorithm.mStackRect);
        }
        if (mAwaitingFirstLayout) {
            // Skip the animation if we are awaiting first layout
            mStackViewsAnimationDuration = 0;
        } else {
            mStackViewsAnimationDuration = Math.max(mStackViewsAnimationDuration, duration);
        }
        mStackViewsDirty = true;
    }

    /** Returns a mapping of child view to Task. */
    HashMap<Task, TaskView> getTaskChildViewMap() {
        HashMap<Task, TaskView> taskViewMap = new HashMap<Task, TaskView>();
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView tv = (TaskView) getChildAt(i);
            taskViewMap.put(tv.getTask(), tv);
        }
        return taskViewMap;
    }

    /** Finds the child view given a specific task. */
    TaskView getChildViewForTask(Task t) {
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
        return mStackAlgorithm;
    }

    /**
     * Gets the stack transforms of a list of tasks, and returns the visible range of tasks.
     */
    private void updateStackTransforms(ArrayList<TaskViewTransform> taskTransforms,
                                       ArrayList<Task> tasks,
                                       int stackScroll,
                                       int[] visibleRangeOut,
                                       boolean boundTranslationsToRect) {
        // XXX: We should be intelligent about where to look for the visible stack range using the
        //      current stack scroll.

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
        TaskStack.GroupTaskIndex groupTaskIndex = new TaskStack.GroupTaskIndex();
        for (int i = taskCount - 1; i >= 0; i--) {
            mStack.getGroupIndexForTask(tasks.get(i), groupTaskIndex);
            TaskViewTransform transform = mStackAlgorithm.getStackTransform(groupTaskIndex.groupIndex,
                    groupTaskIndex.taskIndex, stackScroll, taskTransforms.get(i));
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
                        mStackAlgorithm.mRect.bottom);
            }
        }
        if (visibleRangeOut != null) {
            visibleRangeOut[0] = frontMostVisibleIndex;
            visibleRangeOut[1] = backMostVisibleIndex;
            if (Console.Enabled) {
                Console.log(Constants.Log.TaskStack.SynchronizeViewsWithModel,
                        "[TaskStackView|updateStackTransforms]",
                        "Back: " + backMostVisibleIndex + " Front: " + frontMostVisibleIndex);
            }
        }
    }

    /**
     * Gets the stack transforms of a list of tasks, and returns the visible range of tasks. This
     * call is less optimal than calling updateStackTransforms directly.
     */
    private ArrayList<TaskViewTransform> getStackTransforms(ArrayList<Task> tasks,
                                                            int stackScroll,
                                                            int[] visibleRangeOut,
                                                            boolean boundTranslationsToRect) {
        ArrayList<TaskViewTransform> taskTransforms = new ArrayList<TaskViewTransform>();
        updateStackTransforms(taskTransforms, tasks, stackScroll, visibleRangeOut,
                boundTranslationsToRect);
        return taskTransforms;
    }

    /** Synchronizes the views with the model */
    void synchronizeStackViewsWithModel() {
        if (Console.Enabled) {
            Console.log(Constants.Log.TaskStack.SynchronizeViewsWithModel,
                    "[TaskStackView|synchronizeViewsWithModel]",
                    "mStackViewsDirty: " + mStackViewsDirty, Console.AnsiYellow);
        }
        if (mStackViewsDirty) {
            // Get all the task transforms
            ArrayList<Task> tasks = mStack.getTasks();
            int stackScroll = getStackScroll();
            int[] visibleRange = mTmpVisibleRange;
            updateStackTransforms(mCurrentTaskTransforms, tasks, stackScroll, visibleRange, false);
            TaskViewTransform tmpTransform = new TaskViewTransform();
            TaskStack.GroupTaskIndex gti = new TaskStack.GroupTaskIndex();

            // Return all the invisible children to the pool
            HashMap<Task, TaskView> taskChildViewMap = getTaskChildViewMap();
            int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                TaskView tv = (TaskView) getChildAt(i);
                Task task = tv.getTask();
                int taskIndex = mStack.indexOfTask(task);
                if (taskIndex < visibleRange[1] || taskIndex > visibleRange[0]) {
                    taskChildViewMap.remove(task);
                    mViewPool.returnViewToPool(tv);
                }
            }

            // Pick up all the newly visible children and update all the existing children
            boolean isValidVisibleRange = visibleRange[0] != -1 && visibleRange[1] != -1;
            for (int i = visibleRange[0]; isValidVisibleRange && i >= visibleRange[1]; i--) {
                Task task = tasks.get(i);
                TaskViewTransform transform = mCurrentTaskTransforms.get(i);
                TaskView tv = taskChildViewMap.get(task);
                int taskIndex = mStack.indexOfTask(task);

                if (tv == null) {
                    tv = mViewPool.pickUpViewFromPool(task, task);
                    if (mStackViewsAnimationDuration > 0) {
                        // For items in the list, put them in start animating them from the
                        // approriate ends of the list where they are expected to appear
                        Task fromTask = (transform.t < 0) ?
                                tasks.get(visibleRange[1]) :
                                tasks.get(visibleRange[0]);
                        mStack.getGroupIndexForTask(fromTask, gti);
                        tmpTransform = mStackAlgorithm.getStackTransform(
                                (transform.t < 0) ? gti.groupIndex - 1 : gti.groupIndex + 1,
                                (transform.t < 0) ? gti.taskIndex - 1 : gti.taskIndex + 1,
                                stackScroll, tmpTransform);
                        tv.updateViewPropertiesToTaskTransform(tmpTransform, 0);
                    }
                }

                // Update and animate the task into place
                tv.updateViewPropertiesToTaskTransform(mCurrentTaskTransforms.get(taskIndex),
                        mStackViewsAnimationDuration);
            }

            if (Console.Enabled) {
                Console.log(Constants.Log.TaskStack.SynchronizeViewsWithModel,
                        "  [TaskStackView|viewChildren]", "" + getChildCount());
            }

            mStackViewsAnimationDuration = 0;
            mStackViewsDirty = false;
        }
    }

    /** Sets the current stack scroll */
    public void setStackScroll(int value) {
        mStackScroll = value;
        mUIDozeTrigger.poke();
        requestSynchronizeStackViewsWithModel();
    }
    /** Sets the current stack scroll without synchronizing the stack view with the model */
    public void setStackScrollRaw(int value) {
        mStackScroll = value;
        mUIDozeTrigger.poke();
    }
    /** Sets the current stack scroll to the initial state when you first enter recents */
    public void setStackScrollToInitialState() {
        setStackScroll(getInitialStackScroll());
    }
    /** Computes the initial stack scroll for the stack. */
    int getInitialStackScroll() {
        if (mStack.getGroupingCount() > 2) {
            return mMaxScroll - mStackAlgorithm.mTaskRect.height() / 2;
        }
        return mMaxScroll;
    }

    /** Gets the current stack scroll */
    public int getStackScroll() {
        return mStackScroll;
    }

    /** Animates the stack scroll into bounds */
    ObjectAnimator animateBoundScroll() {
        int curScroll = getStackScroll();
        int newScroll = Math.max(mMinScroll, Math.min(mMaxScroll, curScroll));
        if (newScroll != curScroll) {
            // Enable hw layers on the stack
            addHwLayersRefCount("animateBoundScroll");

            // Start a new scroll animation
            animateScroll(curScroll, newScroll, new Runnable() {
                @Override
                public void run() {
                    // Disable hw layers on the stack
                    decHwLayersRefCount("animateBoundScroll");
                }
            });
        }
        return mScrollAnimator;
    }

    /** Animates the stack scroll */
    void animateScroll(int curScroll, int newScroll, final Runnable postRunnable) {
        // Abort any current animations
        abortScroller();
        abortBoundScrollAnimation();

        mScrollAnimator = ObjectAnimator.ofInt(this, "stackScroll", curScroll, newScroll);
        mScrollAnimator.setDuration(Utilities.calculateTranslationAnimationDuration(newScroll -
                curScroll, 250));
        mScrollAnimator.setInterpolator(mConfig.fastOutSlowInInterpolator);
        mScrollAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setStackScroll((Integer) animation.getAnimatedValue());
            }
        });
        mScrollAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (postRunnable != null) {
                    postRunnable.run();
                }
                mScrollAnimator.removeAllListeners();
            }
        });
        mScrollAnimator.start();
    }

    /** Aborts any current stack scrolls */
    void abortBoundScrollAnimation() {
        if (mScrollAnimator != null) {
            mScrollAnimator.cancel();
        }
    }

    /** Aborts the scroller and any current fling */
    void abortScroller() {
        if (!mScroller.isFinished()) {
            // Abort the scroller
            mScroller.abortAnimation();
            // And disable hw layers on the stack
            decHwLayersRefCount("flingScroll");
        }
    }

    /** Bounds the current scroll if necessary */
    public boolean boundScroll() {
        int curScroll = getStackScroll();
        int newScroll = Math.max(mMinScroll, Math.min(mMaxScroll, curScroll));
        if (newScroll != curScroll) {
            setStackScroll(newScroll);
            return true;
        }
        return false;
    }

    /**
     * Bounds the current scroll if necessary, but does not synchronize the stack view with the
     * model.
     */
    public boolean boundScrollRaw() {
        int curScroll = getStackScroll();
        int newScroll = Math.max(mMinScroll, Math.min(mMaxScroll, curScroll));
        if (newScroll != curScroll) {
            setStackScrollRaw(newScroll);
            return true;
        }
        return false;
    }


    /** Returns the amount that the scroll is out of bounds */
    int getScrollAmountOutOfBounds(int scroll) {
        if (scroll < mMinScroll) {
            return mMinScroll - scroll;
        } else if (scroll > mMaxScroll) {
            return scroll - mMaxScroll;
        }
        return 0;
    }

    /** Returns whether the specified scroll is out of bounds */
    boolean isScrollOutOfBounds() {
        return getScrollAmountOutOfBounds(getStackScroll()) != 0;
    }

    /** Updates the min and max virtual scroll bounds */
    void updateMinMaxScroll(boolean boundScrollToNewMinMax) {
        // Compute the min and max scroll values
        mStackAlgorithm.computeMinMaxScroll(mStack.getGroupingCount());
        mMinScroll = mStackAlgorithm.mMinScroll;
        mMaxScroll = mStackAlgorithm.mMaxScroll;

        // Debug logging
        if (Constants.Log.UI.MeasureAndLayout) {
            Console.log("  [TaskStack|minScroll] " + mMinScroll);
            Console.log("  [TaskStack|maxScroll] " + mMaxScroll);
        }

        if (boundScrollToNewMinMax) {
            boundScroll();
        }
    }

    /** Animates a task view in this stack as it launches. */
    public void animateOnLaunchingTask(TaskView tv, final Runnable r) {
        // Hide each of the task bar dismiss buttons
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView t = (TaskView) getChildAt(i);
            if (t == tv) {
                t.startLaunchTaskAnimation(r, true);
            } else {
                t.startLaunchTaskAnimation(null, false);
            }
        }
    }

    /** Focuses the task at the specified index in the stack */
    void focusTask(int taskIndex, boolean scrollToNewPosition) {
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.Focus, "[TaskStackView|focusTask]", "" + taskIndex);
        }
        if (0 <= taskIndex && taskIndex < mStack.getTaskCount()) {
            mFocusedTaskIndex = taskIndex;

            // Focus the view if possible, otherwise, focus the view after we scroll into position
            Task t = mStack.getTasks().get(taskIndex);
            TaskView tv = getChildViewForTask(t);
            Runnable postScrollRunnable = null;
            if (tv != null) {
                tv.setFocusedTask();
                if (Console.Enabled) {
                    Console.log(Constants.Log.UI.Focus, "[TaskStackView|focusTask]", "Requesting focus");
                }
            } else {
                postScrollRunnable = new Runnable() {
                    @Override
                    public void run() {
                        Task t = mStack.getTasks().get(mFocusedTaskIndex);
                        TaskView tv = getChildViewForTask(t);
                        if (tv != null) {
                            tv.setFocusedTask();
                            if (Console.Enabled) {
                                Console.log(Constants.Log.UI.Focus, "[TaskStackView|focusTask]",
                                        "Requesting focus after scroll animation");
                            }
                        }
                    }
                };
            }

            if (scrollToNewPosition) {
                // Scroll the view into position
                int newScroll = Math.max(mMinScroll, Math.min(mMaxScroll,
                        mStackAlgorithm.getStackScrollForTaskIndex(taskIndex)));

                animateScroll(getStackScroll(), newScroll, postScrollRunnable);
            } else {
                if (postScrollRunnable != null) {
                    postScrollRunnable.run();
                }
            }
        }
    }

    /** Focuses the next task in the stack */
    void focusNextTask(boolean forward) {
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.Focus, "[TaskStackView|focusNextTask]", "" +
                    mFocusedTaskIndex);
        }

        // Find the next index to focus
        int numTasks = mStack.getTaskCount();
        if (mFocusedTaskIndex < 0) {
            mFocusedTaskIndex = numTasks - 1;
        }
        if (0 <= mFocusedTaskIndex && mFocusedTaskIndex < numTasks) {
            mFocusedTaskIndex = Math.max(0, Math.min(numTasks - 1,
                    mFocusedTaskIndex + (forward ? -1 : 1)));
        }
        focusTask(mFocusedTaskIndex, true);
    }

    /** Enables the hw layers and increments the hw layer requirement ref count */
    void addHwLayersRefCount(String reason) {
        if (Console.Enabled) {
            int refCount = mHwLayersTrigger.getCount();
            Console.log(Constants.Log.UI.HwLayers,
                    "[TaskStackView|addHwLayersRefCount] refCount: " +
                            refCount + "->" + (refCount + 1) + " " + reason);
        }
        mHwLayersTrigger.increment();
    }

    /** Decrements the hw layer requirement ref count and disables the hw layers when we don't
        need them anymore. */
    void decHwLayersRefCount(String reason) {
        if (Console.Enabled) {
            int refCount = mHwLayersTrigger.getCount();
            Console.log(Constants.Log.UI.HwLayers,
                    "[TaskStackView|decHwLayersRefCount] refCount: " +
                            refCount + "->" + (refCount - 1) + " " + reason);
        }
        mHwLayersTrigger.decrement();
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
        if (mScroller.computeScrollOffset()) {
            setStackScroll(mScroller.getCurrY());
            invalidate(mStackAlgorithm.mStackRect);

            // If we just finished scrolling, then disable the hw layers
            if (mScroller.isFinished()) {
                decHwLayersRefCount("finishedFlingScroll");
            }
        }
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.Draw, "[TaskStackView|dispatchDraw]", "",
                    Console.AnsiPurple);
        }
        synchronizeStackViewsWithModel();
        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (Constants.DebugFlags.App.EnableTaskStackClipping) {
            TaskView tv = (TaskView) child;
            TaskView nextTv = null;
            TaskView tmpTv = null;
            if (tv.shouldClipViewInStack()) {
                int curIndex = indexOfChild(tv);

                // Find the next view to clip against
                while (nextTv == null && curIndex < getChildCount()) {
                    tmpTv = (TaskView) getChildAt(++curIndex);
                    if (tmpTv != null && tmpTv.shouldClipViewInStack()) {
                        nextTv = tmpTv;
                    }
                }

                // Clip against the next view (if we aren't animating its alpha)
                if (nextTv != null) {
                    Rect curRect = tv.getClippingRect(mTmpRect);
                    Rect nextRect = nextTv.getClippingRect(mTmpRect2);
                    // The hit rects are relative to the task view, which needs to be offset by
                    // the system bar height
                    curRect.offset(0, mConfig.systemInsets.top);
                    nextRect.offset(0, mConfig.systemInsets.top);
                    // Compute the clip region
                    Region clipRegion = new Region();
                    clipRegion.op(curRect, Region.Op.UNION);
                    clipRegion.op(nextRect, Region.Op.DIFFERENCE);
                    // Clip the canvas
                    int saveCount = canvas.save(Canvas.CLIP_SAVE_FLAG);
                    canvas.clipRegion(clipRegion);
                    boolean invalidate = super.drawChild(canvas, child, drawingTime);
                    canvas.restoreToCount(saveCount);
                    return invalidate;
                }
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    /** Computes the stack and task rects */
    public void computeRects(int width, int height, int insetLeft, int insetBottom) {
        // Compute the rects in the stack algorithm
        mStackAlgorithm.computeRects(width, height, insetLeft, insetBottom);

        // Update the scroll bounds
        updateMinMaxScroll(false);
    }

    /**
     * This is called with the size of the space not including the top or right insets, or the
     * search bar height in portrait (but including the search bar width in landscape, since we want
     * to draw under it.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.MeasureAndLayout, "[TaskStackView|measure]",
                    "width: " + width + " height: " + height +
                            " awaitingFirstLayout: " + mAwaitingFirstLayout, Console.AnsiGreen);
        }

        // Compute our stack/task rects
        Rect taskStackBounds = new Rect();
        mConfig.getTaskStackBounds(width, height, taskStackBounds);
        computeRects(width, height, taskStackBounds.left, mConfig.systemInsets.bottom);

        // Debug logging
        if (Constants.Log.UI.MeasureAndLayout) {
            Console.log("  [TaskStack|fullRect] " + mStackAlgorithm.mRect);
            Console.log("  [TaskStack|stackRect] " + mStackAlgorithm.mStackRect);
            Console.log("  [TaskStack|stackRectSansPeek] " + mStackAlgorithm.mStackRectSansPeek);
            Console.log("  [TaskStack|taskRect] " + mStackAlgorithm.mTaskRect);
        }

        // If this is the first layout, then scroll to the front of the stack and synchronize the
        // stack views immediately
        if (mAwaitingFirstLayout) {
            setStackScrollToInitialState();
            requestSynchronizeStackViewsWithModel();
            synchronizeStackViewsWithModel();
        }

        // Measure each of the children
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView t = (TaskView) getChildAt(i);
            t.measure(MeasureSpec.makeMeasureSpec(mStackAlgorithm.mTaskRect.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(mStackAlgorithm.mTaskRect.height(), MeasureSpec.EXACTLY));
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
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.MeasureAndLayout, "[TaskStackView|layout]",
                    "" + new Rect(left, top, right, bottom), Console.AnsiGreen);
        }

        // Debug logging
        if (Constants.Log.UI.MeasureAndLayout) {
            Console.log("  [TaskStack|fullRect] " + mStackAlgorithm.mRect);
            Console.log("  [TaskStack|stackRect] " + mStackAlgorithm.mStackRect);
            Console.log("  [TaskStack|stackRectSansPeek] " + mStackAlgorithm.mStackRectSansPeek);
            Console.log("  [TaskStack|taskRect] " + mStackAlgorithm.mTaskRect);
        }

        // Layout each of the children
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView t = (TaskView) getChildAt(i);
            t.layout(mStackAlgorithm.mTaskRect.left, mStackAlgorithm.mStackRectSansPeek.top,
                    mStackAlgorithm.mTaskRect.right, mStackAlgorithm.mStackRectSansPeek.top +
                    mStackAlgorithm.mTaskRect.height());
        }

        if (mAwaitingFirstLayout) {
            // Mark that we have completely the first layout
            mAwaitingFirstLayout = false;

            // Prepare the first view for its enter animation
            int offsetTopAlign = -mStackAlgorithm.mTaskRect.top;
            int offscreenY = mStackAlgorithm.mRect.bottom -
                    (mStackAlgorithm.mTaskRect.top - mStackAlgorithm.mRect.top);
            for (int i = childCount - 1; i >= 0; i--) {
                TaskView tv = (TaskView) getChildAt(i);
                tv.prepareEnterRecentsAnimation((i == (getChildCount() - 1)), offsetTopAlign,
                        offscreenY, mStackAlgorithm.mTaskRect);
            }

            // If the enter animation started already and we haven't completed a layout yet, do the
            // enter animation now
            if (mStartEnterAnimationRequestedAfterLayout) {
                startEnterRecentsAnimation(mStartEnterAnimationContext);
                mStartEnterAnimationRequestedAfterLayout = false;
                mStartEnterAnimationContext = null;
            }

            // Update the focused task index to be the next item to the top task
            if (mConfig.launchedWithAltTab) {
                // When alt-tabbing, we focus the next previous task
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
            TaskStack.GroupTaskIndex groupTaskIndex = new TaskStack.GroupTaskIndex();

            if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
                // Find the target task with the specified id
                ArrayList<Task> tasks = mStack.getTasks();
                Task targetTask = null;
                int targetTaskId = mConfig.launchedToTaskId;
                if (targetTaskId != -1) {
                    int taskCount = tasks.size();
                    for (int i = 0; i < taskCount; i++) {
                        Task t = tasks.get(i);
                        if (t.key.id == targetTaskId) {
                            targetTask = t;
                            break;
                        }
                    }
                }

                // Find the group and task index of the target task
                if (targetTask != null) {
                    mStack.getGroupIndexForTask(targetTask, groupTaskIndex);
                    ctx.targetTaskTransform = new TaskViewTransform();
                    mStackAlgorithm.getStackTransform(groupTaskIndex.groupIndex,
                            groupTaskIndex.taskIndex, getStackScroll(), ctx.targetTaskTransform);
                    Rect taskStackBounds = new Rect();
                    mConfig.getTaskStackBounds(getMeasuredWidth(), getMeasuredHeight(), taskStackBounds);
                    ctx.targetTaskTransform.rect.offset(taskStackBounds.left, taskStackBounds.top);
                }
            }

            // Animate all the task views into view
            int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                TaskView tv = (TaskView) getChildAt(i);
                ctx.currentTaskTransform = new TaskViewTransform();
                ctx.currentStackViewIndex = i;
                ctx.currentStackViewCount = childCount;
                ctx.isCurrentTaskFrontMost = (i == (getChildCount() - 1));
                mStack.getGroupIndexForTask(tv.getTask(), groupTaskIndex);
                mStackAlgorithm.getStackTransform(groupTaskIndex.groupIndex, groupTaskIndex.taskIndex,
                        getStackScroll(), ctx.currentTaskTransform);
                tv.startEnterRecentsAnimation(ctx);
            }

            // Add a runnable to the post animation ref counter to clear all the views
            ctx.postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    // Start dozing
                    mUIDozeTrigger.startDozing();
                }
            });
        }
    }

    /** Requests this task stacks to start it's exit-recents animation. */
    public void startExitToHomeAnimation(ViewAnimation.TaskViewExitContext ctx) {
        // Animate all the task views into view
        ctx.offscreenTranslationY = mStackAlgorithm.mRect.bottom -
                (mStackAlgorithm.mTaskRect.top - mStackAlgorithm.mRect.top);
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView tv = (TaskView) getChildAt(i);
            tv.startExitToHomeAnimation(ctx);
        }

        // Add a runnable to the post animation ref counter to clear all the views
        ctx.postAnimationTrigger.addLastDecrementRunnable(mReturnAllViewsToPoolRunnable);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        requestSynchronizeStackViewsWithModel();
    }

    public boolean isTransformedTouchPointInView(float x, float y, View child) {
        return isTransformedTouchPointInView(x, y, child, null);
    }

    /** Pokes the dozer on user interaction. */
    void onUserInteraction() {
        // Poke the doze trigger if it is dozing
        mUIDozeTrigger.poke();
    }

    /** Disables handling touch on this task view. */
    void setTouchOnTaskView(TaskView tv, boolean enabled) {
        tv.setOnClickListener(enabled ? this : null);
    }

    /**** TaskStackCallbacks Implementation ****/

    @Override
    public void onStackTaskAdded(TaskStack stack, Task t) {
        requestSynchronizeStackViewsWithModel();
    }

    @Override
    public void onStackTaskRemoved(TaskStack stack, Task t) {
        // Remove the view associated with this task, we can't rely on updateTransforms
        // to work here because the task is no longer in the list
        TaskView tv = getChildViewForTask(t);
        if (tv != null) {
            mViewPool.returnViewToPool(tv);
        }

        // Notify the callback that we've removed the task and it can clean up after it
        mCb.onTaskViewDismissed(t);

        // Update the min/max scroll and animate other task views into their new positions
        updateMinMaxScroll(true);
        int movement = (int) mStackAlgorithm.getTaskOverlapHeight();
        requestSynchronizeStackViewsWithModel(Utilities.calculateTranslationAnimationDuration(movement));

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
        // Stash the scroll and filtered task for us to restore to when we unfilter
        mStashedScroll = getStackScroll();

        // Calculate the current task transforms
        ArrayList<TaskViewTransform> curTaskTransforms =
                getStackTransforms(curTasks, getStackScroll(), null, true);

        // Scroll the item to the top of the stack (sans-peek) rect so that we can see it better
        updateMinMaxScroll(false);
        float overlapHeight = mStackAlgorithm.getTaskOverlapHeight();
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
    }

    @Override
    public void onStackUnfiltered(TaskStack newStack, final ArrayList<Task> curTasks) {
        // Calculate the current task transforms
        final ArrayList<TaskViewTransform> curTaskTransforms =
                getStackTransforms(curTasks, getStackScroll(), null, true);

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
    }

    /**** ViewPoolConsumer Implementation ****/

    @Override
    public TaskView createView(Context context) {
        if (Console.Enabled) {
            Console.log(Constants.Log.ViewPool.PoolCallbacks, "[TaskStackView|createPoolView]");
        }
        return (TaskView) mInflater.inflate(R.layout.recents_task_view, this, false);
    }

    @Override
    public void prepareViewToEnterPool(TaskView tv) {
        Task task = tv.getTask();
        if (Console.Enabled) {
            Console.log(Constants.Log.ViewPool.PoolCallbacks, "[TaskStackView|returnToPool]",
                    tv.getTask() + " tv: " + tv);
        }

        // Report that this tasks's data is no longer being used
        RecentsTaskLoader.getInstance().unloadTaskData(task);

        // Detach the view from the hierarchy
        detachViewFromParent(tv);

        // Disable HW layers
        tv.disableHwLayers();

        // Reset the view properties
        tv.resetViewProperties();
    }

    @Override
    public void prepareViewToLeavePool(TaskView tv, Task task, boolean isNewView) {
        if (Console.Enabled) {
            Console.log(Constants.Log.ViewPool.PoolCallbacks, "[TaskStackView|leavePool]",
                    "isNewView: " + isNewView);
        }

        // Rebind the task and request that this task's data be filled into the TaskView
        tv.onTaskBound(task);
        RecentsTaskLoader.getInstance().loadTaskData(task);

        // Sanity check, the task view should always be clipping against the stack at this point,
        // but just in case, re-enable it here
        tv.setClipViewInStack(true);

        // If the doze trigger has already fired, then update the state for this task view
        if (mUIDozeTrigger.hasTriggered()) {
            tv.setNoUserInteractionState();
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
        if (Console.Enabled) {
            Console.log(Constants.Log.ViewPool.PoolCallbacks, "  [TaskStackView|insertIndex]",
                    "" + insertIndex);
        }
        if (isNewView) {
            addView(tv, insertIndex);

            // Set the callbacks and listeners for this new view
            setTouchOnTaskView(tv, true);
            tv.setCallbacks(this);
        } else {
            attachViewToParent(tv, insertIndex, tv.getLayoutParams());
        }

        // Enable hw layers on this view if hw layers are enabled on the stack
        if (mHwLayersTrigger.getCount() > 0) {
            tv.enableHwLayers();
        }
    }

    @Override
    public boolean hasPreferredData(TaskView tv, Task preferredData) {
        return (tv.getTask() == preferredData);
    }

    /**** TaskViewCallbacks Implementation ****/

    @Override
    public void onTaskViewAppIconClicked(TaskView tv) {
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.ClickEvents, "[TaskStack|Clicked|Icon]",
                    tv.getTask() + " is currently filtered: " + mStack.hasFilteredTasks(),
                    Console.AnsiCyan);
        }
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
    public void onTaskViewDismissed(TaskView tv) {
        Task task = tv.getTask();
        // Remove the task from the view
        mStack.removeTask(task);
    }

    /**** View.OnClickListener Implementation ****/

    @Override
    public void onClick(View v) {
        TaskView tv = (TaskView) v;
        Task task = tv.getTask();
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.ClickEvents, "[TaskStack|Clicked|Thumbnail]",
                    task + " cb: " + mCb);
        }

        // Cancel any doze triggers
        mUIDozeTrigger.stopDozing();

        if (mCb != null) {
            mCb.onTaskViewClicked(this, tv, mStack, task);
        }
    }

    /**** RecentsPackageMonitor.PackageCallbacks Implementation ****/

    @Override
    public void onComponentRemoved(Set<ComponentName> cns) {
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