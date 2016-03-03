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

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewOutlineProvider;
import android.view.ViewPropertyAnimator;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsAppWidgetHostView;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.ClearHistoryEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;
import com.android.systemui.recents.events.activity.HideHistoryButtonEvent;
import com.android.systemui.recents.events.activity.HideHistoryEvent;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.activity.ShowHistoryButtonEvent;
import com.android.systemui.recents.events.activity.ShowHistoryEvent;
import com.android.systemui.recents.events.activity.TaskStackUpdatedEvent;
import com.android.systemui.recents.events.activity.ToggleHistoryEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEndedEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEvent;
import com.android.systemui.recents.events.ui.ResetBackgroundScrimEvent;
import com.android.systemui.recents.events.ui.UpdateBackgroundScrimEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragDropTargetChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.history.RecentsHistoryView;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.stackdivider.WindowManagerProxy;
import com.android.systemui.statusbar.FlingAnimationUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * This view is the the top level layout that contains TaskStacks (which are laid out according
 * to their SpaceNode bounds.
 */
public class RecentsView extends FrameLayout {

    private static final int DOCK_AREA_OVERLAY_TRANSITION_DURATION = 135;
    private static final int DEFAULT_UPDATE_SCRIM_DURATION = 200;
    private static final float DEFAULT_SCRIM_ALPHA = 0.33f;

    private final Handler mHandler;

    private TaskStack mStack;
    private TaskStackView mTaskStackView;
    private RecentsAppWidgetHostView mSearchBar;
    private TextView mHistoryButton;
    private TextView mHistoryClearAllButton;
    private View mEmptyView;
    private RecentsHistoryView mHistoryView;

    private boolean mAwaitingFirstLayout = true;
    private boolean mLastTaskLaunchedWasFreeform;

    @ViewDebug.ExportedProperty(category="recents")
    private Rect mSystemInsets = new Rect();
    private int mDividerSize;

    private ColorDrawable mBackgroundScrim = new ColorDrawable(Color.BLACK);
    private Animator mBackgroundScrimAnimator;

    private RecentsTransitionHelper mTransitionHelper;
    @ViewDebug.ExportedProperty(deepExport=true, prefix="touch_")
    private RecentsViewTouchHandler mTouchHandler;
    private final FlingAnimationUtils mFlingAnimationUtils;

    public RecentsView(Context context) {
        this(context, null);
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

        SystemServicesProxy ssp = Recents.getSystemServices();
        mHandler = new Handler();
        mTransitionHelper = new RecentsTransitionHelper(getContext(), mHandler);
        mDividerSize = ssp.getDockedDividerSize(context);
        mTouchHandler = new RecentsViewTouchHandler(this);
        mFlingAnimationUtils = new FlingAnimationUtils(context, 0.3f);

        final float cornerRadius = context.getResources().getDimensionPixelSize(
                R.dimen.recents_task_view_rounded_corners_radius);
        LayoutInflater inflater = LayoutInflater.from(context);
        if (RecentsDebugFlags.Static.EnableHistory) {
            mHistoryButton = (TextView) inflater.inflate(R.layout.recents_history_button, this,
                    false);
            mHistoryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    EventBus.getDefault().send(new ToggleHistoryEvent());
                }
            });
            addView(mHistoryButton);
            mHistoryButton.setClipToOutline(true);
            mHistoryButton.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            });
        }
        mEmptyView = inflater.inflate(R.layout.recents_empty, this, false);
        addView(mEmptyView);

        setBackground(mBackgroundScrim);
    }

    /** Set/get the bsp root node */
    public void setTaskStack(TaskStack stack) {
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        mStack = stack;
        if (launchState.launchedReuseTaskStackViews) {
            if (mTaskStackView != null) {
                // If onRecentsHidden is not triggered, we need to the stack view again here
                mTaskStackView.reset();
                mTaskStackView.setStack(stack);
            } else {
                mTaskStackView = new TaskStackView(getContext(), stack);
                addView(mTaskStackView);
            }
        } else {
            if (mTaskStackView != null) {
                removeView(mTaskStackView);
            }
            mTaskStackView = new TaskStackView(getContext(), stack);
            addView(mTaskStackView);
        }

        // If we are already occluded by the app, then just set the default background scrim now.
        // Otherwise, defer until the enter animation completes to animate the scrim with the
        // tasks for the home animation.
        if (launchState.launchedWhileDocking || launchState.launchedFromApp
                || mStack.getTaskCount() == 0) {
            mBackgroundScrim.setAlpha((int) (DEFAULT_SCRIM_ALPHA * 255));
        } else {
            mBackgroundScrim.setAlpha(0);
        }

        // Update the top level view's visibilities
        if (stack.getTaskCount() > 0) {
            hideEmptyView();
        } else {
            showEmptyView();
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

    /**
     * Returns whether the history is visible or not.
     */
    public boolean isHistoryVisible() {
        return mHistoryView != null && mHistoryView.isVisible();
    }

    /**
     * Returns the currently set task stack.
     */
    public TaskStack getTaskStack() {
        return mStack;
    }

    /** Gets the next task in the stack - or if the last - the top task */
    public Task getNextTaskOrTopTask(Task taskToSearch) {
        Task returnTask = null;
        boolean found = false;
        if (mTaskStackView != null) {
            TaskStack stack = mTaskStackView.getStack();
            ArrayList<Task> taskList = stack.getStackTasks();
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
    public boolean launchFocusedTask(int logEvent) {
        if (mTaskStackView != null) {
            Task task = mTaskStackView.getFocusedTask();
            if (task != null) {
                TaskView taskView = mTaskStackView.getChildViewForTask(task);
                EventBus.getDefault().send(new LaunchTaskEvent(taskView, task, null,
                        INVALID_STACK_ID, false));

                if (logEvent != 0) {
                    MetricsLogger.action(getContext(), logEvent,
                            task.key.getComponent().toString());
                }
                return true;
            }
        }
        return false;
    }

    /** Launches the task that recents was launched from if possible */
    public boolean launchPreviousTask() {
        if (mTaskStackView != null) {
            TaskStack stack = mTaskStackView.getStack();
            Task task = stack.getLaunchTarget();
            if (task != null) {
                TaskView taskView = mTaskStackView.getChildViewForTask(task);
                EventBus.getDefault().send(new LaunchTaskEvent(taskView, task, null,
                        INVALID_STACK_ID, false));
                return true;
            }
        }
        return false;
    }

    /** Launches a given task. */
    public boolean launchTask(Task task, Rect taskBounds, int destinationStack) {
        if (mTaskStackView != null) {
            // Iterate the stack views and try and find the given task.
            List<TaskView> taskViews = mTaskStackView.getTaskViews();
            int taskViewCount = taskViews.size();
            for (int j = 0; j < taskViewCount; j++) {
                TaskView tv = taskViews.get(j);
                if (tv.getTask() == task) {
                    EventBus.getDefault().send(new LaunchTaskEvent(tv, task, taskBounds,
                            destinationStack, false));
                    return true;
                }
            }
        }
        return false;
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

    /**
     * Hides the task stack and shows the empty view.
     */
    public void showEmptyView() {
        if (RecentsDebugFlags.Static.EnableSearchBar && (mSearchBar != null)) {
            mSearchBar.setVisibility(View.INVISIBLE);
        }
        mTaskStackView.setVisibility(View.INVISIBLE);
        mEmptyView.setVisibility(View.VISIBLE);
        mEmptyView.bringToFront();
        if (RecentsDebugFlags.Static.EnableHistory) {
            mHistoryButton.bringToFront();
        }
    }

    /**
     * Shows the task stack and hides the empty view.
     */
    public void hideEmptyView() {
        mEmptyView.setVisibility(View.INVISIBLE);
        mTaskStackView.setVisibility(View.VISIBLE);
        if (RecentsDebugFlags.Static.EnableSearchBar && (mSearchBar != null)) {
            mSearchBar.setVisibility(View.VISIBLE);
        }
        mTaskStackView.bringToFront();
        if (mSearchBar != null) {
            mSearchBar.bringToFront();
        }
        if (RecentsDebugFlags.Static.EnableHistory) {
            mHistoryButton.bringToFront();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        EventBus.getDefault().register(mTouchHandler, RecentsActivity.EVENT_BUS_PRIORITY + 2);
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

        // Measure the empty view to the full size of the screen
        if (mEmptyView.getVisibility() != GONE) {
            measureChild(mEmptyView, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }

        if (RecentsDebugFlags.Static.EnableHistory) {
            // Measure the history view
            if (mHistoryView != null && mHistoryView.getVisibility() != GONE) {
                measureChild(mHistoryView, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }

            // Measure the history button within the constraints of the space above the stack
            Rect historyButtonRect = mTaskStackView.mLayoutAlgorithm.mHistoryButtonRect;
            measureChild(mHistoryButton,
                    MeasureSpec.makeMeasureSpec(historyButtonRect.width(), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(historyButtonRect.height(), MeasureSpec.AT_MOST));
            if (mHistoryClearAllButton != null && mHistoryClearAllButton.getVisibility() != GONE) {
                measureChild(mHistoryClearAllButton,
                    MeasureSpec.makeMeasureSpec(historyButtonRect.width(), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(historyButtonRect.height(), MeasureSpec.AT_MOST));
            }
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

        // Layout the empty view
        if (mEmptyView.getVisibility() != GONE) {
            mEmptyView.layout(left, top, right, bottom);
        }

        if (RecentsDebugFlags.Static.EnableHistory) {
            // Layout the history view
            if (mHistoryView != null && mHistoryView.getVisibility() != GONE) {
                mHistoryView.layout(left, top, right, bottom);
            }

            // Layout the history button such that its drawable is start-aligned with the stack,
            // vertically centered in the available space above the stack
            Rect historyButtonRect = mTaskStackView.mLayoutAlgorithm.mHistoryButtonRect;
            int historyLeft = isLayoutRtl()
                    ? historyButtonRect.right + mHistoryButton.getPaddingStart()
                    - mHistoryButton.getMeasuredWidth()
                    : historyButtonRect.left - mHistoryButton.getPaddingStart();
            int historyTop = historyButtonRect.top +
                    (historyButtonRect.height() - mHistoryButton.getMeasuredHeight()) / 2;
            mHistoryButton.layout(historyLeft, historyTop,
                    historyLeft + mHistoryButton.getMeasuredWidth(),
                    historyTop + mHistoryButton.getMeasuredHeight());

            // Layout the history clear all button such that it is end-aligned with the stack,
            // vertically centered in the available space above the stack
            if (mHistoryClearAllButton != null && mHistoryClearAllButton.getVisibility() != GONE) {
                int clearAllLeft = isLayoutRtl()
                        ? historyButtonRect.left - mHistoryClearAllButton.getPaddingStart()
                        : historyButtonRect.right + mHistoryClearAllButton.getPaddingStart()
                        - mHistoryClearAllButton.getMeasuredWidth();
                int clearAllTop = historyButtonRect.top +
                        (historyButtonRect.height() - mHistoryClearAllButton.getMeasuredHeight()) /
                                2;
                mHistoryClearAllButton.layout(clearAllLeft, clearAllTop,
                        clearAllLeft + mHistoryClearAllButton.getMeasuredWidth(),
                        clearAllTop + mHistoryClearAllButton.getMeasuredHeight());
            }
        }

        if (mAwaitingFirstLayout) {
            mAwaitingFirstLayout = false;

            // If launched via dragging from the nav bar, then we should translate the whole view
            // down offscreen
            RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
            if (launchState.launchedViaDragGesture) {
                setTranslationY(getMeasuredHeight());
            } else {
                setTranslationY(0f);
            }
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mSystemInsets.set(insets.getSystemWindowInsets());
        requestLayout();
        return insets;
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
    public void onDrawForeground(Canvas canvas) {
        super.onDrawForeground(canvas);

        ArrayList<TaskStack.DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            Drawable d = visDockStates.get(i).viewState.dockAreaOverlay;
            if (d.getAlpha() > 0) {
                d.draw(canvas);
            }
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        ArrayList<TaskStack.DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            Drawable d = visDockStates.get(i).viewState.dockAreaOverlay;
            if (d == who) {
                return true;
            }
        }
        return super.verifyDrawable(who);
    }

    /**** EventBus Events ****/

    public final void onBusEvent(LaunchTaskEvent event) {
        mLastTaskLaunchedWasFreeform = event.task.isFreeformTask();
        mTransitionHelper.launchTaskFromRecents(mStack, event.task, mTaskStackView, event.taskView,
                event.screenPinningRequested, event.targetTaskBounds, event.targetTaskStack);
    }

    public final void onBusEvent(DismissRecentsToHomeAnimationStarted event) {
        int taskViewExitToHomeDuration = TaskStackAnimationHelper.EXIT_TO_HOME_TRANSLATION_DURATION;
        if (RecentsDebugFlags.Static.EnableHistory) {
            // Hide the history button
            hideHistoryButton(taskViewExitToHomeDuration, false /* translate */);
        }
        animateBackgroundScrim(0f, taskViewExitToHomeDuration);
    }

    public final void onBusEvent(DragStartEvent event) {
        updateVisibleDockRegions(mTouchHandler.getDockStatesForCurrentOrientation(),
                true /* isDefaultDockState */, TaskStack.DockState.NONE.viewState.dockAreaAlpha,
                true /* animateAlpha */, false /* animateBounds */);
    }

    public final void onBusEvent(DragDropTargetChangedEvent event) {
        if (event.dropTarget == null || !(event.dropTarget instanceof TaskStack.DockState)) {
            updateVisibleDockRegions(mTouchHandler.getDockStatesForCurrentOrientation(),
                    true /* isDefaultDockState */, TaskStack.DockState.NONE.viewState.dockAreaAlpha,
                    true /* animateAlpha */, true /* animateBounds */);
        } else {
            final TaskStack.DockState dockState = (TaskStack.DockState) event.dropTarget;
            updateVisibleDockRegions(new TaskStack.DockState[] {dockState},
                    false /* isDefaultDockState */, -1, true /* animateAlpha */,
                    true /* animateBounds */);
        }
    }

    public final void onBusEvent(final DragEndEvent event) {
        // Handle the case where we drop onto a dock region
        if (event.dropTarget instanceof TaskStack.DockState) {
            final TaskStack.DockState dockState = (TaskStack.DockState) event.dropTarget;

            // Hide the dock region
            updateVisibleDockRegions(null, false /* isDefaultDockState */, -1,
                    false /* animateAlpha */, false /* animateBounds */);

            TaskStackLayoutAlgorithm stackLayout = mTaskStackView.getStackAlgorithm();
            TaskStackViewScroller stackScroller = mTaskStackView.getScroller();
            TaskViewTransform tmpTransform = new TaskViewTransform();

            // We translated the view but we need to animate it back from the current layout-space
            // rect to its final layout-space rect
            int x = (int) event.taskView.getTranslationX();
            int y = (int) event.taskView.getTranslationY();
            Rect taskViewRect = new Rect(event.taskView.getLeft(), event.taskView.getTop(),
                    event.taskView.getRight(), event.taskView.getBottom());
            taskViewRect.offset(x, y);
            event.taskView.setTranslationX(0);
            event.taskView.setTranslationY(0);
            event.taskView.setLeftTopRightBottom(taskViewRect.left, taskViewRect.top,
                    taskViewRect.right, taskViewRect.bottom);

            // Remove the task view after it is docked
            mTaskStackView.updateLayoutAlgorithm(false /* boundScroll */);
            stackLayout.getStackTransform(event.task, stackScroller.getStackScroll(), tmpTransform,
                    null);
            tmpTransform.alpha = 0;
            tmpTransform.scale = 1f;
            tmpTransform.rect.set(taskViewRect);
            mTaskStackView.updateTaskViewToTransform(event.taskView, tmpTransform,
                    new AnimationProps(125, Interpolators.ALPHA_OUT,
                            new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    // Dock the task and launch it
                                    SystemServicesProxy ssp = Recents.getSystemServices();
                                    ssp.startTaskInDockedMode(getContext(), event.taskView,
                                            event.task.key.id, dockState.createMode);

                                    // Animate the stack accordingly
                                    AnimationProps stackAnim = new AnimationProps(
                                            TaskStackView.DEFAULT_SYNC_STACK_DURATION,
                                            Interpolators.FAST_OUT_SLOW_IN);
                                    mTaskStackView.getStack().removeTask(event.task, stackAnim);
                                }
                            }));

            MetricsLogger.action(mContext, MetricsEvent.ACTION_WINDOW_DOCK_DRAG_DROP);
        } else {
            // Animate the overlay alpha back to 0
            updateVisibleDockRegions(null, true /* isDefaultDockState */, -1,
                    true /* animateAlpha */, false /* animateBounds */);
        }
    }

    public final void onBusEvent(DraggingInRecentsEvent event) {
        if (mTaskStackView.getTaskViews().size() > 0) {
            setTranslationY(event.distanceFromTop - mTaskStackView.getTaskViews().get(0).getY());
        }
    }

    public final void onBusEvent(DraggingInRecentsEndedEvent event) {
        ViewPropertyAnimator animator = animate();
        if (event.velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            animator.translationY(getHeight());
            animator.withEndAction(new Runnable() {
                @Override
                public void run() {
                    WindowManagerProxy.getInstance().maximizeDockedStack();
                }
            });
            mFlingAnimationUtils.apply(animator, getTranslationY(), getHeight(), event.velocity);
        } else {
            animator.translationY(0f);
            animator.setListener(null);
            mFlingAnimationUtils.apply(animator, getTranslationY(), 0, event.velocity);
        }
        animator.start();
    }

    public final void onBusEvent(TaskStackUpdatedEvent event) {
        if (!event.inMultiWindow) {
            mStack.setTasks(event.stack.computeAllTasksList(), true /* notifyStackChanges */);
            mStack.createAffiliatedGroupings(getContext());
        }
    }

    public final void onBusEvent(EnterRecentsWindowAnimationCompletedEvent event) {
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        if (!launchState.launchedWhileDocking && !launchState.launchedFromApp
                && mStack.getTaskCount() > 0) {
            animateBackgroundScrim(DEFAULT_SCRIM_ALPHA,
                    TaskStackAnimationHelper.ENTER_FROM_HOME_TRANSLATION_DURATION);
        }
    }

    public final void onBusEvent(UpdateBackgroundScrimEvent event) {
        animateBackgroundScrim(event.alpha, DEFAULT_UPDATE_SCRIM_DURATION);
    }

    public final void onBusEvent(ResetBackgroundScrimEvent event) {
        animateBackgroundScrim(DEFAULT_SCRIM_ALPHA, DEFAULT_UPDATE_SCRIM_DURATION);
    }

    public final void onBusEvent(RecentsVisibilityChangedEvent event) {
        if (!event.visible) {
            // Reset the view state
            mAwaitingFirstLayout = true;
            mLastTaskLaunchedWasFreeform = false;
            if (RecentsDebugFlags.Static.EnableHistory) {
                hideHistoryButton(0, false /* translate */);
            }
        }
    }

    public final void onBusEvent(ToggleHistoryEvent event) {
        if (!RecentsDebugFlags.Static.EnableHistory) {
            return;
        }

        if (mHistoryView != null && mHistoryView.isVisible()) {
            EventBus.getDefault().send(new HideHistoryEvent(true /* animate */));
        } else {
            EventBus.getDefault().send(new ShowHistoryEvent());
        }
    }

    public final void onBusEvent(ShowHistoryEvent event) {
        if (!RecentsDebugFlags.Static.EnableHistory) {
            return;
        }

        if (mHistoryView == null) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            mHistoryView = (RecentsHistoryView) inflater.inflate(R.layout.recents_history, this,
                    false);
            addView(mHistoryView);

            final float cornerRadius = getResources().getDimensionPixelSize(
                    R.dimen.recents_task_view_rounded_corners_radius);
            mHistoryClearAllButton = (TextView) inflater.inflate(
                    R.layout.recents_history_clear_all_button, this, false);
            mHistoryClearAllButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    EventBus.getDefault().send(new ClearHistoryEvent());
                }
            });
            mHistoryClearAllButton.setClipToOutline(true);
            mHistoryClearAllButton.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            });
            addView(mHistoryClearAllButton);

            // Since this history view is inflated by a view stub after the insets have already
            // been applied, we have to set them ourselves initial from the insets that were last
            // provided.
            mHistoryView.setSystemInsets(mSystemInsets);
            mHistoryView.setHeaderHeight(mHistoryButton.getMeasuredHeight());
            mHistoryButton.bringToFront();
            mHistoryClearAllButton.bringToFront();
        }

        // Animate the empty view in parallel with the history view (the task view animations are
        // handled in TaskStackView)
        Rect stackRect = mTaskStackView.mLayoutAlgorithm.mStackRect;
        if (mEmptyView.getVisibility() == View.VISIBLE) {
            int historyTransitionDuration = getResources().getInteger(
                    R.integer.recents_history_transition_duration);
            mEmptyView.animate()
                    .alpha(0f)
                    .translationY(stackRect.height() / 2)
                    .setDuration(historyTransitionDuration)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mEmptyView.setVisibility(View.INVISIBLE);
                        }
                    })
                    .start();
        }

        mHistoryView.show(mStack, stackRect.height(), mHistoryClearAllButton);
    }

    public final void onBusEvent(HideHistoryEvent event) {
        if (!RecentsDebugFlags.Static.EnableHistory) {
            return;
        }

        // Animate the empty view in parallel with the history view (the task view animations are
        // handled in TaskStackView)
        Rect stackRect = mTaskStackView.mLayoutAlgorithm.mStackRect;
        if (mStack.getTaskCount() == 0) {
            int historyTransitionDuration = getResources().getInteger(
                    R.integer.recents_history_transition_duration);
            mEmptyView.setVisibility(View.VISIBLE);
            mEmptyView.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(historyTransitionDuration)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .start();
        }

        mHistoryView.hide(event.animate, stackRect.height(), mHistoryClearAllButton);
    }

    public final void onBusEvent(ShowHistoryButtonEvent event) {
        if (!RecentsDebugFlags.Static.EnableHistory) {
            return;
        }

        showHistoryButton(150, event.translate);
    }

    public final void onBusEvent(HideHistoryButtonEvent event) {
        if (!RecentsDebugFlags.Static.EnableHistory) {
            return;
        }

        hideHistoryButton(100, true /* translate */);
    }

    /**
     * Shows the history button.
     */
    private void showHistoryButton(final int duration, final boolean translate) {
        if (!RecentsDebugFlags.Static.EnableHistory) {
            return;
        }

        final ReferenceCountedTrigger postAnimationTrigger = new ReferenceCountedTrigger();
        if (mHistoryButton.getVisibility() == View.INVISIBLE) {
            mHistoryButton.setVisibility(View.VISIBLE);
            mHistoryButton.setAlpha(0f);
            if (translate) {
                mHistoryButton.setTranslationY(-mHistoryButton.getMeasuredHeight() * 0.25f);
            } else {
                mHistoryButton.setTranslationY(0f);
            }
            postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    if (translate) {
                        mHistoryButton.animate()
                            .translationY(0f);
                    }
                    mHistoryButton.animate()
                            .alpha(1f)
                            .setDuration(duration)
                            .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                            .withLayer()
                            .start();
                }
            });
        }
        postAnimationTrigger.flushLastDecrementRunnables();
    }

    /**
     * Hides the history button.
     */
    private void hideHistoryButton(int duration, boolean translate) {
        if (!RecentsDebugFlags.Static.EnableHistory) {
            return;
        }

        final ReferenceCountedTrigger postAnimationTrigger = new ReferenceCountedTrigger();
        hideHistoryButton(duration, translate, postAnimationTrigger);
        postAnimationTrigger.flushLastDecrementRunnables();
    }

    /**
     * Hides the history button.
     */
    private void hideHistoryButton(int duration, boolean translate,
            final ReferenceCountedTrigger postAnimationTrigger) {
        if (!RecentsDebugFlags.Static.EnableHistory) {
            return;
        }

        if (mHistoryButton.getVisibility() == View.VISIBLE) {
            if (translate) {
                mHistoryButton.animate()
                    .translationY(-mHistoryButton.getMeasuredHeight() * 0.25f);
            }
            mHistoryButton.animate()
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mHistoryButton.setVisibility(View.INVISIBLE);
                            postAnimationTrigger.decrement();
                        }
                    })
                    .withLayer()
                    .start();
            postAnimationTrigger.increment();
        }
    }

    /**
     * Updates the dock region to match the specified dock state.
     */
    private void updateVisibleDockRegions(TaskStack.DockState[] newDockStates,
            boolean isDefaultDockState, int overrideAlpha, boolean animateAlpha,
            boolean animateBounds) {
        ArraySet<TaskStack.DockState> newDockStatesSet = Utilities.arrayToSet(newDockStates,
                new ArraySet<TaskStack.DockState>());
        ArrayList<TaskStack.DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            TaskStack.DockState dockState = visDockStates.get(i);
            TaskStack.DockState.ViewState viewState = dockState.viewState;
            if (newDockStates == null || !newDockStatesSet.contains(dockState)) {
                // This is no longer visible, so hide it
                viewState.startAnimation(null, 0, DOCK_AREA_OVERLAY_TRANSITION_DURATION,
                        Interpolators.ALPHA_OUT, animateAlpha, animateBounds);
            } else {
                // This state is now visible, update the bounds and show it
                int alpha = (overrideAlpha != -1 ? overrideAlpha : viewState.dockAreaAlpha);
                Rect bounds = isDefaultDockState
                        ? dockState.getPreDockedBounds(getMeasuredWidth(), getMeasuredHeight())
                        : dockState.getDockedBounds(getMeasuredWidth(), getMeasuredHeight(),
                        mDividerSize, mSystemInsets, getResources());
                if (viewState.dockAreaOverlay.getCallback() != this) {
                    viewState.dockAreaOverlay.setCallback(this);
                    viewState.dockAreaOverlay.setBounds(bounds);
                }
                viewState.startAnimation(bounds, alpha, DOCK_AREA_OVERLAY_TRANSITION_DURATION,
                        Interpolators.ALPHA_IN, animateAlpha, animateBounds);
            }
        }
    }

    /**
     * Animates the background scrim to the given {@param alpha}.
     */
    private void animateBackgroundScrim(float alpha, int duration) {
        Utilities.cancelAnimationWithoutCallbacks(mBackgroundScrimAnimator);
        int alphaInt = (int) (alpha * 255);
        mBackgroundScrimAnimator = ObjectAnimator.ofInt(mBackgroundScrim, Utilities.DRAWABLE_ALPHA,
                mBackgroundScrim.getAlpha(), alphaInt);
        mBackgroundScrimAnimator.setDuration(duration);
        mBackgroundScrimAnimator.setInterpolator(alphaInt > mBackgroundScrim.getAlpha()
                ? Interpolators.ALPHA_OUT
                : Interpolators.ALPHA_IN);
        mBackgroundScrimAnimator.start();
    }
}
