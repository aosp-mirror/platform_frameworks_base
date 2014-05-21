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
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.OverScroller;
import com.android.systemui.R;
import com.android.systemui.recents.Console;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsPackageMonitor;
import com.android.systemui.recents.RecentsTaskLoader;
import com.android.systemui.recents.Utilities;
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
        public void onTaskLaunched(TaskStackView stackView, TaskView tv, TaskStack stack, Task t);
        public void onTaskAppInfoLaunched(Task t);
        public void onTaskRemoved(Task t);
    }

    TaskStack mStack;
    TaskStackViewTouchHandler mTouchHandler;
    TaskStackViewCallbacks mCb;
    ViewPool<TaskView, Task> mViewPool;

    // The various rects that define the stack view
    Rect mRect = new Rect();
    Rect mStackRect = new Rect();
    Rect mStackRectSansPeek = new Rect();
    Rect mTaskRect = new Rect();

    // The virtual stack scroll that we use for the card layout
    int mStackScroll;
    int mMinScroll;
    int mMaxScroll;
    int mStashedScroll;
    int mFocusedTaskIndex = -1;
    OverScroller mScroller;
    ObjectAnimator mScrollAnimator;

    // Optimizations
    int mHwLayersRefCount;
    int mStackViewsAnimationDuration;
    boolean mStackViewsDirty = true;
    boolean mAwaitingFirstLayout = true;
    int[] mTmpVisibleRange = new int[2];
    Rect mTmpRect = new Rect();
    Rect mTmpRect2 = new Rect();
    LayoutInflater mInflater;

    public TaskStackView(Context context, TaskStack stack) {
        super(context);
        mStack = stack;
        mStack.setCallbacks(this);
        mScroller = new OverScroller(context);
        mTouchHandler = new TaskStackViewTouchHandler(context, this);
        mViewPool = new ViewPool<TaskView, Task>(context, this);
        mInflater = LayoutInflater.from(context);
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
            invalidate();
        }
        if (mAwaitingFirstLayout) {
            // Skip the animation if we are awaiting first layout
            mStackViewsAnimationDuration = 0;
        } else {
            mStackViewsAnimationDuration = Math.max(mStackViewsAnimationDuration, duration);
        }
        mStackViewsDirty = true;
    }

    // XXX: Optimization: Use a mapping of Task -> View
    private TaskView getChildViewForTask(Task t) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView tv = (TaskView) getChildAt(i);
            if (tv.getTask() == t) {
                return tv;
            }
        }
        return null;
    }

    /** Update/get the transform */
    public TaskViewTransform getStackTransform(int indexInStack, int stackScroll) {
        TaskViewTransform transform = new TaskViewTransform();

        // Return early if we have an invalid index
        if (indexInStack < 0) return transform;

        // Map the items to an continuous position relative to the specified scroll
        int numPeekCards = Constants.Values.TaskStackView.StackPeekNumCards;
        float overlapHeight = Constants.Values.TaskStackView.StackOverlapPct * mTaskRect.height();
        float peekHeight = Constants.Values.TaskStackView.StackPeekHeightPct * mStackRect.height();
        float t = ((indexInStack * overlapHeight) - stackScroll) / overlapHeight;
        float boundedT = Math.max(t, -(numPeekCards + 1));

        // Set the scale relative to its position
        float minScale = Constants.Values.TaskStackView.StackPeekMinScale;
        float scaleRange = 1f - minScale;
        float scaleInc = scaleRange / numPeekCards;
        float scale = Math.max(minScale, Math.min(1f, 1f + (boundedT * scaleInc)));
        float scaleYOffset = ((1f - scale) * mTaskRect.height()) / 2;
        transform.scale = scale;

        // Set the translation
        if (boundedT < 0f) {
            transform.translationY = (int) ((Math.max(-numPeekCards, boundedT) /
                    numPeekCards) * peekHeight - scaleYOffset);
        } else {
            transform.translationY = (int) (boundedT * overlapHeight - scaleYOffset);
        }

        // Set the alphas
        transform.dismissAlpha = Math.max(-1f, Math.min(0f, t)) + 1f;

        // Update the rect and visibility
        transform.rect.set(mTaskRect);
        if (t < -(numPeekCards + 1)) {
            transform.visible = false;
        } else {
            transform.rect.offset(0, transform.translationY);
            Utilities.scaleRectAboutCenter(transform.rect, transform.scale);
            transform.visible = Rect.intersects(mRect, transform.rect);
        }
        transform.t = t;
        return transform;
    }

    /**
     * Gets the stack transforms of a list of tasks, and returns the visible range of tasks.
     */
    private ArrayList<TaskViewTransform> getStackTransforms(ArrayList<Task> tasks,
                                                            int stackScroll,
                                                            int[] visibleRangeOut,
                                                            boolean boundTranslationsToRect) {
        // XXX: Optimization: Use binary search to find the visible range

        ArrayList<TaskViewTransform> taskTransforms = new ArrayList<TaskViewTransform>();
        int taskCount = tasks.size();
        int firstVisibleIndex = -1;
        int lastVisibleIndex = -1;
        for (int i = 0; i < taskCount; i++) {
            TaskViewTransform transform = getStackTransform(i, stackScroll);
            taskTransforms.add(transform);
            if (transform.visible) {
                if (firstVisibleIndex < 0) {
                    firstVisibleIndex = i;
                }
                lastVisibleIndex = i;
            }

            if (boundTranslationsToRect) {
                transform.translationY = Math.min(transform.translationY, mRect.bottom);
            }
        }
        if (visibleRangeOut != null) {
            visibleRangeOut[0] = firstVisibleIndex;
            visibleRangeOut[1] = lastVisibleIndex;
        }
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
            // XXX: Consider using TaskViewTransform pool to prevent allocations
            // XXX: Iterate children views, update transforms and remove all that are not visible
            //      For all remaining tasks, update transforms and if visible add the view

            // Get all the task transforms
            int[] visibleRange = mTmpVisibleRange;
            int stackScroll = getStackScroll();
            ArrayList<Task> tasks = mStack.getTasks();
            ArrayList<TaskViewTransform> taskTransforms = getStackTransforms(tasks, stackScroll,
                    visibleRange, false);

            // Update the visible state of all the tasks
            int taskCount = tasks.size();
            for (int i = 0; i < taskCount; i++) {
                Task task = tasks.get(i);
                TaskViewTransform transform = taskTransforms.get(i);
                TaskView tv = getChildViewForTask(task);

                if (transform.visible) {
                    if (tv == null) {
                        tv = mViewPool.pickUpViewFromPool(task, task);
                        // When we are picking up a new view from the view pool, prepare it for any
                        // following animation by putting it in a reasonable place
                        if (mStackViewsAnimationDuration > 0 && i != 0) {
                            int fromIndex = (transform.t < 0) ? (visibleRange[0] - 1) :
                                    (visibleRange[1] + 1);
                            tv.updateViewPropertiesToTaskTransform(null,
                                    getStackTransform(fromIndex, stackScroll), 0);
                        }
                    }
                } else {
                    if (tv != null) {
                        mViewPool.returnViewToPool(tv);
                    }
                }
            }

            // Update all the remaining view children
            // NOTE: We have to iterate in reverse where because we are removing views directly
            int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                TaskView tv = (TaskView) getChildAt(i);
                Task task = tv.getTask();
                int taskIndex = mStack.indexOfTask(task);
                if (taskIndex < 0 || !taskTransforms.get(taskIndex).visible) {
                    mViewPool.returnViewToPool(tv);
                } else {
                    tv.updateViewPropertiesToTaskTransform(null, taskTransforms.get(taskIndex),
                            mStackViewsAnimationDuration);
                }
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
        requestSynchronizeStackViewsWithModel();
    }
    /** Sets the current stack scroll without synchronizing the stack view with the model */
    public void setStackScrollRaw(int value) {
        mStackScroll = value;
    }

    /**
     * Returns the scroll to such that the task transform at that index will have t=0. (If the scroll
     * is not bounded)
     */
    int getStackScrollForTaskIndex(int i) {
        int taskHeight = mTaskRect.height();
        return (int) (i * Constants.Values.TaskStackView.StackOverlapPct * taskHeight);
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
        mScrollAnimator.setInterpolator(RecentsConfiguration.getInstance().defaultBezierInterpolator);
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
        int numTasks = Math.max(1, mStack.getTaskCount());
        int taskHeight = mTaskRect.height();
        int stackHeight = mStackRectSansPeek.height();
        int maxScrollHeight = taskHeight + (int) ((numTasks - 1) *
                Constants.Values.TaskStackView.StackOverlapPct * taskHeight);

        if (numTasks <= 1) {
            // If there is only one task, then center the task in the stack rect (sans peek)
            mMinScroll = mMaxScroll = -(stackHeight - taskHeight) / 2;
        } else {
            mMinScroll = Math.min(stackHeight, maxScrollHeight) - stackHeight;
            mMaxScroll = maxScrollHeight - stackHeight;
        }

        // Debug logging
        if (Constants.Log.UI.MeasureAndLayout) {
            Console.log("  [TaskStack|minScroll] " + mMinScroll);
            Console.log("  [TaskStack|maxScroll] " + mMaxScroll);
        }

        if (boundScrollToNewMinMax) {
            boundScroll();
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
                        getStackScrollForTaskIndex(taskIndex)));

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
            Console.log(Constants.Log.UI.HwLayers,
                    "[TaskStackView|addHwLayersRefCount] refCount: " +
                            mHwLayersRefCount + "->" + (mHwLayersRefCount + 1) + " " + reason);
        }
        if (mHwLayersRefCount == 0) {
            // Enable hw layers on each of the children
            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                TaskView tv = (TaskView) getChildAt(i);
                tv.enableHwLayers();
            }
        }
        mHwLayersRefCount++;
    }

    /** Decrements the hw layer requirement ref count and disables the hw layers when we don't
        need them anymore. */
    void decHwLayersRefCount(String reason) {
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.HwLayers,
                    "[TaskStackView|decHwLayersRefCount] refCount: " +
                            mHwLayersRefCount + "->" + (mHwLayersRefCount - 1) + " " + reason);
        }
        mHwLayersRefCount--;
        if (mHwLayersRefCount == 0) {
            // Disable hw layers on each of the children
            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                TaskView tv = (TaskView) getChildAt(i);
                tv.disableHwLayers();
            }
        } else if (mHwLayersRefCount < 0) {
            new Throwable("Invalid hw layers ref count").printStackTrace();
            Console.logError(getContext(), "Invalid HW layers ref count");
        }
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            setStackScroll(mScroller.getCurrY());
            invalidate();

            // If we just finished scrolling, then disable the hw layers
            if (mScroller.isFinished()) {
                decHwLayersRefCount("finishedFlingScroll");
            }
        }
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
            RecentsConfiguration config = RecentsConfiguration.getInstance();
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
                if (nextTv != null && nextTv.getAlpha() == 1f) {
                    Rect curRect = tv.getClippingRect(mTmpRect);
                    Rect nextRect = nextTv.getClippingRect(mTmpRect2);
                    // The hit rects are relative to the task view, which needs to be offset by
                    // the system bar height
                    curRect.offset(0, config.systemInsets.top);
                    nextRect.offset(0, config.systemInsets.top);
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
        // Note: We let the stack view be the full height because we want the cards to go under the
        //       navigation bar if possible.  However, the stack rects which we use to calculate
        //       max scroll, etc. need to take the nav bar into account

        // Compute the stack rects
        mRect.set(0, 0, width, height);
        mStackRect.set(mRect);
        mStackRect.left += insetLeft;
        mStackRect.bottom -= insetBottom;

        int smallestDimension = Math.min(width, height);
        int padding = (int) (Constants.Values.TaskStackView.StackPaddingPct * smallestDimension / 2f);
        if (Constants.DebugFlags.App.EnableSearchLayout) {
            mStackRect.top += padding;
            mStackRect.left += padding;
            mStackRect.right -= padding;
            mStackRect.bottom -= padding;
        } else {
            mStackRect.inset(padding, padding);
        }
        mStackRectSansPeek.set(mStackRect);
        mStackRectSansPeek.top += Constants.Values.TaskStackView.StackPeekHeightPct * mStackRect.height();

        // Compute the task rect
        int minHeight = (int) (mStackRect.height() -
                (Constants.Values.TaskStackView.StackPeekHeightPct * mStackRect.height()));
        int size = Math.min(minHeight, Math.min(mStackRect.width(), mStackRect.height()));
        int left = mStackRect.left + (mStackRect.width() - size) / 2;
        mTaskRect.set(left, mStackRectSansPeek.top,
                left + size, mStackRectSansPeek.top + size);

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
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        Rect taskStackBounds = new Rect();
        config.getTaskStackBounds(width, height, taskStackBounds);
        computeRects(width, height, taskStackBounds.left, config.systemInsets.bottom);

        // Debug logging
        if (Constants.Log.UI.MeasureAndLayout) {
            Console.log("  [TaskStack|fullRect] " + mRect);
            Console.log("  [TaskStack|stackRect] " + mStackRect);
            Console.log("  [TaskStack|stackRectSansPeek] " + mStackRectSansPeek);
            Console.log("  [TaskStack|taskRect] " + mTaskRect);
        }

        // If this is the first layout, then scroll to the front of the stack and synchronize the
        // stack views immediately
        if (mAwaitingFirstLayout) {
            setStackScroll(mMaxScroll);
            requestSynchronizeStackViewsWithModel();
            synchronizeStackViewsWithModel();

            // Update the focused task index to be the next item to the top task
            if (config.launchedFromAltTab) {
                focusTask(Math.max(0, mStack.getTaskCount() - 2), false);
            }

            // Animate the task bar of the first task view
            if (config.launchedWithThumbnailAnimation) {
                TaskView tv = (TaskView) getChildAt(getChildCount() - 1);
                if (tv != null) {
                    tv.animateOnEnterRecents();
                }
            }
        }

        // Measure each of the children
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView t = (TaskView) getChildAt(i);
            t.measure(MeasureSpec.makeMeasureSpec(mTaskRect.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(mTaskRect.height(), MeasureSpec.EXACTLY));
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
            Console.log("  [TaskStack|fullRect] " + mRect);
            Console.log("  [TaskStack|stackRect] " + mStackRect);
            Console.log("  [TaskStack|stackRectSansPeek] " + mStackRectSansPeek);
            Console.log("  [TaskStack|taskRect] " + mTaskRect);
        }

        // Layout each of the children
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView t = (TaskView) getChildAt(i);
            t.layout(mTaskRect.left, mStackRectSansPeek.top,
                    mTaskRect.right, mStackRectSansPeek.top + mTaskRect.height());
        }

        if (mAwaitingFirstLayout) {
            mAwaitingFirstLayout = false;
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        requestSynchronizeStackViewsWithModel();
    }

    public boolean isTransformedTouchPointInView(float x, float y, View child) {
        return isTransformedTouchPointInView(x, y, child, null);
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
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            TaskView tv = (TaskView) getChildAt(i);
            if (tv.getTask() == t) {
                mViewPool.returnViewToPool(tv);
                break;
            }
        }

        // Update the min/max scroll and animate other task views into their new positions
        updateMinMaxScroll(true);
        int movement = (int) (Constants.Values.TaskStackView.StackOverlapPct * mTaskRect.height());
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
                Activity activity = (Activity) getContext();
                activity.finish();
            }
        }
    }

    /**
     * Creates the animations for all the children views that need to be removed or to move views
     * to their un/filtered position when we are un/filtering a stack, and returns the duration
     * for these animations.
     */
    int getExitTransformsForFilterAnimation(ArrayList<Task> curTasks,
                        ArrayList<TaskViewTransform> curTaskTransforms,
                        ArrayList<Task> tasks, ArrayList<TaskViewTransform> taskTransforms,
                        HashMap<TaskView, Pair<Integer, TaskViewTransform>> childViewTransformsOut,
                        ArrayList<TaskView> childrenToRemoveOut,
                        RecentsConfiguration config) {
        // Animate all of the existing views out of view (if they are not in the visible range in
        // the new stack) or to their final positions in the new stack
        int movement = 0;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskView tv = (TaskView) getChildAt(i);
            Task task = tv.getTask();
            int taskIndex = tasks.indexOf(task);
            TaskViewTransform toTransform;

            // If the view is no longer visible, then we should just animate it out
            boolean willBeInvisible = taskIndex < 0 || !taskTransforms.get(taskIndex).visible;
            if (willBeInvisible) {
                if (taskIndex < 0) {
                    toTransform = curTaskTransforms.get(curTasks.indexOf(task));
                } else {
                    toTransform = new TaskViewTransform(taskTransforms.get(taskIndex));
                }
                tv.prepareTaskTransformForFilterTaskVisible(toTransform);
                childrenToRemoveOut.add(tv);
            } else {
                toTransform = taskTransforms.get(taskIndex);
                // Use the movement of the visible views to calculate the duration of the animation
                movement = Math.max(movement, Math.abs(toTransform.translationY -
                        (int) tv.getTranslationY()));
            }
            childViewTransformsOut.put(tv, new Pair(0, toTransform));
        }
        return Utilities.calculateTranslationAnimationDuration(movement,
                config.filteringCurrentViewsMinAnimDuration);
    }

    /**
     * Creates the animations for all the children views that need to be animated in when we are
     * un/filtering a stack, and returns the duration for these animations.
     */
    int getEnterTransformsForFilterAnimation(ArrayList<Task> tasks,
                         ArrayList<TaskViewTransform> taskTransforms,
                         HashMap<TaskView, Pair<Integer, TaskViewTransform>> childViewTransformsOut,
                         RecentsConfiguration config) {
        int offset = 0;
        int movement = 0;
        int taskCount = tasks.size();
        for (int i = taskCount - 1; i >= 0; i--) {
            Task task = tasks.get(i);
            TaskViewTransform toTransform = taskTransforms.get(i);
            if (toTransform.visible) {
                TaskView tv = getChildViewForTask(task);
                if (tv == null) {
                    // For views that are not already visible, animate them in
                    tv = mViewPool.pickUpViewFromPool(task, task);

                    // Compose a new transform to fade and slide the new task in
                    TaskViewTransform fromTransform = new TaskViewTransform(toTransform);
                    tv.prepareTaskTransformForFilterTaskHidden(fromTransform);
                    tv.updateViewPropertiesToTaskTransform(null, fromTransform, 0);

                    int startDelay = offset *
                            Constants.Values.TaskStackView.FilterStartDelay;
                    childViewTransformsOut.put(tv, new Pair(startDelay, toTransform));

                    // Use the movement of the new views to calculate the duration of the animation
                    movement = Math.max(movement,
                            Math.abs(toTransform.translationY - fromTransform.translationY));
                    offset++;
                }
            }
        }
        return Utilities.calculateTranslationAnimationDuration(movement,
                config.filteringNewViewsMinAnimDuration);
    }

    /** Orchestrates the animations of the current child views and any new views. */
    void doFilteringAnimation(ArrayList<Task> curTasks,
                              ArrayList<TaskViewTransform> curTaskTransforms,
                              final ArrayList<Task> tasks,
                              final ArrayList<TaskViewTransform> taskTransforms) {
        final RecentsConfiguration config = RecentsConfiguration.getInstance();

        // Calculate the transforms to animate out all the existing views if they are not in the
        // new visible range (or to their final positions in the stack if they are)
        final ArrayList<TaskView> childrenToRemove = new ArrayList<TaskView>();
        final HashMap<TaskView, Pair<Integer, TaskViewTransform>> childViewTransforms =
                new HashMap<TaskView, Pair<Integer, TaskViewTransform>>();
        int duration = getExitTransformsForFilterAnimation(curTasks, curTaskTransforms, tasks,
                taskTransforms, childViewTransforms, childrenToRemove, config);

        // If all the current views are in the visible range of the new stack, then don't wait for
        // views to animate out and animate all the new views into their place
        final boolean unifyNewViewAnimation = childrenToRemove.isEmpty();
        if (unifyNewViewAnimation) {
            int inDuration = getEnterTransformsForFilterAnimation(tasks, taskTransforms,
                    childViewTransforms, config);
            duration = Math.max(duration, inDuration);
        }

        // Animate all the views to their final transforms
        for (final TaskView tv : childViewTransforms.keySet()) {
            Pair<Integer, TaskViewTransform> t = childViewTransforms.get(tv);
            tv.animate().cancel();
            tv.animate()
                    .setStartDelay(t.first)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            childViewTransforms.remove(tv);
                            if (childViewTransforms.isEmpty()) {
                                // Return all the removed children to the view pool
                                for (TaskView tv : childrenToRemove) {
                                    mViewPool.returnViewToPool(tv);
                                }

                                if (!unifyNewViewAnimation) {
                                    // For views that are not already visible, animate them in
                                    childViewTransforms.clear();
                                    int duration = getEnterTransformsForFilterAnimation(tasks,
                                            taskTransforms, childViewTransforms, config);
                                    for (final TaskView tv : childViewTransforms.keySet()) {
                                        Pair<Integer, TaskViewTransform> t = childViewTransforms.get(tv);
                                        tv.animate().setStartDelay(t.first);
                                        tv.updateViewPropertiesToTaskTransform(null, t.second, duration);
                                    }
                                }
                            }
                        }
                    });
            tv.updateViewPropertiesToTaskTransform(null, t.second, duration);
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
        float overlapHeight = Constants.Values.TaskStackView.StackOverlapPct * mTaskRect.height();
        setStackScrollRaw((int) (newStack.indexOfTask(filteredTask) * overlapHeight));
        boundScrollRaw();

        // Compute the transforms of the items in the new stack after setting the new scroll
        final ArrayList<Task> tasks = mStack.getTasks();
        final ArrayList<TaskViewTransform> taskTransforms =
                getStackTransforms(mStack.getTasks(), getStackScroll(), null, true);

        // Animate
        doFilteringAnimation(curTasks, curTaskTransforms, tasks, taskTransforms);
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
        doFilteringAnimation(curTasks, curTaskTransforms, tasks, taskTransforms);

        // Clear the saved vars
        mStashedScroll = 0;
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
        tv.resetViewProperties();
        if (Console.Enabled) {
            Console.log(Constants.Log.ViewPool.PoolCallbacks, "[TaskStackView|returnToPool]",
                    tv.getTask() + " tv: " + tv);
        }

        // Report that this tasks's data is no longer being used
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        loader.unloadTaskData(task);

        // Detach the view from the hierarchy
        detachViewFromParent(tv);

        // Disable hw layers on this view
        tv.disableHwLayers();
    }

    @Override
    public void prepareViewToLeavePool(TaskView tv, Task prepareData, boolean isNewView) {
        if (Console.Enabled) {
            Console.log(Constants.Log.ViewPool.PoolCallbacks, "[TaskStackView|leavePool]",
                    "isNewView: " + isNewView);
        }

        // Setup and attach the view to the window
        Task task = prepareData;
        // We try and rebind the task (this MUST be done before the task filled)
        tv.onTaskBound(task);
        // Request that this tasks's data be filled
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        loader.loadTaskData(task);

        // Find the index where this task should be placed in the children
        int insertIndex = -1;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            Task tvTask = ((TaskView) getChildAt(i)).getTask();
            if (mStack.containsTask(task) && (mStack.indexOfTask(task) < mStack.indexOfTask(tvTask))) {
                insertIndex = i;
                break;
            }
        }

        // Sanity check, the task view should always be clipping against the stack at this point,
        // but just in case, re-enable it here
        tv.setClipViewInStack(true);

        // Add/attach the view to the hierarchy
        if (Console.Enabled) {
            Console.log(Constants.Log.ViewPool.PoolCallbacks, "  [TaskStackView|insertIndex]",
                    "" + insertIndex);
        }
        if (isNewView) {
            addView(tv, insertIndex);

            // Set the callbacks and listeners for this new view
            tv.setOnClickListener(this);
            tv.setCallbacks(this);
        } else {
            attachViewToParent(tv, insertIndex, tv.getLayoutParams());
        }

        // Enable hw layers on this view if hw layers are enabled on the stack
        if (mHwLayersRefCount > 0) {
            tv.enableHwLayers();
        }
    }

    @Override
    public boolean hasPreferredData(TaskView tv, Task preferredData) {
        return (tv.getTask() == preferredData);
    }

    /**** TaskViewCallbacks Implementation ****/

    @Override
    public void onTaskIconClicked(TaskView tv) {
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
    public void onTaskAppInfoClicked(TaskView tv) {
        if (mCb != null) {
            mCb.onTaskAppInfoLaunched(tv.getTask());
        }
    }

    @Override
    public void onTaskFocused(TaskView tv) {
        // Do nothing
    }

    @Override
    public void onTaskDismissed(TaskView tv) {
        Task task = tv.getTask();
        // Remove the task from the view
        mStack.removeTask(task);
        // Notify the callback that we've removed the task and it can clean up after it
        mCb.onTaskRemoved(task);
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

        if (mCb != null) {
            mCb.onTaskLaunched(this, tv, mStack, task);
        }
    }

    /**** RecentsPackageMonitor.PackageCallbacks Implementation ****/

    @Override
    public void onComponentRemoved(Set<ComponentName> cns) {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        // For other tasks, just remove them directly if they no longer exist
        ArrayList<Task> tasks = mStack.getTasks();
        for (int i = tasks.size() - 1; i >= 0; i--) {
            final Task t = tasks.get(i);
            if (cns.contains(t.key.baseIntent.getComponent())) {
                TaskView tv = getChildViewForTask(t);
                if (tv != null) {
                    // For visible children, defer removing the task until after the animation
                    tv.animateRemoval(new Runnable() {
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

/* Handles touch events */
class TaskStackViewTouchHandler implements SwipeHelper.Callback {
    static int INACTIVE_POINTER_ID = -1;

    TaskStackView mSv;
    VelocityTracker mVelocityTracker;

    boolean mIsScrolling;

    int mInitialMotionX, mInitialMotionY;
    int mLastMotionX, mLastMotionY;
    int mActivePointerId = INACTIVE_POINTER_ID;
    TaskView mActiveTaskView = null;

    int mTotalScrollMotion;
    int mMinimumVelocity;
    int mMaximumVelocity;
    // The scroll touch slop is used to calculate when we start scrolling
    int mScrollTouchSlop;
    // The page touch slop is used to calculate when we start swiping
    float mPagingTouchSlop;

    SwipeHelper mSwipeHelper;
    boolean mInterceptedBySwipeHelper;

    public TaskStackViewTouchHandler(Context context, TaskStackView sv) {
        ViewConfiguration configuration = ViewConfiguration.get(context);
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mScrollTouchSlop = configuration.getScaledTouchSlop();
        mPagingTouchSlop = configuration.getScaledPagingTouchSlop();
        mSv = sv;


        float densityScale = context.getResources().getDisplayMetrics().density;
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, densityScale, mPagingTouchSlop);
        mSwipeHelper.setMinAlpha(1f);
    }

    /** Velocity tracker helpers */
    void initOrResetVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }
    void initVelocityTrackerIfNotExists() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }
    void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    /** Returns the view at the specified coordinates */
    TaskView findViewAtPoint(int x, int y) {
        int childCount = mSv.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            TaskView tv = (TaskView) mSv.getChildAt(i);
            if (tv.getVisibility() == View.VISIBLE) {
                if (mSv.isTransformedTouchPointInView(x, y, tv)) {
                    return tv;
                }
            }
        }
        return null;
    }

    /** Touch preprocessing for handling below */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.TouchEvents,
                    "[TaskStackViewTouchHandler|interceptTouchEvent]",
                    Console.motionEventActionToString(ev.getAction()), Console.AnsiBlue);
        }

        // Return early if we have no children
        boolean hasChildren = (mSv.getChildCount() > 0);
        if (!hasChildren) {
            return false;
        }

        // Pass through to swipe helper if we are swiping
        mInterceptedBySwipeHelper = mSwipeHelper.onInterceptTouchEvent(ev);
        if (mInterceptedBySwipeHelper) {
            return true;
        }

        boolean wasScrolling = !mSv.mScroller.isFinished() ||
                (mSv.mScrollAnimator != null && mSv.mScrollAnimator.isRunning());
        int action = ev.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                // Save the touch down info
                mInitialMotionX = mLastMotionX = (int) ev.getX();
                mInitialMotionY = mLastMotionY = (int) ev.getY();
                mActivePointerId = ev.getPointerId(0);
                mActiveTaskView = findViewAtPoint(mLastMotionX, mLastMotionY);
                // Stop the current scroll if it is still flinging
                mSv.abortScroller();
                mSv.abortBoundScrollAnimation();
                // Initialize the velocity tracker
                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(ev);
                // Check if the scroller is finished yet
                mIsScrolling = !mSv.mScroller.isFinished();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == INACTIVE_POINTER_ID) break;

                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int y = (int) ev.getY(activePointerIndex);
                int x = (int) ev.getX(activePointerIndex);
                if (Math.abs(y - mInitialMotionY) > mScrollTouchSlop) {
                    // Save the touch move info
                    mIsScrolling = true;
                    // Initialize the velocity tracker if necessary
                    initVelocityTrackerIfNotExists();
                    mVelocityTracker.addMovement(ev);
                    // Disallow parents from intercepting touch events
                    final ViewParent parent = mSv.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    // Enable HW layers
                    mSv.addHwLayersRefCount("stackScroll");
                }

                mLastMotionX = x;
                mLastMotionY = y;
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                // Animate the scroll back if we've cancelled
                mSv.animateBoundScroll();
                // Disable HW layers
                if (mIsScrolling) {
                    mSv.decHwLayersRefCount("stackScroll");
                }
                // Reset the drag state and the velocity tracker
                mIsScrolling = false;
                mActivePointerId = INACTIVE_POINTER_ID;
                mActiveTaskView = null;
                mTotalScrollMotion = 0;
                recycleVelocityTracker();
                break;
            }
        }

        return wasScrolling || mIsScrolling;
    }

    /** Handles touch events once we have intercepted them */
    public boolean onTouchEvent(MotionEvent ev) {
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.TouchEvents,
                    "[TaskStackViewTouchHandler|touchEvent]",
                    Console.motionEventActionToString(ev.getAction()), Console.AnsiBlue);
        }

        // Short circuit if we have no children
        boolean hasChildren = (mSv.getChildCount() > 0);
        if (!hasChildren) {
            return false;
        }

        // Pass through to swipe helper if we are swiping
        if (mInterceptedBySwipeHelper && mSwipeHelper.onTouchEvent(ev)) {
            return true;
        }

        // Update the velocity tracker
        initVelocityTrackerIfNotExists();
        mVelocityTracker.addMovement(ev);

        int action = ev.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                // Save the touch down info
                mInitialMotionX = mLastMotionX = (int) ev.getX();
                mInitialMotionY = mLastMotionY = (int) ev.getY();
                mActivePointerId = ev.getPointerId(0);
                mActiveTaskView = findViewAtPoint(mLastMotionX, mLastMotionY);
                // Stop the current scroll if it is still flinging
                mSv.abortScroller();
                mSv.abortBoundScrollAnimation();
                // Initialize the velocity tracker
                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(ev);
                // Disallow parents from intercepting touch events
                final ViewParent parent = mSv.getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int index = ev.getActionIndex();
                mActivePointerId = ev.getPointerId(index);
                mLastMotionX = (int) ev.getX(index);
                mLastMotionY = (int) ev.getY(index);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == INACTIVE_POINTER_ID) break;

                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int x = (int) ev.getX(activePointerIndex);
                int y = (int) ev.getY(activePointerIndex);
                int yTotal = Math.abs(y - mInitialMotionY);
                int deltaY = mLastMotionY - y;
                if (!mIsScrolling) {
                    if (yTotal > mScrollTouchSlop) {
                        mIsScrolling = true;
                        // Initialize the velocity tracker
                        initOrResetVelocityTracker();
                        mVelocityTracker.addMovement(ev);
                        // Disallow parents from intercepting touch events
                        final ViewParent parent = mSv.getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                        // Enable HW layers
                        mSv.addHwLayersRefCount("stackScroll");
                    }
                }
                if (mIsScrolling) {
                    int curStackScroll = mSv.getStackScroll();
                    int overScrollAmount = mSv.getScrollAmountOutOfBounds(curStackScroll + deltaY);
                    if (overScrollAmount != 0) {
                        // Bound the overscroll to a fixed amount, and inversely scale the y-movement
                        // relative to how close we are to the max overscroll
                        float maxOverScroll = mSv.mTaskRect.height() / 3f;
                        deltaY = Math.round(deltaY * (1f - (Math.min(maxOverScroll, overScrollAmount)
                                / maxOverScroll)));
                    }
                    mSv.setStackScroll(curStackScroll + deltaY);
                    if (mSv.isScrollOutOfBounds()) {
                        mVelocityTracker.clear();
                    }
                }
                mLastMotionX = x;
                mLastMotionY = y;
                mTotalScrollMotion += Math.abs(deltaY);
                break;
            }
            case MotionEvent.ACTION_UP: {
                final VelocityTracker velocityTracker = mVelocityTracker;
                velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                int velocity = (int) velocityTracker.getYVelocity(mActivePointerId);

                if (mIsScrolling && (Math.abs(velocity) > mMinimumVelocity)) {
                    // Enable HW layers on the stack
                    mSv.addHwLayersRefCount("flingScroll");
                    // XXX: Make this animation a function of the velocity AND distance
                    int overscrollRange = (int) (Math.min(1f,
                            Math.abs((float) velocity / mMaximumVelocity)) *
                            Constants.Values.TaskStackView.TaskStackOverscrollRange);

                    if (Console.Enabled) {
                        Console.log(Constants.Log.UI.TouchEvents,
                                "[TaskStackViewTouchHandler|fling]",
                                "scroll: " + mSv.getStackScroll() + " velocity: " + velocity +
                                        " maxVelocity: " + mMaximumVelocity +
                                        " overscrollRange: " + overscrollRange,
                                Console.AnsiGreen);
                    }

                    // Fling scroll
                    mSv.mScroller.fling(0, mSv.getStackScroll(),
                            0, -velocity,
                            0, 0,
                            mSv.mMinScroll, mSv.mMaxScroll,
                            0, overscrollRange);
                    // Invalidate to kick off computeScroll
                    mSv.invalidate();
                } else if (mSv.isScrollOutOfBounds()) {
                    // Animate the scroll back into bounds
                    // XXX: Make this animation a function of the velocity OR distance
                    mSv.animateBoundScroll();
                }

                if (mIsScrolling) {
                    // Disable HW layers
                    mSv.decHwLayersRefCount("stackScroll");
                }
                mActivePointerId = INACTIVE_POINTER_ID;
                mIsScrolling = false;
                mTotalScrollMotion = 0;
                recycleVelocityTracker();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndex);
                if (pointerId == mActivePointerId) {
                    // Select a new active pointer id and reset the motion state
                    final int newPointerIndex = (pointerIndex == 0) ? 1 : 0;
                    mActivePointerId = ev.getPointerId(newPointerIndex);
                    mLastMotionX = (int) ev.getX(newPointerIndex);
                    mLastMotionY = (int) ev.getY(newPointerIndex);
                    mVelocityTracker.clear();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (mIsScrolling) {
                    // Disable HW layers
                    mSv.decHwLayersRefCount("stackScroll");
                }
                if (mSv.isScrollOutOfBounds()) {
                    // Animate the scroll back into bounds
                    // XXX: Make this animation a function of the velocity OR distance
                    mSv.animateBoundScroll();
                }
                mActivePointerId = INACTIVE_POINTER_ID;
                mIsScrolling = false;
                mTotalScrollMotion = 0;
                recycleVelocityTracker();
                break;
            }
        }
        return true;
    }

    /**** SwipeHelper Implementation ****/

    @Override
    public View getChildAtPosition(MotionEvent ev) {
        return findViewAtPoint((int) ev.getX(), (int) ev.getY());
    }

    @Override
    public boolean canChildBeDismissed(View v) {
        return true;
    }

    @Override
    public void onBeginDrag(View v) {
        // Enable HW layers
        mSv.addHwLayersRefCount("swipeBegin");
        // Disable clipping with the stack while we are swiping
        TaskView tv = (TaskView) v;
        tv.setClipViewInStack(false);
        // Disallow parents from intercepting touch events
        final ViewParent parent = mSv.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    @Override
    public void onChildDismissed(View v) {
        TaskView tv = (TaskView) v;
        mSv.onTaskDismissed(tv);

        // Re-enable clipping with the stack (we will reuse this view)
        tv.setClipViewInStack(true);

        // Disable HW layers
        mSv.decHwLayersRefCount("swipeComplete");
    }

    @Override
    public void onSnapBackCompleted(View v) {
        // Re-enable clipping with the stack
        TaskView tv = (TaskView) v;
        tv.setClipViewInStack(true);
    }

    @Override
    public void onDragCancelled(View v) {
        // Disable HW layers
        mSv.decHwLayersRefCount("swipeCancelled");
    }
}
