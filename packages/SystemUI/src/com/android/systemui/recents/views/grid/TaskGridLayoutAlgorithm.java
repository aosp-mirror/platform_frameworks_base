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
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.views.TaskStackLayoutAlgorithm;
import com.android.systemui.recents.views.TaskViewTransform;

public class TaskGridLayoutAlgorithm  {

    private final String TAG = "TaskGridLayoutAlgorithm";
    private final int MAX_LAYOUT_TASK_COUNT = 8;

    /** The horizontal padding around the whole recents view. */
    private int mPaddingLeftRight;
    /** The vertical padding around the whole recents view. */
    private int mPaddingTopBottom;
    /** The padding between task views. */
    private int mPaddingTaskView;

    private Rect mDisplayRect;
    private Rect mWindowRect;
    private Point mScreenSize = new Point();

    private Rect mTaskGridRect;

    /** The height, in pixels, of each task view's title bar. */
    private int mTitleBarHeight;

    /** The aspect ratio of each task thumbnail, without the title bar. */
    private float mAppAspectRatio;
    private Rect mSystemInsets = new Rect();

    public TaskGridLayoutAlgorithm(Context context) {
        reloadOnConfigurationChange(context);
    }

    public void reloadOnConfigurationChange(Context context) {
        Resources res = context.getResources();
        mPaddingLeftRight = res.getDimensionPixelSize(R.dimen.recents_grid_padding_left_right);
        mPaddingTopBottom = res.getDimensionPixelSize(R.dimen.recents_grid_padding_top_bottom);
        mPaddingTaskView = res.getDimensionPixelSize(R.dimen.recents_grid_padding_task_view);

        mTaskGridRect = new Rect();
        mTitleBarHeight = res.getDimensionPixelSize(R.dimen.recents_grid_task_view_header_height);

        WindowManager windowManager = (WindowManager) context
                .getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealSize(mScreenSize);

        updateAppAspectRatio();
    }

    public TaskViewTransform getTransform(int taskIndex, int taskCount,
        TaskViewTransform transformOut, TaskStackLayoutAlgorithm stackLayout) {

        int layoutTaskCount = Math.min(MAX_LAYOUT_TASK_COUNT, taskCount);

        // We also need to invert the index in order to display the most recent tasks first.
        int taskLayoutIndex = taskCount - taskIndex - 1;

        int tasksPerLine = layoutTaskCount < 2 ? 1 : (
                layoutTaskCount < 5 ? 2 : (
                        layoutTaskCount < 7 ? 3 : 4));
        int lines = layoutTaskCount < 3 ? 1 : 2;

        int taskWidth, taskHeight;
        int maxTaskWidth = (mDisplayRect.width() - 2 * mPaddingLeftRight
                - (tasksPerLine - 1) * mPaddingTaskView) / tasksPerLine;
        int maxTaskHeight = (mDisplayRect.height() - 2 * mPaddingTopBottom
                - (lines - 1) * mPaddingTaskView) / lines;

        if (maxTaskHeight >= maxTaskWidth / mAppAspectRatio + mTitleBarHeight) {
            // Width bound.
            taskWidth = maxTaskWidth;
            taskHeight = (int) (maxTaskWidth / mAppAspectRatio + mTitleBarHeight);
        } else {
            // Height bound.
            taskHeight = maxTaskHeight;
            taskWidth = (int) ((taskHeight - mTitleBarHeight) * mAppAspectRatio);
        }
        int emptySpaceX = mDisplayRect.width() - 2 * mPaddingLeftRight
                - (tasksPerLine * taskWidth) - (tasksPerLine - 1) * mPaddingTaskView;
        int emptySpaceY = mDisplayRect.height() - 2 * mPaddingTopBottom
                - (lines * taskHeight) - (lines - 1) * mPaddingTaskView;

        mTaskGridRect.set(0, 0, taskWidth, taskHeight);

        int xIndex = taskLayoutIndex % tasksPerLine;
        int yIndex = taskLayoutIndex / tasksPerLine;
        int x = emptySpaceX / 2 + mPaddingLeftRight + (taskWidth + mPaddingTaskView) * xIndex;
        int y = emptySpaceY / 2 + mPaddingTopBottom + (taskHeight + mPaddingTaskView) * yIndex;
        float z = stackLayout.mMaxTranslationZ;

        float dimAlpha = 0f;
        float viewOutlineAlpha = 0f;
        boolean isTaskViewVisible = (taskLayoutIndex < MAX_LAYOUT_TASK_COUNT);

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

    public void initialize(Rect displayRect, Rect windowRect) {
        mDisplayRect = displayRect;
        mWindowRect = windowRect;
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
        Rect buttonRect = new Rect(mDisplayRect);
        buttonRect.right -= mPaddingLeftRight;
        buttonRect.left += mPaddingLeftRight;
        buttonRect.bottom = buttonRect.top + mPaddingTopBottom;
        return buttonRect;
    }
}
