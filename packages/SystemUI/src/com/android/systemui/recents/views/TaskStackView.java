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
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.OverScroller;
import com.android.systemui.recents.Console;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsTaskLoader;
import com.android.systemui.recents.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;


/* The visual representation of a task stack view */
public class TaskStackView extends FrameLayout implements TaskStack.TaskStackCallbacks,
        TaskView.TaskViewCallbacks, ViewPool.ViewPoolConsumer<TaskView, Task>,
        View.OnClickListener {

    /** The TaskView callbacks */
    interface TaskStackViewCallbacks {
        public void onTaskLaunched(TaskStackView stackView, TaskView tv, TaskStack stack, Task t);
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
    OverScroller mScroller;
    ObjectAnimator mScrollAnimator;

    // Optimizations
    int mHwLayersRefCount;
    int mStackViewsAnimationDuration;
    boolean mStackViewsDirty = true;
    boolean mAwaitingFirstLayout = true;

    public TaskStackView(Context context, TaskStack stack) {
        super(context);
        mStack = stack;
        mStack.setCallbacks(this);
        mScroller = new OverScroller(context);
        mTouchHandler = new TaskStackViewTouchHandler(context, this);
        mViewPool = new ViewPool<TaskView, Task>(context, this);
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
        Console.log(Constants.DebugFlags.TaskStack.SynchronizeViewsWithModel,
                "[TaskStackView|requestSynchronize]", "", Console.AnsiYellow);
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
    public TaskViewTransform getStackTransform(int indexInStack) {
        TaskViewTransform transform = new TaskViewTransform();

        // Map the items to an continuous position relative to the current scroll
        int numPeekCards = Constants.Values.TaskStackView.StackPeekNumCards;
        float overlapHeight = Constants.Values.TaskStackView.StackOverlapPct * mTaskRect.height();
        float peekHeight = Constants.Values.TaskStackView.StackPeekHeightPct * mStackRect.height();
        float t = ((indexInStack * overlapHeight) - getStackScroll()) / overlapHeight;
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

        // Update the rect and visibility
        transform.rect.set(mTaskRect);
        if (t < -(numPeekCards + 1)) {
            transform.visible = false;
        } else {
            transform.rect.offset(0, transform.translationY);
            Utilities.scaleRectAboutCenter(transform.rect, scale);
            transform.visible = Rect.intersects(mRect, transform.rect);
        }
        transform.t = t;
        return transform;
    }

    /** Synchronizes the views with the model */
    void synchronizeStackViewsWithModel() {
        Console.log(Constants.DebugFlags.TaskStack.SynchronizeViewsWithModel,
                "[TaskStackView|synchronizeViewsWithModel]",
                "mStackViewsDirty: " + mStackViewsDirty, Console.AnsiYellow);
        if (mStackViewsDirty) {

            // XXX: Optimization: Use binary search to find the visible range
            // XXX: Optimize to not call getStackTransform() so many times
            // XXX: Consider using TaskViewTransform pool to prevent allocations
            // XXX: Iterate children views, update transforms and remove all that are not visible
            //      For all remaining tasks, update transforms and if visible add the view

            // Update the visible state of all the tasks
            ArrayList<Task> tasks = mStack.getTasks();
            int taskCount = tasks.size();
            for (int i = 0; i < taskCount; i++) {
                Task task = tasks.get(i);
                TaskViewTransform transform = getStackTransform(i);
                TaskView tv = getChildViewForTask(task);

                if (transform.visible) {
                    if (tv == null) {
                        tv = mViewPool.pickUpViewFromPool(task, task);
                        // When we are picking up a new view from the view pool, prepare it for any
                        // following animation by putting it in a reasonable place
                        if (mStackViewsAnimationDuration > 0 && i != 0) {
                            // XXX: We have to animate when filtering, etc. Maybe we should have a
                            //      runnable that ensures that tasks are animated in a special way
                            //      when they are entering the scene?
                            int fromIndex = (transform.t < 0) ? (i - 1) : (i + 1);
                            tv.updateViewPropertiesFromTask(null, getStackTransform(fromIndex), 0);
                        }
                    }
                } else {
                    if (tv != null) {
                        mViewPool.returnViewToPool(tv);
                    }
                }
            }

            // Update all the current view children
            // NOTE: We have to iterate in reverse where because we are removing views directly
            int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                TaskView tv = (TaskView) getChildAt(i);
                Task task = tv.getTask();
                TaskViewTransform transform = getStackTransform(mStack.indexOfTask(task));
                if (!transform.visible) {
                    mViewPool.returnViewToPool(tv);
                } else {
                    tv.updateViewPropertiesFromTask(null, transform, mStackViewsAnimationDuration);
                }
            }

            Console.log(Constants.DebugFlags.TaskStack.SynchronizeViewsWithModel,
                    "  [TaskStackView|viewChildren]", "" + getChildCount());

            mStackViewsAnimationDuration = 0;
            mStackViewsDirty = false;
        }
    }

    /** Sets the current stack scroll */
    public void setStackScroll(int value) {
        mStackScroll = value;
        requestSynchronizeStackViewsWithModel();
    }

    /** Gets the current stack scroll */
    public int getStackScroll() {
        return mStackScroll;
    }

    /** Animates the stack scroll into bounds */
    ObjectAnimator animateBoundScroll(int duration) {
        int curScroll = getStackScroll();
        int newScroll = Math.max(mMinScroll, Math.min(mMaxScroll, curScroll));
        if (newScroll != curScroll) {
            // Enable hw layers on the stack
            addHwLayersRefCount("animateBoundScroll");

            // Abort any current animations
            abortScroller();
            if (mScrollAnimator != null) {
                mScrollAnimator.cancel();
                mScrollAnimator.removeAllListeners();
            }

            // Start a new scroll animation
            mScrollAnimator = ObjectAnimator.ofInt(this, "stackScroll", curScroll, newScroll);
            mScrollAnimator.setDuration(duration);
            mScrollAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    setStackScroll((Integer) animation.getAnimatedValue());
                }
            });
            mScrollAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // Disable hw layers on the stack
                    decHwLayersRefCount("animateBoundScroll");
                }
            });
            mScrollAnimator.start();
        }
        return mScrollAnimator;
    }

    /** Aborts any current stack scrolls */
    void abortBoundScrollAnimation() {
        if (mScrollAnimator != null) {
            mScrollAnimator.cancel();
        }
    }

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

    /** Returns whether the current scroll is out of bounds */
    boolean isScrollOutOfBounds() {
        return (getStackScroll() < 0) || (getStackScroll() > mMaxScroll);
    }

    /** Updates the min and max virtual scroll bounds */
    void updateMinMaxScroll(boolean boundScrollToNewMinMax) {
        // Compute the min and max scroll values
        int numTasks = Math.max(1, mStack.getTaskCount());
        int taskHeight = mTaskRect.height();
        int stackHeight = mStackRectSansPeek.height();
        int maxScrollHeight = taskHeight + (int) ((numTasks - 1) *
                Constants.Values.TaskStackView.StackOverlapPct * taskHeight);
        mMinScroll = Math.min(stackHeight, maxScrollHeight) - stackHeight;
        mMaxScroll = maxScrollHeight - stackHeight;

        // Debug logging
        if (Constants.DebugFlags.UI.MeasureAndLayout) {
            Console.log("  [TaskStack|minScroll] " + mMinScroll);
            Console.log("  [TaskStack|maxScroll] " + mMaxScroll);
        }

        if (boundScrollToNewMinMax) {
            boundScroll();
        }
    }

    /** Enables the hw layers and increments the hw layer requirement ref count */
    void addHwLayersRefCount(String reason) {
        Console.log(Constants.DebugFlags.UI.HwLayers,
                "[TaskStackView|addHwLayersRefCount] refCount: " +
                        mHwLayersRefCount + "->" + (mHwLayersRefCount + 1) + " " + reason);
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
        Console.log(Constants.DebugFlags.UI.HwLayers,
                "[TaskStackView|decHwLayersRefCount] refCount: " +
                        mHwLayersRefCount + "->" + (mHwLayersRefCount - 1) + " " + reason);
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
        Console.log(Constants.DebugFlags.UI.Draw, "[TaskStackView|dispatchDraw]", "",
                Console.AnsiPurple);
        synchronizeStackViewsWithModel();
        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (Constants.DebugFlags.App.EnableTaskStackClipping) {
            TaskView tv = (TaskView) child;
            TaskView nextTv = null;
            int curIndex = indexOfChild(tv);
            if (curIndex < (getChildCount() - 1)) {
                // Clip against the next view (if we aren't animating its alpha)
                nextTv = (TaskView) getChildAt(curIndex + 1);
                if (nextTv.getAlpha() == 1f) {
                    Rect curRect = tv.getClippingRect(Utilities.tmpRect, false);
                    Rect nextRect = nextTv.getClippingRect(Utilities.tmpRect2, true);
                    RecentsConfiguration config = RecentsConfiguration.getInstance();
                    // The hit rects are relative to the task view, which needs to be offset by the
                    // system bar height
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
    public void computeRects(int width, int height) {
        // Note: We let the stack view be the full height because we want the cards to go under the
        //       navigation bar if possible.  However, the stack rects which we use to calculate
        //       max scroll, etc. need to take the nav bar into account

        // Compute the stack rects
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        mRect.set(0, 0, width, height);
        mStackRect.set(mRect);
        mStackRect.bottom -= config.systemInsets.bottom;

        int smallestDimension = Math.min(width, height);
        int padding = (int) (Constants.Values.TaskStackView.StackPaddingPct * smallestDimension / 2f);
        mStackRect.inset(padding, padding);
        mStackRectSansPeek.set(mStackRect);
        mStackRectSansPeek.top += Constants.Values.TaskStackView.StackPeekHeightPct * mStackRect.height();

        // Compute the task rect
        int minHeight = (int) (mStackRect.height() -
                (Constants.Values.TaskStackView.StackPeekHeightPct * mStackRect.height()));
        int size = Math.min(minHeight, Math.min(mStackRect.width(), mStackRect.height()));
        int centerX = mStackRect.centerX();
        mTaskRect.set(centerX - size / 2, mStackRectSansPeek.top,
                centerX + size / 2, mStackRectSansPeek.top + size);

        // Update the scroll bounds
        updateMinMaxScroll(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        Console.log(Constants.DebugFlags.UI.MeasureAndLayout, "[TaskStackView|measure]",
                "width: " + width + " height: " + height +
                " awaitingFirstLayout: " + mAwaitingFirstLayout, Console.AnsiGreen);

        // Compute our stack/task rects
        computeRects(width, height);

        // Debug logging
        if (Constants.DebugFlags.UI.MeasureAndLayout) {
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

            // Animate the icon of the first task view
            if (Constants.Values.TaskView.AnimateFrontTaskIconOnEnterRecents) {
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

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        Console.log(Constants.DebugFlags.UI.MeasureAndLayout, "[TaskStackView|layout]",
                "" + new Rect(left, top, right, bottom), Console.AnsiGreen);

        // Debug logging
        if (Constants.DebugFlags.UI.MeasureAndLayout) {
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

        if (!mAwaitingFirstLayout) {
            requestSynchronizeStackViewsWithModel();
        } else {
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

        updateMinMaxScroll(true);
        requestSynchronizeStackViewsWithModel(Constants.Values.TaskStackView.Animation.TaskRemovedReshuffleDuration);
    }

    @Override
    public void onStackFiltered(TaskStack stack) {
        requestSynchronizeStackViewsWithModel();
    }

    @Override
    public void onStackUnfiltered(TaskStack stack) {
        requestSynchronizeStackViewsWithModel();
    }

    /**** ViewPoolConsumer Implementation ****/

    @Override
    public TaskView createView(Context context) {
        Console.log(Constants.DebugFlags.ViewPool.PoolCallbacks, "[TaskStackView|createPoolView]");
        return new TaskView(context);
    }

    @Override
    public void prepareViewToEnterPool(TaskView tv) {
        Task task = tv.getTask();
        tv.resetViewProperties();
        Console.log(Constants.DebugFlags.ViewPool.PoolCallbacks, "[TaskStackView|returnToPool]",
                tv.getTask() + " tv: " + tv);

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
        Console.log(Constants.DebugFlags.ViewPool.PoolCallbacks, "[TaskStackView|leavePool]",
                "isNewView: " + isNewView);

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

        // Add/attach the view to the hierarchy
        Console.log(Constants.DebugFlags.ViewPool.PoolCallbacks, "  [TaskStackView|insertIndex]",
                "" + insertIndex);
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
        Console.log(Constants.DebugFlags.UI.ClickEvents, "[TaskStack|Clicked|Icon]",
                tv.getTask() + " is currently filtered: " + mStack.hasFilteredTasks(),
                Console.AnsiCyan);
        if (Constants.DebugFlags.App.EnableTaskFiltering) {
            if (mStack.hasFilteredTasks()) {
                mStack.unfilterTasks();
            } else {
                mStack.filterTasks(tv.getTask());
            }
        } else {
            Console.logError(getContext(), "Task Filtering TBD");
        }
    }

    /**** View.OnClickListener Implementation ****/

    @Override
    public void onClick(View v) {
        TaskView tv = (TaskView) v;
        Task task = tv.getTask();
        Console.log(Constants.DebugFlags.UI.ClickEvents, "[TaskStack|Clicked|Thumbnail]",
                task + " cb: " + mCb);

        if (mCb != null) {
            mCb.onTaskLaunched(this, tv, mStack, task);
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
        Console.log(Constants.DebugFlags.UI.TouchEvents,
                "[TaskStackViewTouchHandler|interceptTouchEvent]",
                Console.motionEventActionToString(ev.getAction()), Console.AnsiBlue);

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
                mSv.animateBoundScroll(Constants.Values.TaskStackView.Animation.SnapScrollBackDuration);
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
        Console.log(Constants.DebugFlags.TaskStack.SynchronizeViewsWithModel,
                "[TaskStackViewTouchHandler|touchEvent]",
                Console.motionEventActionToString(ev.getAction()), Console.AnsiBlue);

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
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == INACTIVE_POINTER_ID) break;

                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int x = (int) ev.getX(activePointerIndex);
                int y = (int) ev.getY(activePointerIndex);
                int deltaY = mLastMotionY - y;
                if (!mIsScrolling) {
                    if (Math.abs(y - mInitialMotionY) > mScrollTouchSlop) {
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
                    mSv.setStackScroll(mSv.getStackScroll() + deltaY);
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
                    int overscrollRange = (int) (Math.min(1f,
                            Math.abs((float) velocity / mMaximumVelocity)) *
                            Constants.Values.TaskStackView.TaskStackOverscrollRange);

                    Console.log(Constants.DebugFlags.UI.TouchEvents,
                            "[TaskStackViewTouchHandler|fling]",
                            "scroll: " + mSv.getStackScroll() + " velocity: " + velocity +
                                    " maxVelocity: " + mMaximumVelocity +
                                    " overscrollRange: " + overscrollRange,
                            Console.AnsiGreen);

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
                    mSv.animateBoundScroll(Constants.Values.TaskStackView.Animation.SnapScrollBackDuration);
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
            case MotionEvent.ACTION_CANCEL: {
                if (mIsScrolling) {
                    // Disable HW layers
                    mSv.decHwLayersRefCount("stackScroll");
                }
                if (mSv.isScrollOutOfBounds()) {
                    // Animate the scroll back into bounds
                    // XXX: Make this animation a function of the velocity OR distance
                    mSv.animateBoundScroll(Constants.Values.TaskStackView.Animation.SnapScrollBackDuration);
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
        // Disallow parents from intercepting touch events
        final ViewParent parent = mSv.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    @Override
    public void onChildDismissed(View v) {
        TaskView tv = (TaskView) v;
        Task task = tv.getTask();
        Activity activity = (Activity) mSv.getContext();

        // We have to disable the listener to ensure that we
        // don't hit this again
        tv.animate().setListener(null);

        // Remove the task from the view
        mSv.mStack.removeTask(task);

        // Remove any stored data from the loader
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        loader.deleteTaskData(task);

        // Remove the task from activity manager
        final ActivityManager am = (ActivityManager)
                activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            am.removeTask(tv.getTask().key.id,
                    ActivityManager.REMOVE_TASK_KILL_PROCESS);
        }

        // If there are no remaining tasks, then just close the activity
        if (mSv.mStack.getTaskCount() == 0) {
            activity.finish();
        }

        // Disable HW layers
        mSv.decHwLayersRefCount("swipeComplete");
    }

    @Override
    public void onSnapBackCompleted(View v) {
        // Do Nothing
    }

    @Override
    public void onDragCancelled(View v) {
        // Disable HW layers
        mSv.decHwLayersRefCount("swipeCancelled");
    }
}
