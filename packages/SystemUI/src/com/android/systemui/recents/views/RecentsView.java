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

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.AppTransitionAnimationSpec;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManagerGlobal;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsAppWidgetHostView;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.events.ui.DismissTaskEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragDockStateChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.List;

import static android.app.ActivityManager.INVALID_STACK_ID;

/**
 * This view is the the top level layout that contains TaskStacks (which are laid out according
 * to their SpaceNode bounds.
 */
public class RecentsView extends FrameLayout implements TaskStackView.TaskStackViewCallbacks {

    private static final String TAG = "RecentsView";

    private static final boolean ADD_HEADER_BITMAP = true;

    /** The RecentsView callbacks */
    public interface RecentsViewCallbacks {
        public void onTaskViewClicked();
        public void onTaskLaunchFailed();
        public void onAllTaskViewsDismissed();
        public void runAfterPause(Runnable r);
    }

    RecentsConfiguration mConfig;
    LayoutInflater mInflater;

    ArrayList<TaskStack> mStacks;
    TaskStackView mTaskStackView;
    RecentsAppWidgetHostView mSearchBar;
    RecentsViewCallbacks mCb;

    RecentsViewTouchHandler mTouchHandler;
    DragView mDragView;
    TaskStack.DockState[] mVisibleDockStates = {
            TaskStack.DockState.LEFT,
            TaskStack.DockState.TOP,
            TaskStack.DockState.RIGHT,
            TaskStack.DockState.BOTTOM,
    };

    Interpolator mFastOutSlowInInterpolator;

    Rect mSystemInsets = new Rect();

    public RecentsView(Context context) {
        super(context);
    }

    public RecentsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setWillNotDraw(false);
        mConfig = RecentsConfiguration.getInstance();
        mInflater = LayoutInflater.from(context);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        mTouchHandler = new RecentsViewTouchHandler(this);
    }

    /** Sets the callbacks */
    public void setCallbacks(RecentsViewCallbacks cb) {
        mCb = cb;
    }

    /** Set/get the bsp root node */
    public void setTaskStack(TaskStack stack) {
        if (mConfig.getLaunchState().launchedReuseTaskStackViews) {
            if (mTaskStackView != null) {
                // If onRecentsHidden is not triggered, we need to the stack view again here
                mTaskStackView.reset();
                mTaskStackView.setStack(stack);
            } else {
                mTaskStackView = new TaskStackView(getContext(), stack);
                mTaskStackView.setCallbacks(this);
                addView(mTaskStackView);
            }
        } else {
            if (mTaskStackView != null) {
                removeView(mTaskStackView);
            }
            mTaskStackView = new TaskStackView(getContext(), stack);
            mTaskStackView.setCallbacks(this);
            addView(mTaskStackView);
        }

        // Trigger a new layout
        requestLayout();
    }

    /** Gets the next task in the stack - or if the last - the top task */
    public Task getNextTaskOrTopTask(Task taskToSearch) {
        Task returnTask = null;
        boolean found = false;
        if (mTaskStackView != null) {
            TaskStack stack = mTaskStackView.getStack();
            ArrayList<Task> taskList = stack.getTasks();
            // Iterate the stack views and try and find the focused task
            for (int j = taskList.size() - 1; j >= 0; --j) {
                Task task = taskList.get(j);
                // Return the next task in the line.
                if (found)
                    return task;
                // Remember the first possible task as the top task.
                if (returnTask == null)
                    returnTask = task;
                if (task == taskToSearch)
                    found = true;
            }
        }
        return returnTask;
    }

    /** Launches the focused task from the first stack if possible */
    public boolean launchFocusedTask() {
        if (mTaskStackView != null) {
            TaskStack stack = mTaskStackView.getStack();
            // Iterate the stack views and try and find the focused task
            List<TaskView> taskViews = mTaskStackView.getTaskViews();
            int taskViewCount = taskViews.size();
            for (int j = 0; j < taskViewCount; j++) {
                TaskView tv = taskViews.get(j);
                Task task = tv.getTask();
                if (tv.isFocusedTask()) {
                    onTaskViewClicked(mTaskStackView, tv, stack, task, false, false, null,
                            INVALID_STACK_ID);
                    return true;
                }
            }
        }
        return false;
    }

    /** Launches a given task. */
    public boolean launchTask(Task task, Rect taskBounds, int destinationStack) {
        if (mTaskStackView != null) {
            TaskStack stack = mTaskStackView.getStack();
            // Iterate the stack views and try and find the given task.
            List<TaskView> taskViews = mTaskStackView.getTaskViews();
            int taskViewCount = taskViews.size();
            for (int j = 0; j < taskViewCount; j++) {
                TaskView tv = taskViews.get(j);
                if (tv.getTask() == task) {
                    onTaskViewClicked(mTaskStackView, tv, stack, task, false, taskBounds != null,
                            taskBounds, destinationStack);
                    return true;
                }
            }
        }
        return false;
    }

    /** Launches the task that Recents was launched from, if possible */
    public boolean launchPreviousTask() {
        if (mTaskStackView != null) {
            TaskStack stack = mTaskStackView.getStack();
            ArrayList<Task> tasks = stack.getTasks();

            // Find the launch task in the stack
            // TODO: replace this with an event from RecentsActivity
            if (!tasks.isEmpty()) {
                int taskCount = tasks.size();
                for (int j = 0; j < taskCount; j++) {
                    if (tasks.get(j).isLaunchTarget) {
                        Task task = tasks.get(j);
                        TaskView tv = mTaskStackView.getChildViewForTask(task);
                        onTaskViewClicked(mTaskStackView, tv, stack, task, false, false, null,
                                INVALID_STACK_ID);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Requests all task stacks to start their enter-recents animation */
    public void startEnterRecentsAnimation(ViewAnimation.TaskViewEnterContext ctx) {
        // We have to increment/decrement the post animation trigger in case there are no children
        // to ensure that it runs
        ctx.postAnimationTrigger.increment();
        if (mTaskStackView != null) {
            mTaskStackView.startEnterRecentsAnimation(ctx);
        }
        ctx.postAnimationTrigger.decrement();
    }

    /** Requests all task stacks to start their exit-recents animation */
    public void startExitToHomeAnimation(ViewAnimation.TaskViewExitContext ctx) {
        // We have to increment/decrement the post animation trigger in case there are no children
        // to ensure that it runs
        ctx.postAnimationTrigger.increment();
        if (mTaskStackView != null) {
            mTaskStackView.startExitToHomeAnimation(ctx);
        }
        ctx.postAnimationTrigger.decrement();

        // Notify of the exit animation
        EventBus.getDefault().send(new DismissRecentsToHomeAnimationStarted());
    }

    /** Adds the search bar */
    public void setSearchBar(RecentsAppWidgetHostView searchBar) {
        // Remove the previous search bar if one exists
        if (mSearchBar != null && indexOfChild(mSearchBar) > -1) {
            removeView(mSearchBar);
        }
        // Add the new search bar
        if (searchBar != null) {
            mSearchBar = searchBar;
            addView(mSearchBar);
        }
    }

    /** Returns whether there is currently a search bar */
    public boolean hasValidSearchBar() {
        return mSearchBar != null && !mSearchBar.isReinflateRequired();
    }

    /** Sets the visibility of the search bar */
    public void setSearchBarVisibility(int visibility) {
        if (mSearchBar != null) {
            mSearchBar.setVisibility(visibility);
            // Always bring the search bar to the top
            mSearchBar.bringToFront();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        EventBus.getDefault().register(mTouchHandler, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
        EventBus.getDefault().unregister(mTouchHandler);
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Get the search bar bounds and measure the search bar layout
        Rect searchBarSpaceBounds = new Rect();
        if (mSearchBar != null) {
            mConfig.getSearchBarBounds(new Rect(0, 0, width, height), mSystemInsets.top,
                    searchBarSpaceBounds);
            mSearchBar.measure(
                    MeasureSpec.makeMeasureSpec(searchBarSpaceBounds.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(searchBarSpaceBounds.height(), MeasureSpec.EXACTLY));
        }

        Rect taskStackBounds = new Rect();
        mConfig.getTaskStackBounds(new Rect(0, 0, width, height), mSystemInsets.top,
                mSystemInsets.right, searchBarSpaceBounds, taskStackBounds);
        if (mTaskStackView != null && mTaskStackView.getVisibility() != GONE) {
            mTaskStackView.setTaskStackBounds(taskStackBounds, mSystemInsets);
            mTaskStackView.measure(widthMeasureSpec, heightMeasureSpec);
        }

        if (mDragView != null) {
            mDragView.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
        }

        setMeasuredDimension(width, height);
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // Get the search bar bounds so that we lay it out
        Rect measuredRect = new Rect(0, 0, getMeasuredWidth(), getMeasuredHeight());
        Rect searchBarSpaceBounds = new Rect();
        if (mSearchBar != null) {
            mConfig.getSearchBarBounds(measuredRect,
                    mSystemInsets.top, searchBarSpaceBounds);
            mSearchBar.layout(searchBarSpaceBounds.left, searchBarSpaceBounds.top,
                    searchBarSpaceBounds.right, searchBarSpaceBounds.bottom);
        }

        if (mTaskStackView != null && mTaskStackView.getVisibility() != GONE) {
            mTaskStackView.layout(left, top, left + getMeasuredWidth(), top + getMeasuredHeight());
        }

        if (mDragView != null) {
            mDragView.layout(left, top, left + mDragView.getMeasuredWidth(),
                    top + mDragView.getMeasuredHeight());
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mSystemInsets.set(insets.getSystemWindowInsets());
        requestLayout();
        return insets.consumeSystemWindowInsets();
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
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        for (int i = mVisibleDockStates.length - 1; i >= 0; i--) {
            Drawable d = mVisibleDockStates[i].viewState.dockAreaOverlay;
            if (d.getAlpha() > 0) {
                d.draw(canvas);
            }
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        for (int i = mVisibleDockStates.length - 1; i >= 0; i--) {
            Drawable d = mVisibleDockStates[i].viewState.dockAreaOverlay;
            if (d == who) {
                return true;
            }
        }
        return super.verifyDrawable(who);
    }

    /** Focuses the next task in the first stack view */
    public void focusNextTask(boolean forward) {
        // Get the first stack view
        if (mTaskStackView != null) {
            mTaskStackView.focusNextTask(forward, true);
        }
    }

    /** Dismisses the focused task. */
    public void dismissFocusedTask() {
        // Get the first stack view
        if (mTaskStackView != null) {
            mTaskStackView.dismissFocusedTask();
        }
    }

    /** Unfilters any filtered stacks */
    public boolean unfilterFilteredStacks() {
        if (mStacks != null) {
            // Check if there are any filtered stacks and unfilter them before we back out of Recents
            boolean stacksUnfiltered = false;
            int numStacks = mStacks.size();
            for (int i = 0; i < numStacks; i++) {
                TaskStack stack = mStacks.get(i);
                if (stack.hasFilteredTasks()) {
                    stack.unfilterTasks();
                    stacksUnfiltered = true;
                }
            }
            return stacksUnfiltered;
        }
        return false;
    }

    public void disableLayersForOneFrame() {
        if (mTaskStackView != null) {
            mTaskStackView.disableLayersForOneFrame();
        }
    }

    private void postDrawHeaderThumbnailTransitionRunnable(final TaskStackView view,
            final TaskView clickedView, final int offsetX, final int offsetY,
            final float stackScroll,
            final ActivityOptions.OnAnimationStartedListener animStartedListener,
            final int destinationStack) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                overrideDrawHeaderThumbnailTransition(view, clickedView, offsetX, offsetY,
                        stackScroll, animStartedListener, destinationStack);

            }
        };

        mCb.runAfterPause(r);
    }

    private void overrideDrawHeaderThumbnailTransition(TaskStackView stackView,
            TaskView clickedTask, int offsetX, int offsetY, float stackScroll,
            final ActivityOptions.OnAnimationStartedListener animStartedListener,
            int destinationStack) {
        List<AppTransitionAnimationSpec> specs = getAppTransitionAnimationSpecs(stackView,
                clickedTask, offsetX, offsetY, stackScroll, destinationStack);
        if (specs == null) {
            return;
        }

        IRemoteCallback.Stub callback = new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) throws RemoteException {
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (animStartedListener != null) {
                            animStartedListener.onAnimationStarted();
                        }
                    }
                });
            }
        };

        AppTransitionAnimationSpec[] specsArray =
                new AppTransitionAnimationSpec[specs.size()];
        try {
            WindowManagerGlobal.getWindowManagerService().overridePendingAppTransitionMultiThumb(
                    specs.toArray(specsArray), callback, true /* scaleUp */);

        } catch (RemoteException e) {
            Log.w(TAG, "Error overriding app transition", e);
        }
    }

    private List<AppTransitionAnimationSpec> getAppTransitionAnimationSpecs(TaskStackView stackView,
            TaskView clickedTask, int offsetX, int offsetY, float stackScroll,
            int destinationStack) {
        final int targetStackId = destinationStack != INVALID_STACK_ID ?
                destinationStack : clickedTask.getTask().key.stackId;
        if (targetStackId != ActivityManager.FREEFORM_WORKSPACE_STACK_ID
                && targetStackId != ActivityManager.FULLSCREEN_WORKSPACE_STACK_ID) {
            return null;
        }
        // If this is a full screen stack, the transition will be towards the single, full screen
        // task. We only need the transition spec for this task.
        List<AppTransitionAnimationSpec> specs = new ArrayList<>();
        if (targetStackId == ActivityManager.FULLSCREEN_WORKSPACE_STACK_ID) {
            specs.add(createThumbnailHeaderAnimationSpec(
                    stackView, offsetX, offsetY, stackScroll, clickedTask,
                    clickedTask.getTask().key.id, ADD_HEADER_BITMAP));
            return specs;
        }
        // This is a free form stack or full screen stack, so there will be multiple windows
        // animating from thumbnails. We need transition animation specs for all of them.

        // We will use top and bottom task views as a base for tasks, that aren't visible on the
        // screen. This is necessary for cascade recents list, where some of the tasks might be
        // hidden.
        List<TaskView> taskViews = stackView.getTaskViews();
        int childCount = taskViews.size();
        TaskView topChild = taskViews.get(0);
        TaskView bottomChild = taskViews.get(childCount - 1);
        SparseArray<TaskView> taskViewsByTaskId = new SparseArray<>();
        for (int i = 0; i < childCount; i++) {
            TaskView taskView = taskViews.get(i);
            taskViewsByTaskId.put(taskView.getTask().key.id, taskView);
        }

        TaskStack stack = stackView.getStack();
        // We go through all tasks now and for each generate transition animation spec. If there is
        // a view associated with a task, we use that view as a base for the animation. If there
        // isn't, we use bottom or top view, depending on which one would be closer to the task
        // view if it existed.
        ArrayList<Task> tasks = stack.getTasks();
        boolean passedClickedTask = false;
        for (int i = 0, n = tasks.size(); i < n; i++) {
            Task task = tasks.get(i);
            TaskView taskView = taskViewsByTaskId.get(task.key.id);
            if (taskView != null) {
                specs.add(createThumbnailHeaderAnimationSpec(stackView, offsetX, offsetY,
                        stackScroll, taskView, taskView.getTask().key.id, ADD_HEADER_BITMAP));
                if (taskView == clickedTask) {
                    passedClickedTask = true;
                }
            } else {
                taskView = passedClickedTask ? bottomChild : topChild;
                specs.add(createThumbnailHeaderAnimationSpec(stackView, offsetX, offsetY,
                        stackScroll, taskView, task.key.id, !ADD_HEADER_BITMAP));
            }
        }

        return specs;
    }

    private AppTransitionAnimationSpec createThumbnailHeaderAnimationSpec(TaskStackView stackView,
            int offsetX, int offsetY, float stackScroll, TaskView tv, int taskId,
            boolean addHeaderBitmap) {
        // Disable any focused state before we draw the header
        // Upfront the processing of the thumbnail
        if (tv.isFocusedTask()) {
            tv.unsetFocusedTask();
        }
        TaskViewTransform transform = new TaskViewTransform();
        transform = stackView.getStackAlgorithm().getStackTransform(tv.mTask, stackScroll,
                transform, null);

        float scale = tv.getScaleX();
        int fromHeaderWidth = (int) (tv.mHeaderView.getMeasuredWidth() * scale);
        int fromHeaderHeight = (int) (tv.mHeaderView.getMeasuredHeight() * scale);

        Bitmap b = null;
        if (addHeaderBitmap) {
            b = Bitmap.createBitmap(fromHeaderWidth, fromHeaderHeight,
                    Bitmap.Config.ARGB_8888);

            if (Constants.DebugFlags.App.EnableTransitionThumbnailDebugMode) {
                b.eraseColor(0xFFff0000);
            } else {
                Canvas c = new Canvas(b);
                c.scale(tv.getScaleX(), tv.getScaleY());
                tv.mHeaderView.draw(c);
                c.setBitmap(null);

            }
            b = b.createAshmemBitmap();
        }

        int[] pts = new int[2];
        tv.getLocationOnScreen(pts);

        final int left = pts[0] + offsetX;
        final int top = pts[1] + offsetY;
        final Rect rect = new Rect(left, top, left + (int) transform.rect.width(),
                top + (int) transform.rect.height());

        return new AppTransitionAnimationSpec(taskId, b, rect);
    }

    /**** TaskStackView.TaskStackCallbacks Implementation ****/

    @Override
    public void onTaskViewClicked(final TaskStackView stackView, final TaskView tv,
            final TaskStack stack, final Task task, final boolean lockToTask,
            final boolean boundsValid, final Rect bounds, int destinationStack) {
        // Notify any callbacks of the launching of a new task
        if (mCb != null) {
            mCb.onTaskViewClicked();
        }

        // Upfront the processing of the thumbnail
        TaskViewTransform transform = new TaskViewTransform();
        View sourceView;
        int offsetX = 0;
        int offsetY = 0;
        float stackScroll = stackView.getScroller().getStackScroll();
        if (tv == null) {
            // If there is no actual task view, then use the stack view as the source view
            // and then offset to the expected transform rect, but bound this to just
            // outside the display rect (to ensure we don't animate from too far away)
            sourceView = stackView;
            offsetX = (int) transform.rect.left;
            offsetY = getMeasuredHeight();
        } else {
            sourceView = tv.mThumbnailView;
        }

        // Compute the thumbnail to scale up from
        final SystemServicesProxy ssp = Recents.getSystemServices();
        ActivityOptions opts = null;
        ActivityOptions.OnAnimationStartedListener animStartedListener = null;
        if (task.thumbnail != null && task.thumbnail.getWidth() > 0 &&
                task.thumbnail.getHeight() > 0) {
            if (lockToTask) {
                animStartedListener = new ActivityOptions.OnAnimationStartedListener() {
                    boolean mTriggered = false;
                    @Override
                    public void onAnimationStarted() {
                        if (!mTriggered) {
                            postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    EventBus.getDefault().send(new ScreenPinningRequestEvent(
                                            getContext(), ssp));
                                }
                            }, 350);
                            mTriggered = true;
                        }
                    }
                };
            }
            postDrawHeaderThumbnailTransitionRunnable(stackView, tv, offsetX, offsetY, stackScroll,
                    animStartedListener, destinationStack);
            opts = ActivityOptions.makeThumbnailAspectScaleUpAnimation(sourceView,
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8).createAshmemBitmap(),
                    offsetX, offsetY, (int) transform.rect.width(), (int) transform.rect.height(),
                    sourceView.getHandler(), animStartedListener);
        } else {
            opts = ActivityOptions.makeBasic();
        }
        if (boundsValid) {
            opts.setBounds(bounds.isEmpty() ? null : bounds);
        }
        final ActivityOptions launchOpts = opts;
        final boolean screenPinningRequested = (animStartedListener == null) && lockToTask;
        final Runnable launchRunnable = new Runnable() {
            @Override
            public void run() {
                if (task.isActive) {
                    // Bring an active task to the foreground
                    ssp.moveTaskToFront(task.key.id, launchOpts);
                } else {
                    if (ssp.startActivityFromRecents(getContext(), task.key.id,
                            task.activityLabel, launchOpts)) {
                        if (screenPinningRequested) {
                            EventBus.getDefault().send(new ScreenPinningRequestEvent(
                                    getContext(), ssp));
                        }
                    } else {
                        // Dismiss the task and return the user to home if we fail to
                        // launch the task
                        EventBus.getDefault().send(new DismissTaskEvent(task, tv));
                        if (mCb != null) {
                            mCb.onTaskLaunchFailed();
                        }

                        // Keep track of failed launches
                        MetricsLogger.count(getContext(), "overview_task_launch_failed", 1);
                    }
                }
            }
        };

        // Keep track of the index of the task launch
        int taskIndexFromFront = 0;
        int taskIndex = stack.indexOfTask(task);
        if (taskIndex > -1) {
            taskIndexFromFront = stack.getTaskCount() - taskIndex - 1;
        }
        MetricsLogger.histogram(getContext(), "overview_task_launch_index", taskIndexFromFront);

        // Launch the app right away if there is no task view, otherwise, animate the icon out first
        if (tv == null) {
            launchRunnable.run();
        } else {
            if (task.group != null && !task.group.isFrontMostTask(task)) {
                // For affiliated tasks that are behind other tasks, we must animate the front cards
                // out of view before starting the task transition
                stackView.startLaunchTaskAnimation(tv, launchRunnable, lockToTask);
            } else {
                // Otherwise, we can start the task transition immediately
                stackView.startLaunchTaskAnimation(tv, null, lockToTask);
                launchRunnable.run();
            }
        }
    }

    @Override
    public void onAllTaskViewsDismissed(ArrayList<Task> removedTasks) {
        /* TODO: Not currently enabled
        if (removedTasks != null) {
            int taskCount = removedTasks.size();
            for (int i = 0; i < taskCount; i++) {
                onTaskViewDismissed(removedTasks.get(i));
            }
        }
        */

        mCb.onAllTaskViewsDismissed();

        // Keep track of all-deletions
        MetricsLogger.count(getContext(), "overview_task_all_dismissed", 1);
    }

    @Override
    public void onTaskStackFilterTriggered() {
        // Hide the search bar
        if (mSearchBar != null) {
            int filterDuration = getResources().getInteger(
                    R.integer.recents_filter_animate_current_views_duration);
            mSearchBar.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setInterpolator(mFastOutSlowInInterpolator)
                    .setDuration(filterDuration)
                    .withLayer()
                    .start();
        }
    }

    @Override
    public void onTaskStackUnfilterTriggered() {
        // Show the search bar
        if (mSearchBar != null) {
            int filterDuration = getResources().getInteger(
                    R.integer.recents_filter_animate_new_views_duration);
            mSearchBar.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setInterpolator(mFastOutSlowInInterpolator)
                    .setDuration(filterDuration)
                    .withLayer()
                    .start();
        }
    }

    /**** EventBus Events ****/

    public final void onBusEvent(DragStartEvent event) {
        // Add the drag view
        mDragView = event.dragView;
        addView(mDragView);

        updateVisibleDockRegions(mTouchHandler.getDockStatesForCurrentOrientation(),
                TaskStack.DockState.NONE.viewState.dockAreaAlpha);
    }

    public final void onBusEvent(DragDockStateChangedEvent event) {
        if (event.dockState == TaskStack.DockState.NONE) {
            updateVisibleDockRegions(mTouchHandler.getDockStatesForCurrentOrientation(),
                    TaskStack.DockState.NONE.viewState.dockAreaAlpha);
        } else {
            updateVisibleDockRegions(new TaskStack.DockState[] {event.dockState}, -1);
        }
    }

    public final void onBusEvent(final DragEndEvent event) {
        event.postAnimationTrigger.increment();
        event.postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
            @Override
            public void run() {
                // Remove the drag view
                removeView(mDragView);
                mDragView = null;
                updateVisibleDockRegions(null, -1);

                // Dock the new task if we are hovering over a valid dock state
                if (event.dockState != TaskStack.DockState.NONE) {
                    SystemServicesProxy ssp = Recents.getSystemServices();
                    ssp.startTaskInDockedMode(event.task.key.id, event.dockState.createMode);
                    launchTask(event.task, null, INVALID_STACK_ID);
                }
            }
        });
        if (event.dockState == TaskStack.DockState.NONE) {
            // Animate the alpha back to what it was before
            Rect taskBounds = mTaskStackView.getStackAlgorithm().getUntransformedTaskViewBounds();
            int left = taskBounds.left + (int) ((1f - event.taskView.getScaleX()) * taskBounds.width()) / 2;
            int top = taskBounds.top + (int) ((1f - event.taskView.getScaleY()) * taskBounds.height()) / 2;
            event.dragView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(left + event.taskView.getTranslationX())
                    .translationY(top + event.taskView.getTranslationY())
                    .setDuration(175)
                    .setInterpolator(mFastOutSlowInInterpolator)
                    .withEndAction(event.postAnimationTrigger.decrementAsRunnable())
                    .start();

            // Animate the overlay alpha back to 0
            updateVisibleDockRegions(null, -1);
        } else {
            event.postAnimationTrigger.decrement();
        }
    }

    /**
     * Updates the dock region to match the specified dock state.
     */
    private void updateVisibleDockRegions(TaskStack.DockState[] newDockStates, int overrideAlpha) {
        ArraySet<TaskStack.DockState> newDockStatesSet = new ArraySet<>();
        if (newDockStates != null) {
            for (TaskStack.DockState dockState : newDockStates) {
                newDockStatesSet.add(dockState);
            }
        }
        for (TaskStack.DockState dockState : mVisibleDockStates) {
            TaskStack.DockState.ViewState viewState = dockState.viewState;
            if (newDockStates == null || !newDockStatesSet.contains(dockState)) {
                // This is no longer visible, so hide it
                viewState.startAlphaAnimation(0, 150);
            } else {
                // This state is now visible, update the bounds and show it
                int alpha = (overrideAlpha != -1 ? overrideAlpha : viewState.dockAreaAlpha);
                viewState.dockAreaOverlay.setBounds(
                        dockState.getDockedBounds(getMeasuredWidth(), getMeasuredHeight()));
                viewState.dockAreaOverlay.setCallback(this);
                viewState.startAlphaAnimation(alpha, 150);
            }
        }
    }
}
