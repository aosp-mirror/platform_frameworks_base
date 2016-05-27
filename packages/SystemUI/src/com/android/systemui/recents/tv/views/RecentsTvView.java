/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.recents.tv.views;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.CancelEnterRecentsWindowAnimationEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.LaunchTvTaskEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.tv.animations.RecentsRowFocusAnimationHolder;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

/**
 * Top level layout of recents for TV. This will show the TaskStacks using a HorizontalGridView.
 */
public class RecentsTvView extends FrameLayout {

    private static final String TAG = "RecentsTvView";
    private static final boolean DEBUG = false;

    private TaskStack mStack;
    private TaskStackHorizontalGridView mTaskStackHorizontalView;
    private View mEmptyView;
    private View mDismissPlaceholder;
    private RecentsRowFocusAnimationHolder mEmptyViewFocusAnimationHolder;
    private boolean mAwaitingFirstLayout = true;
    private Rect mSystemInsets = new Rect();
    private RecentsTvTransitionHelper mTransitionHelper;
    private final Handler mHandler = new Handler();
    private OnScrollListener mScrollListener;
    public RecentsTvView(Context context) {
        this(context, null);
    }

    public RecentsTvView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsTvView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RecentsTvView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        setWillNotDraw(false);

        LayoutInflater inflater = LayoutInflater.from(context);
        mEmptyView = inflater.inflate(R.layout.recents_tv_empty, this, false);
        addView(mEmptyView);

        mTransitionHelper = new RecentsTvTransitionHelper(mContext, mHandler);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDismissPlaceholder = findViewById(R.id.dismiss_placeholder);
        mTaskStackHorizontalView = (TaskStackHorizontalGridView) findViewById(R.id.task_list);
    }

    /**
     * Initialize the view.
     */
    public void init(TaskStack stack) {
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        mStack = stack;

        mTaskStackHorizontalView.init(stack);

        if (stack.getStackTaskCount() > 0) {
            hideEmptyView();
        } else {
            showEmptyView();
        }

        // Layout with the new stack
        requestLayout();
    }

    public boolean launchFocusedTask() {
        if (mTaskStackHorizontalView != null) {
            Task task = mTaskStackHorizontalView.getFocusedTask();
            if (task != null) {
                launchTaskFomRecents(task, true);
                return true;
            }
        }
        return false;
    }

    /** Launches the task that recents was launched from if possible */
    public boolean launchPreviousTask(boolean animate) {
        if (mTaskStackHorizontalView != null) {
            TaskStack stack = mTaskStackHorizontalView.getStack();
            Task task = stack.getLaunchTarget();
            if (task != null) {
                launchTaskFomRecents(task, animate);
                return true;
            }
        }
        return false;
    }

    /**
     * Launch the given task from recents with animation. If the task is not focused, this will
     * attempt to scroll to focus the task before launching.
     * @param task
     */
    private void launchTaskFomRecents(final Task task, boolean animate) {
        if (!animate) {
            SystemServicesProxy ssp = Recents.getSystemServices();
            ssp.startActivityFromRecents(getContext(), task.key, task.title, null);
            return;
        }
        mTaskStackHorizontalView.requestFocus();
        Task focusedTask = mTaskStackHorizontalView.getFocusedTask();
        if (focusedTask != null && task != focusedTask) {
            if (mScrollListener != null) {
                mTaskStackHorizontalView.removeOnScrollListener(mScrollListener);
            }
            mScrollListener = new OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        TaskCardView cardView = mTaskStackHorizontalView.getChildViewForTask(task);
                        if (cardView != null) {
                            mTransitionHelper.launchTaskFromRecents(mStack, task,
                                    mTaskStackHorizontalView, cardView, null, INVALID_STACK_ID);
                        } else {
                            // This should not happen normally. If this happens then the data in
                            // the grid view was altered during the scroll. Log error and launch
                            // task with no animation.
                            Log.e(TAG, "Card view for task : " + task + ", returned null.");
                            SystemServicesProxy ssp = Recents.getSystemServices();
                            ssp.startActivityFromRecents(getContext(), task.key, task.title, null);
                        }
                        mTaskStackHorizontalView.removeOnScrollListener(mScrollListener);
                    }
                }
            };
            mTaskStackHorizontalView.addOnScrollListener(mScrollListener);
            mTaskStackHorizontalView.setSelectedPositionSmooth(
                    ((TaskStackHorizontalViewAdapter) mTaskStackHorizontalView.getAdapter())
                            .getPositionOfTask(task));
        } else {
            mTransitionHelper.launchTaskFromRecents(mStack, task, mTaskStackHorizontalView,
                    mTaskStackHorizontalView.getChildViewForTask(task), null,
                    INVALID_STACK_ID);
        }
    }

    /**
     * Hides the task stack and shows the empty view.
     */
    public void showEmptyView() {
        mEmptyView.setVisibility(View.VISIBLE);
        mTaskStackHorizontalView.setVisibility(View.GONE);
        if (Recents.getSystemServices().isTouchExplorationEnabled()) {
            mDismissPlaceholder.setVisibility(View.GONE);
        }
    }

    /**
     * Shows the task stack and hides the empty view.
     */
    public void hideEmptyView() {
        mEmptyView.setVisibility(View.GONE);
        mTaskStackHorizontalView.setVisibility(View.VISIBLE);
        if (Recents.getSystemServices().isTouchExplorationEnabled()) {
            mDismissPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Returns the last known system insets.
     */
    public Rect getSystemInsets() {
        return mSystemInsets;
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mSystemInsets.set(insets.getSystemWindowInsets());
        requestLayout();
        return insets;
    }

    /**** EventBus Events ****/

    public final void onBusEvent(LaunchTvTaskEvent event) {
        mTransitionHelper.launchTaskFromRecents(mStack, event.task, mTaskStackHorizontalView,
                event.taskView, event.targetTaskBounds, event.targetTaskStack);
    }

    public final void onBusEvent(DismissRecentsToHomeAnimationStarted event) {
        // If we are going home, cancel the previous task's window transition
        EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(null));
    }

    public final void onBusEvent(RecentsVisibilityChangedEvent event) {
        if (!event.visible) {
            // Reset the view state
            mAwaitingFirstLayout = true;
        }
    }

    public TaskStackHorizontalGridView setTaskStackViewAdapter(
            TaskStackHorizontalViewAdapter taskStackViewAdapter) {
        if (mTaskStackHorizontalView != null) {
            mTaskStackHorizontalView.setAdapter(taskStackViewAdapter);
            taskStackViewAdapter.setTaskStackHorizontalGridView(mTaskStackHorizontalView);
        }
        return mTaskStackHorizontalView;
    }

    public TaskStackHorizontalGridView getGridView() {
        return mTaskStackHorizontalView;
    }
}
