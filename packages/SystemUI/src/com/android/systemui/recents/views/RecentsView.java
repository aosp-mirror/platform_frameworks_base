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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.AppTransitionAnimationSpec;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsAppWidgetHostView;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.CancelEnterRecentsWindowAnimationEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEndedEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragDropTargetChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.List;

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

/**
 * This view is the the top level layout that contains TaskStacks (which are laid out according
 * to their SpaceNode bounds.
 */
public class RecentsView extends FrameLayout implements TaskStackView.TaskStackViewCallbacks {

    private static final String TAG = "RecentsView";
    private static final boolean DEBUG = false;

    LayoutInflater mInflater;
    Handler mHandler;

    TaskStackView mTaskStackView;
    RecentsAppWidgetHostView mSearchBar;
    boolean mAwaitingFirstLayout = true;
    boolean mLastTaskLaunchedWasFreeform;

    RecentsTransitionHelper mTransitionHelper;
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
        mInflater = LayoutInflater.from(context);
        mHandler = new Handler();
        mTransitionHelper = new RecentsTransitionHelper(getContext(), mHandler);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        mTouchHandler = new RecentsViewTouchHandler(this);
    }

    /** Set/get the bsp root node */
    public void setTaskStack(TaskStack stack) {
        RecentsConfiguration config = Recents.getConfiguration();
        if (config.getLaunchState().launchedReuseTaskStackViews) {
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

    /**
     * Returns whether the last task launched was in the freeform stack or not.
     */
    public boolean isLastTaskLaunchedFreeform() {
        return mLastTaskLaunchedWasFreeform;
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
            Task task = mTaskStackView.getFocusedTask();
            if (task != null) {
                TaskView taskView = mTaskStackView.getChildViewForTask(task);
                onTaskViewClicked(mTaskStackView, taskView, stack, task, false, null,
                        INVALID_STACK_ID);
                return true;
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
                    onTaskViewClicked(mTaskStackView, tv, stack, task, false,
                            taskBounds, destinationStack);
                    return true;
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

        // If we are going home, cancel the previous task's window transition
        EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(null));

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
        RecentsConfiguration config = Recents.getConfiguration();
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Get the search bar bounds and measure the search bar layout
        Rect searchBarSpaceBounds = new Rect();
        if (mSearchBar != null) {
            config.getSearchBarBounds(new Rect(0, 0, width, height), mSystemInsets.top,
                    searchBarSpaceBounds);
            mSearchBar.measure(
                    MeasureSpec.makeMeasureSpec(searchBarSpaceBounds.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(searchBarSpaceBounds.height(), MeasureSpec.EXACTLY));
        }

        Rect taskStackBounds = new Rect();
        config.getTaskStackBounds(new Rect(0, 0, width, height), mSystemInsets.top,
                mSystemInsets.right, searchBarSpaceBounds, taskStackBounds);
        if (mTaskStackView != null && mTaskStackView.getVisibility() != GONE) {
            mTaskStackView.setTaskStackBounds(taskStackBounds, mSystemInsets);
            mTaskStackView.measure(widthMeasureSpec, heightMeasureSpec);
        }

        if (mDragView != null) {
            Rect taskRect = mTaskStackView.mLayoutAlgorithm.mTaskRect;
            mDragView.measure(
                    MeasureSpec.makeMeasureSpec(taskRect.width(), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(taskRect.height(), MeasureSpec.AT_MOST));
        }

        setMeasuredDimension(width, height);
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        RecentsConfiguration config = Recents.getConfiguration();

        // Get the search bar bounds so that we lay it out
        Rect measuredRect = new Rect(0, 0, getMeasuredWidth(), getMeasuredHeight());
        Rect searchBarSpaceBounds = new Rect();
        if (mSearchBar != null) {
            config.getSearchBarBounds(measuredRect,
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

        if (mAwaitingFirstLayout) {
            mAwaitingFirstLayout = false;

            // If launched via dragging from the nav bar, then we should translate the whole view
            // down offscreen
            RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
            if (launchState.launchedViaDragGesture) {
                setTranslationY(getMeasuredHeight());
            }
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

    public void disableLayersForOneFrame() {
        if (mTaskStackView != null) {
            mTaskStackView.disableLayersForOneFrame();
        }
    }

    /**** TaskStackView.TaskStackCallbacks Implementation ****/

    @Override
    public void onTaskViewClicked(final TaskStackView stackView, final TaskView tv,
            final TaskStack stack, final Task task, final boolean lockToTask,
            final Rect bounds, int destinationStack) {
        mLastTaskLaunchedWasFreeform = SystemServicesProxy.isFreeformStack(task.key.stackId);
        mTransitionHelper.launchTaskFromRecents(stack, task, stackView, tv, lockToTask, bounds,
                destinationStack);
    }

    /**** EventBus Events ****/

    public final void onBusEvent(DragStartEvent event) {
        // Add the drag view
        mDragView = event.dragView;
        addView(mDragView);

        updateVisibleDockRegions(mTouchHandler.getDockStatesForCurrentOrientation(),
                TaskStack.DockState.NONE.viewState.dockAreaAlpha);
    }

    public final void onBusEvent(DragDropTargetChangedEvent event) {
        if (event.dropTarget == null || !(event.dropTarget instanceof TaskStack.DockState)) {
            updateVisibleDockRegions(mTouchHandler.getDockStatesForCurrentOrientation(),
                    TaskStack.DockState.NONE.viewState.dockAreaAlpha);
        } else {
            final TaskStack.DockState dockState = (TaskStack.DockState) event.dropTarget;
            updateVisibleDockRegions(new TaskStack.DockState[] {dockState}, -1);
        }
    }

    public final void onBusEvent(final DragEndEvent event) {
        final Runnable cleanUpRunnable = new Runnable() {
            @Override
            public void run() {
                // Remove the drag view
                removeView(mDragView);
                mDragView = null;
            }
        };

        // Animate the overlay alpha back to 0
        updateVisibleDockRegions(null, -1);

        if (event.dropTarget == null) {
            // No drop targets for hit, so just animate the task back to its place
            event.postAnimationTrigger.increment();
            event.postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    cleanUpRunnable.run();
                }
            });
            // Animate the task back to where it was before then clean up afterwards
            TaskViewTransform taskTransform = new TaskViewTransform();
            TaskStackLayoutAlgorithm layoutAlgorithm = mTaskStackView.getStackAlgorithm();
            layoutAlgorithm.getStackTransform(event.task,
                    mTaskStackView.getScroller().getStackScroll(), taskTransform, null);
            event.dragView.animate()
                    .scaleX(taskTransform.scale)
                    .scaleY(taskTransform.scale)
                    .translationX((layoutAlgorithm.mTaskRect.left - event.dragView.getLeft())
                            + taskTransform.translationX)
                    .translationY((layoutAlgorithm.mTaskRect.top - event.dragView.getTop())
                            + taskTransform.translationY)
                    .setDuration(175)
                    .setInterpolator(mFastOutSlowInInterpolator)
                    .withEndAction(event.postAnimationTrigger.decrementAsRunnable())
                    .start();

        } else if (event.dropTarget instanceof TaskStack.DockState) {
            final TaskStack.DockState dockState = (TaskStack.DockState) event.dropTarget;

            // For now, just remove the drag view and the original task
            // TODO: Animate the task to the drop target rect before launching it above
            cleanUpRunnable.run();

            // Dock the task and launch it
            SystemServicesProxy ssp = Recents.getSystemServices();
            ssp.startTaskInDockedMode(event.task.key.id, dockState.createMode);
            launchTask(event.task, null, INVALID_STACK_ID);

        } else {
            // We dropped on another drop target, so just add the cleanup to the post animation
            // trigger
            event.postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    cleanUpRunnable.run();
                }
            });
        }
    }

    public final void onBusEvent(DraggingInRecentsEvent event) {
        setTranslationY(event.distanceFromTop - mTaskStackView.getTaskViews().get(0).getY());
    }

    public final void onBusEvent(DraggingInRecentsEndedEvent event) {
        animate().translationY(0f);
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

    public final void onBusEvent(RecentsVisibilityChangedEvent event) {
        if (!event.visible) {
            // Reset the view state
            mAwaitingFirstLayout = true;
            mLastTaskLaunchedWasFreeform = false;
        }
    }
}
