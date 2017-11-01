/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.recents.views.grid;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import android.view.ViewTreeObserver.OnGlobalFocusChangeListener;
import com.android.systemui.R;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.TaskStackView;

public class TaskViewFocusFrame extends View implements OnGlobalFocusChangeListener {

    private TaskStackView mSv;
    private TaskGridLayoutAlgorithm mTaskGridLayoutAlgorithm;
    public TaskViewFocusFrame(Context context) {
        this(context, null);
    }

    public TaskViewFocusFrame(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskViewFocusFrame(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskViewFocusFrame(Context context, AttributeSet attrs, int defStyleAttr,
        int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setBackground(mContext.getDrawable(
            R.drawable.recents_grid_task_view_focus_frame_background));
        setFocusable(false);
        hide();
    }

    public TaskViewFocusFrame(Context context, TaskStackView stackView,
        TaskGridLayoutAlgorithm taskGridLayoutAlgorithm) {
        this(context);
        mSv = stackView;
        mTaskGridLayoutAlgorithm = taskGridLayoutAlgorithm;
    }

    /**
     * Measure the width and height of the focus frame according to the current grid task view size.
     */
    public void measure() {
        int thickness = mTaskGridLayoutAlgorithm.getFocusFrameThickness();
        Rect rect = mTaskGridLayoutAlgorithm.getTaskGridRect();
        measure(
            MeasureSpec.makeMeasureSpec(rect.width() + thickness * 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(rect.height() + thickness * 2, MeasureSpec.EXACTLY));
    }

    /**
     * Layout the focus frame with its size.
     */
    public void layout() {
        layout(0, 0, getMeasuredWidth(), getMeasuredHeight());
    }

    /**
     * Update the current size of grid task view and the focus frame.
     */
    public void resize() {
        if (mSv.useGridLayout()) {
            mTaskGridLayoutAlgorithm.updateTaskGridRect(mSv.getStack().getTaskCount());
            measure();
            requestLayout();
        }
    }

    /**
     * Move the task view focus frame to surround the newly focused view. If it's {@code null} or
     * it's not an instance of GridTaskView, we hide the focus frame.
     * @param newFocus The newly focused view.
     */
    public void moveGridTaskViewFocus(View newFocus) {
        if (mSv.useGridLayout()) {
            // The frame only shows up in the grid layout. It shouldn't show up in the stack
            // layout including when we're in the split screen.
            if (newFocus instanceof GridTaskView) {
                // If the focus goes to a GridTaskView, we show the frame and layout it.
                int[] location = new int[2];
                newFocus.getLocationInWindow(location);
                int thickness = mTaskGridLayoutAlgorithm.getFocusFrameThickness();
                setTranslationX(location[0] - thickness);
                setTranslationY(location[1] - thickness);
                show();
            } else {
                // If focus goes to other views, we hide the frame.
                hide();
            }
        }
    }

    @Override
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        if (!mSv.useGridLayout()) {
            return;
        }
        if (newFocus == null) {
            // We're going to touch mode, unset the focus.
            moveGridTaskViewFocus(null);
            return;
        }
        if (oldFocus == null) {
            // We're returning from touch mode, set the focus to the previously focused task.
            final TaskStack stack = mSv.getStack();
            final int taskCount = stack.getTaskCount();
            final int k = stack.indexOfStackTask(mSv.getFocusedTask());
            final int taskIndexToFocus = k == -1 ? (taskCount - 1) : (k % taskCount);
            mSv.setFocusedTask(taskIndexToFocus, false, true);
        }
    }

    private void show() {
        setAlpha(1f);
    }

    private void hide() {
        setAlpha(0f);
    }
}
