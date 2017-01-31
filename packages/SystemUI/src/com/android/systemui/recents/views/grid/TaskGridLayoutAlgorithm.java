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

package com.android.systemui.recents.views.grid;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.recents.events.ui.focus.NavigateTaskViewEvent;
import com.android.systemui.recents.events.ui.focus.NavigateTaskViewEvent.Direction;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.views.TaskStackLayoutAlgorithm;
import com.android.systemui.recents.views.TaskViewTransform;

public class TaskGridLayoutAlgorithm  {

    private final String TAG = "TaskGridLayoutAlgorithm";
    public static final int MAX_LAYOUT_TASK_COUNT = 8;

    /** The horizontal padding around the whole recents view. */
    private int mPaddingLeftRight;
    /** The vertical padding around the whole recents view. */
    private int mPaddingTopBottom;
    /** The padding between task views. */
    private int mPaddingTaskView;

    private Rect mWindowRect;
    private Point mScreenSize = new Point();

    private Rect mTaskGridRect;

    /** The height, in pixels, of each task view's title bar. */
    private int mTitleBarHeight;

    /** The aspect ratio of each task thumbnail, without the title bar. */
    private float mAppAspectRatio;
    private Rect mSystemInsets = new Rect();

    /** The thickness of the focused task view frame. */
    private int mFocusedFrameThickness;

    /**
     * When the amount of tasks is determined, the size and position of every task view can be
     * decided. Each instance of TaskGridRectInfo store the task view information for a certain
     * amount of tasks.
     */
    class TaskGridRectInfo {
        Rect size;
        int[] xOffsets;
        int[] yOffsets;
        int tasksPerLine;
        int lines;

        TaskGridRectInfo(int taskCount) {
            size = new Rect();
            xOffsets = new int[taskCount];
            yOffsets = new int[taskCount];

            int layoutTaskCount = Math.min(MAX_LAYOUT_TASK_COUNT, taskCount);
            tasksPerLine = getTasksPerLine(layoutTaskCount);
            lines = layoutTaskCount < 4 ? 1 : 2;

            // A couple of special cases.
            boolean landscapeWindow = mWindowRect.width() > mWindowRect.height();
            boolean landscapeTaskView = mAppAspectRatio > 1;
            // If we're in portrait but task views are landscape, show more lines of fewer tasks.
            if (!landscapeWindow && landscapeTaskView) {
                tasksPerLine = layoutTaskCount < 2 ? 1 : 2;
                lines = layoutTaskCount < 3 ? 1 : (
                        layoutTaskCount < 5 ? 2 : (
                                layoutTaskCount < 7 ? 3 : 4));
            }
            // If we're in landscape but task views are portrait, show fewer lines of more tasks.
            if (landscapeWindow && !landscapeTaskView) {
                tasksPerLine = layoutTaskCount < 7 ? layoutTaskCount : 6;
                lines = layoutTaskCount < 7 ? 1 : 2;
            }

            int taskWidth, taskHeight;
            int maxTaskWidth = (mWindowRect.width() - 2 * mPaddingLeftRight
                - (tasksPerLine - 1) * mPaddingTaskView) / tasksPerLine;
            int maxTaskHeight = (mWindowRect.height() - 2 * mPaddingTopBottom
                - (lines - 1) * mPaddingTaskView) / lines;

            if (maxTaskHeight >= maxTaskWidth / mAppAspectRatio + mTitleBarHeight) {
                // Width bound.
                taskWidth = maxTaskWidth;
                // Here we should round the height to the nearest integer.
                taskHeight = (int) (maxTaskWidth / mAppAspectRatio + mTitleBarHeight + 0.5);
            } else {
                // Height bound.
                taskHeight = maxTaskHeight;
                // Here we should round the width to the nearest integer.
                taskWidth = (int) ((taskHeight - mTitleBarHeight) * mAppAspectRatio + 0.5);
            }
            size.set(0, 0, taskWidth, taskHeight);

            int emptySpaceX = mWindowRect.width() - 2 * mPaddingLeftRight
                - (tasksPerLine * taskWidth) - (tasksPerLine - 1) * mPaddingTaskView;
            int emptySpaceY = mWindowRect.height() - 2 * mPaddingTopBottom
                - (lines * taskHeight) - (lines - 1) * mPaddingTaskView;
            for (int taskIndex = 0; taskIndex < taskCount; taskIndex++) {
                // We also need to invert the index in order to display the most recent tasks first.
                int taskLayoutIndex = taskCount - taskIndex - 1;

                int xIndex = taskLayoutIndex % tasksPerLine;
                int yIndex = taskLayoutIndex / tasksPerLine;
                xOffsets[taskIndex] = mWindowRect.left +
                    emptySpaceX / 2 + mPaddingLeftRight + (taskWidth + mPaddingTaskView) * xIndex;
                yOffsets[taskIndex] = mWindowRect.top +
                    emptySpaceY / 2 + mPaddingTopBottom + (taskHeight + mPaddingTaskView) * yIndex;
            }
        }

        private int getTasksPerLine(int taskCount) {
            switch(taskCount) {
                case 0:
                    return 0;
                case 1:
                    return 1;
                case 2:
                case 4:
                    return 2;
                case 3:
                case 5:
                case 6:
                    return 3;
                case 7:
                case 8:
                    return 4;
                default:
                    throw new IllegalArgumentException("Unsupported task count " + taskCount);
            }
        }
    }

    /**
     * We can find task view sizes and positions from mTaskGridRectInfoList[k - 1] when there
     * are k tasks.
     */
    private TaskGridRectInfo[] mTaskGridRectInfoList;

    public TaskGridLayoutAlgorithm(Context context) {
        reloadOnConfigurationChange(context);
    }

    public void reloadOnConfigurationChange(Context context) {
        Resources res = context.getResources();
        mPaddingTaskView = res.getDimensionPixelSize(R.dimen.recents_grid_padding_task_view);
        mFocusedFrameThickness = res.getDimensionPixelSize(
            R.dimen.recents_grid_task_view_focused_frame_thickness);

        mTaskGridRect = new Rect();
        mTitleBarHeight = res.getDimensionPixelSize(R.dimen.recents_grid_task_view_header_height);

        WindowManager windowManager = (WindowManager) context
                .getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealSize(mScreenSize);

        updateAppAspectRatio();
    }

    /**
     * Returns the proper task view transform of a certain task view, according to its index and the
     * amount of task views.
     * @param taskIndex     The index of the task view whose transform we want. It's never greater
     *                      than {@link MAX_LAYOUT_TASK_COUNT}.
     * @param taskCount     The current amount of task views.
     * @param transformOut  The result transform that this method returns.
     * @param stackLayout   The base stack layout algorithm.
     * @return  The expected transform of the (taskIndex)th task view.
     */
    public TaskViewTransform getTransform(int taskIndex, int taskCount,
        TaskViewTransform transformOut, TaskStackLayoutAlgorithm stackLayout) {
        if (taskCount == 0) {
            transformOut.reset();
            return transformOut;
        }

        TaskGridRectInfo gridInfo = mTaskGridRectInfoList[taskCount - 1];
        mTaskGridRect.set(gridInfo.size);

        int x = gridInfo.xOffsets[taskIndex];
        int y = gridInfo.yOffsets[taskIndex];
        float z = stackLayout.mMaxTranslationZ;

        // We always set the dim alpha to 0, since we don't want grid task views to dim.
        float dimAlpha = 0f;
        // We always set the alpha of the view outline to 1, to make sure the shadow is visible.
        float viewOutlineAlpha = 1f;

        // We also need to invert the index in order to display the most recent tasks first.
        int taskLayoutIndex = taskCount - taskIndex - 1;
        boolean isTaskViewVisible = taskLayoutIndex < MAX_LAYOUT_TASK_COUNT;

        // Fill out the transform
        transformOut.scale = 1f;
        transformOut.alpha = isTaskViewVisible ? 1f : 0f;
        transformOut.translationZ = z;
        transformOut.dimAlpha = dimAlpha;
        transformOut.viewOutlineAlpha = viewOutlineAlpha;
        transformOut.rect.set(mTaskGridRect);
        transformOut.rect.offset(x, y);
        Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
        // We only show the 8 most recent tasks.
        transformOut.visible = isTaskViewVisible;
        return transformOut;
    }

    /**
     * Return the proper task index to focus for arrow key navigation.
     * @param taskCount             The amount of tasks.
     * @param currentFocusedIndex   The index of the currently focused task.
     * @param direction             The direction we're navigating.
     * @return  The index of the task that should get the focus.
     */
    public int navigateFocus(int taskCount, int currentFocusedIndex, Direction direction) {
        if (taskCount < 1 || taskCount > MAX_LAYOUT_TASK_COUNT) {
            return -1;
        }
        if (currentFocusedIndex == -1) {
            return 0;
        }
        int newIndex = currentFocusedIndex;
        final TaskGridRectInfo gridInfo = mTaskGridRectInfoList[taskCount - 1];
        final int currentLine = (taskCount - 1 - currentFocusedIndex) / gridInfo.tasksPerLine;
        switch (direction) {
            case UP:
                newIndex += gridInfo.tasksPerLine;
                newIndex = newIndex >= taskCount ? currentFocusedIndex : newIndex;
                break;
            case DOWN:
                newIndex -= gridInfo.tasksPerLine;
                newIndex = newIndex < 0 ? currentFocusedIndex : newIndex;
                break;
            case LEFT:
                newIndex++;
                final int leftMostIndex = (taskCount - 1) - currentLine * gridInfo.tasksPerLine;
                newIndex = newIndex > leftMostIndex ? currentFocusedIndex : newIndex;
                break;
            case RIGHT:
                newIndex--;
                int rightMostIndex =
                    (taskCount - 1) - (currentLine + 1) * gridInfo.tasksPerLine + 1;
                rightMostIndex = rightMostIndex < 0 ? 0 : rightMostIndex;
                newIndex = newIndex < rightMostIndex ? currentFocusedIndex : newIndex;
                break;
        }
        return newIndex;
    }

    public void initialize(Rect windowRect) {
        mWindowRect = windowRect;
        // Define paddings in terms of percentage of the total area.
        mPaddingLeftRight = (int) (0.025f * Math.min(mWindowRect.width(), mWindowRect.height()));
        mPaddingTopBottom = (int) (0.1 * mWindowRect.height());

        // Pre-calculate the positions and offsets of task views so that we can reuse them directly
        // in the future.
        mTaskGridRectInfoList = new TaskGridRectInfo[MAX_LAYOUT_TASK_COUNT];
        for (int i = 0; i < MAX_LAYOUT_TASK_COUNT; i++) {
            mTaskGridRectInfoList[i] = new TaskGridRectInfo(i + 1);
        }
    }

    public void setSystemInsets(Rect systemInsets) {
        mSystemInsets = systemInsets;
        updateAppAspectRatio();
    }

    private void updateAppAspectRatio() {
        int usableWidth = mScreenSize.x - mSystemInsets.left - mSystemInsets.right;
        int usableHeight = mScreenSize.y - mSystemInsets.top - mSystemInsets.bottom;
        mAppAspectRatio = (float) usableWidth / (float) usableHeight;
    }

    public Rect getStackActionButtonRect() {
        Rect buttonRect = new Rect(mWindowRect);
        buttonRect.right -= mPaddingLeftRight;
        buttonRect.left += mPaddingLeftRight;
        buttonRect.bottom = buttonRect.top + mPaddingTopBottom;
        return buttonRect;
    }

    public void updateTaskGridRect(int taskCount) {
        if (taskCount > 0) {
            TaskGridRectInfo gridInfo = mTaskGridRectInfoList[taskCount - 1];
            mTaskGridRect.set(gridInfo.size);
        }
    }

    public Rect getTaskGridRect() {
        return mTaskGridRect;
    }

    public int getFocusFrameThickness() {
        return mFocusedFrameThickness;
    }
}
