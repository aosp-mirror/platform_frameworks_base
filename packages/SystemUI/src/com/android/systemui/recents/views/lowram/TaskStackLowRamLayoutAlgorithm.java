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

package com.android.systemui.recents.views.lowram;

import android.content.Context;
import android.graphics.Rect;
import android.view.ViewConfiguration;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.views.TaskStackLayoutAlgorithm;
import com.android.systemui.recents.views.TaskViewTransform;

import java.util.ArrayList;

import static com.android.systemui.recents.views.TaskStackLayoutAlgorithm.VisibilityReport;

public class TaskStackLowRamLayoutAlgorithm {

    private static final String TAG = "TaskStackLowRamLayoutAlgorithm";
    private static final float MAX_OVERSCROLL = 0.2f / 0.3f;

    public static final int MAX_LAYOUT_TASK_COUNT = 9;
    public static final int NUM_TASK_VISIBLE_LAUNCHED_FROM_HOME = 2;
    public static final int NUM_TASK_VISIBLE_LAUNCHED_FROM_APP =
            NUM_TASK_VISIBLE_LAUNCHED_FROM_HOME + 1;
    private Rect mWindowRect;

    private int mFlingThreshold;
    private int mPadding;
    private int mPaddingLeftRight;
    private int mTopOffset;
    private int mPaddingEndTopBottom;
    private Rect mTaskRect = new Rect();
    private Rect mSystemInsets = new Rect();

    public TaskStackLowRamLayoutAlgorithm(Context context) {
        reloadOnConfigurationChange(context);
    }

    public void reloadOnConfigurationChange(Context context) {
        mPadding = context.getResources()
                .getDimensionPixelSize(R.dimen.recents_layout_side_margin_phone);
        mFlingThreshold = ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
    }

    public void initialize(Rect windowRect) {
        mWindowRect = windowRect;
        if (mWindowRect.height() > 0) {
            int windowHeight = mWindowRect.height() - mSystemInsets.bottom;
            int windowWidth = mWindowRect.width() - mSystemInsets.right - mSystemInsets.left;
            int width = Math.min(windowWidth, windowHeight) - mPadding * 2;
            boolean isLandscape = windowWidth > windowHeight;
            mTaskRect.set(0, 0, width, isLandscape ? width * 2 / 3 : width);
            mPaddingLeftRight = (windowWidth - mTaskRect.width()) / 2;
            mPaddingEndTopBottom = (windowHeight - mTaskRect.height()) / 2;

            // Compute the top offset to center tasks in the middle of the screen
            mTopOffset = (getTotalHeightOfTasks(MAX_LAYOUT_TASK_COUNT) - windowHeight) / 2;
        }
    }

    public void setSystemInsets(Rect systemInsets) {
        mSystemInsets = systemInsets;
    }

    public VisibilityReport computeStackVisibilityReport(ArrayList<Task> tasks) {
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        int maxVisible = launchState.launchedFromHome || launchState.launchedFromPipApp
                    || launchState.launchedWithNextPipApp
                ? NUM_TASK_VISIBLE_LAUNCHED_FROM_HOME
                : NUM_TASK_VISIBLE_LAUNCHED_FROM_APP;
        int visibleCount = Math.min(maxVisible, tasks.size());
        return new VisibilityReport(visibleCount, visibleCount);
    }

    public void getFrontOfStackTransform(TaskViewTransform transformOut,
            TaskStackLayoutAlgorithm stackLayout) {
        if (mWindowRect == null) {
            transformOut.reset();
            return;
        }

        // Calculate the static task y position 2 tasks after/below the middle/current task
        int windowHeight = mWindowRect.height() - mSystemInsets.bottom;
        int bottomOfCurrentTask = (windowHeight + mTaskRect.height()) / 2;
        int y = bottomOfCurrentTask + mTaskRect.height() + mPadding * 2;
        fillStackTransform(transformOut, y, stackLayout.mMaxTranslationZ, true);
    }

    public void getBackOfStackTransform(TaskViewTransform transformOut,
            TaskStackLayoutAlgorithm stackLayout) {
        if (mWindowRect == null) {
            transformOut.reset();
            return;
        }

        // Calculate the static task y position 2 tasks before/above the middle/current task
        int windowHeight = mWindowRect.height() - mSystemInsets.bottom;
        int topOfCurrentTask = (windowHeight - mTaskRect.height()) / 2;
        int y = topOfCurrentTask - (mTaskRect.height() + mPadding) * 2;
        fillStackTransform(transformOut, y, stackLayout.mMaxTranslationZ, true);
    }

    public TaskViewTransform getTransform(int taskIndex, float stackScroll,
            TaskViewTransform transformOut, int taskCount, TaskStackLayoutAlgorithm stackLayout) {
        if (taskCount == 0) {
            transformOut.reset();
            return transformOut;
        }
        boolean visible = true;
        int y;
        if (taskCount > 1) {
            y = getTaskTopFromIndex(taskIndex) - percentageToScroll(stackScroll);

            // Check visibility from the bottom of the task
            visible = y + mPadding + getTaskRect().height() > 0;
        } else {
            int windowHeight = mWindowRect.height() - mSystemInsets.bottom;
            y = (windowHeight - mTaskRect.height()) / 2 - percentageToScroll(stackScroll);
        }
        fillStackTransform(transformOut, y, stackLayout.mMaxTranslationZ, visible);
        return transformOut;
    }

    /**
     * Finds the closest task to the scroll percentage in the y axis and returns the percentage of
     * the task to scroll to.
     * @param scrollP percentage to find nearest to
     * @param numTasks number of tasks in recents stack
     * @param velocity speed of fling
     */
    public float getClosestTaskP(float scrollP, int numTasks, int velocity) {
        int y = percentageToScroll(scrollP);

        int lastY = getTaskTopFromIndex(0) - mPaddingEndTopBottom;
        for (int i = 1; i < numTasks; i++) {
            int taskY = getTaskTopFromIndex(i) - mPaddingEndTopBottom;
            int diff = taskY - y;
            if (diff > 0) {
                int diffPrev = Math.abs(y - lastY);
                boolean useNext = diff > diffPrev;
                if (Math.abs(velocity) > mFlingThreshold) {
                    useNext = velocity > 0;
                }
                return useNext
                        ? scrollToPercentage(lastY) : scrollToPercentage(taskY);
            }
            lastY = taskY;
        }
        return scrollToPercentage(lastY);
    }

    /**
     * Convert a scroll value to a percentage
     * @param scroll a scroll value
     * @return a percentage that represents the scroll from the total height of tasks
     */
    public float scrollToPercentage(int scroll) {
        return (float) scroll / (mTaskRect.height() + mPadding);
    }

    /**
     * Converts a percentage to the scroll value from the total height of tasks
     * @param p a percentage that represents the scroll value
     * @return a scroll value in pixels
     */
    public int percentageToScroll(float p) {
        return (int) (p * (mTaskRect.height() + mPadding));
    }

    /**
     * Get the min scroll progress for low ram layout. This computes the top position of the
     * first task and reduce by the end padding to center the first task
     * @return position of max scroll
     */
    public float getMinScrollP() {
        return getScrollPForTask(0);
    }

    /**
     * Get the max scroll progress for low ram layout. This computes the top position of the last
     * task and reduce by the end padding to center the last task
     * @param taskCount the amount of tasks in the recents stack
     * @return position of max scroll
     */
    public float getMaxScrollP(int taskCount) {
        return getScrollPForTask(taskCount - 1);
    }

    /**
     * Get the initial scroll value whether launched from home or from an app.
     * @param taskCount the amount of tasks currently in recents
     * @param fromHome if launching recents from home or not
     * @return from home it will return max value and from app it will return 2nd last task
     */
    public float getInitialScrollP(int taskCount, boolean fromHome) {
        if (fromHome) {
            return getMaxScrollP(taskCount);
        }
        if (taskCount < 2) {
            return 0;
        }
        return getScrollPForTask(taskCount - 2);
    }

    /**
     * Get the scroll progress for any task
     * @param taskIndex task index to get the scroll progress of
     * @return scroll progress of task
     */
    public float getScrollPForTask(int taskIndex) {
        return scrollToPercentage(getTaskTopFromIndex(taskIndex) - mPaddingEndTopBottom);
    }

    public Rect getTaskRect() {
        return mTaskRect;
    }

    public float getMaxOverscroll() {
        return MAX_OVERSCROLL;
    }

    private int getTaskTopFromIndex(int index) {
        return getTotalHeightOfTasks(index) - mTopOffset;
    }

    private int getTotalHeightOfTasks(int taskCount) {
        return taskCount * mTaskRect.height() + (taskCount + 1) * mPadding;
    }

    private void fillStackTransform(TaskViewTransform transformOut, int y, int translationZ,
            boolean visible) {
        transformOut.scale = 1f;
        transformOut.alpha = 1f;
        transformOut.translationZ = translationZ;
        transformOut.dimAlpha = 0f;
        transformOut.viewOutlineAlpha = 1f;
        transformOut.rect.set(getTaskRect());
        transformOut.rect.offset(mPaddingLeftRight + mSystemInsets.left, y);
        Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
        transformOut.visible = visible;
    }
}
