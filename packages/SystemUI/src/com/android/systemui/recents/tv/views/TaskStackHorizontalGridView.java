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

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.content.Context;
import android.support.v17.leanback.widget.HorizontalGridView;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.model.TaskStack.TaskStackCallbacks;
import com.android.systemui.recents.views.AnimationProps;

/**
 * Horizontal Grid View Implementation to show the Task Stack for TV.
 */
public class TaskStackHorizontalGridView extends HorizontalGridView implements TaskStackCallbacks {
    private static final int ANIMATION_DELAY_MS = 50;
    private static final int MSG_START_RECENT_ROW_FOCUS_ANIMATION = 100;
    private TaskStack mStack;
    private Task mFocusedTask;
    private AnimatorSet mRecentsRowFocusAnimation;

    public TaskStackHorizontalGridView(Context context) {
        this(context, null);
    }

    public TaskStackHorizontalGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        setWindowAlignment(WINDOW_ALIGN_NO_EDGE);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    /**
     * Initializes the grid view.
     * @param stack
     */
    public void init(TaskStack stack) {
        // Set new stack
        mStack = stack;
        if (mStack != null) {
            mStack.setCallbacks(this);
        }
    }

    /**
     * @return Returns the task stack.
     */
    public TaskStack getStack() {
        return mStack;
    }

    /**
     * @return - The focused task.
     */
    public Task getFocusedTask() {
        if (findFocus() != null) {
            mFocusedTask = ((TaskCardView)findFocus()).getTask();
        }
        return mFocusedTask;
    }

    /**
     * @param task
     * @return Child view for given task
     */
    public TaskCardView getChildViewForTask(Task task) {
        for (int i = 0; i < getChildCount(); i++) {
            TaskCardView tv = (TaskCardView) getChildAt(i);
            if (tv.getTask() == task) {
                return tv;
            }
        }
        return null;
    }


    /**
     * Starts the Recents row's focus gain animation.
     */
    public void startFocusGainAnimation() {
        for (int i = 0; i < getChildCount(); i++) {
            TaskCardView v = (TaskCardView) getChildAt(i);
            if (v.hasFocus()) {
                v.getViewFocusAnimator().changeSize(true);
            }
            v.getRecentsRowFocusAnimationHolder().startFocusGainAnimation();
        }
    }

    /**
     * Starts the Recents row's focus loss animation.
     */
    public void startFocusLossAnimation() {
        for (int i = 0; i < getChildCount(); i++) {
            TaskCardView v = (TaskCardView) getChildAt(i);
            if (v.hasFocus()) {
                v.getViewFocusAnimator().changeSize(false);
            }
            v.getRecentsRowFocusAnimationHolder().startFocusLossAnimation();
        }
    }

    @Override
    public void onStackTaskAdded(TaskStack stack, Task newTask) {
        ((TaskStackHorizontalViewAdapter) getAdapter()).addTaskAt(newTask,
                stack.indexOfStackTask(newTask));
    }

    @Override
    public void onStackTaskRemoved(TaskStack stack, Task removedTask, Task newFrontMostTask,
            AnimationProps animation, boolean fromDockGesture) {
        ((TaskStackHorizontalViewAdapter) getAdapter()).removeTask(removedTask);
        if (mFocusedTask == removedTask) {
            mFocusedTask = null;
        }
        // If there are no remaining tasks, then just close recents
        if (mStack.getStackTaskCount() == 0) {
            boolean shouldFinishActivity = (mStack.getStackTaskCount() == 0);
            if (shouldFinishActivity) {
                EventBus.getDefault().send(new AllTaskViewsDismissedEvent(fromDockGesture
                        ? R.string.recents_empty_message
                        : R.string.recents_empty_message_dismissed_all));
            }
        }
    }

    @Override
    public void onStackTasksRemoved(TaskStack stack) {
        // Do nothing
    }

    @Override
    public void onStackTasksUpdated(TaskStack stack) {
        // Do nothing
    }
}
