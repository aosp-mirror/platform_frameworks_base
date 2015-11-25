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

import android.util.Log;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;

import java.util.HashMap;
import java.util.List;

/**
 * The layout logic for the contents of the freeform workspace.
 */
public class FreeformWorkspaceLayoutAlgorithm {

    private static final String TAG = "FreeformWorkspaceLayoutAlgorithm";
    private static final boolean DEBUG = false;

    // The number of cells in the freeform workspace
    private int mFreeformCellXCount;
    private int mFreeformCellYCount;

    // The width and height of the cells in the freeform workspace
    private int mFreeformCellWidth;
    private int mFreeformCellHeight;

    // Optimization, allows for quick lookup of task -> index
    private HashMap<Task.TaskKey, Integer> mTaskIndexMap = new HashMap<>();

    /**
     * Updates the layout for each of the freeform workspace tasks.  This is called after the stack
     * layout is updated.
     */
    public void update(List<Task> freeformTasks, TaskStackLayoutAlgorithm stackLayout) {
        mTaskIndexMap.clear();

        int numFreeformTasks = stackLayout.mNumFreeformTasks;
        if (!freeformTasks.isEmpty()) {
            // Calculate the cell width/height depending on the number of freeform tasks
            mFreeformCellXCount = Math.max(2, (int) Math.ceil(Math.sqrt(numFreeformTasks)));
            mFreeformCellYCount = Math.max(2, (int) Math.ceil((float) numFreeformTasks /
                    mFreeformCellXCount));
            // For now, make the cells square
            mFreeformCellWidth = Math.min(stackLayout.mFreeformRect.width() / mFreeformCellXCount,
                    stackLayout.mFreeformRect.height() / mFreeformCellYCount);
            mFreeformCellHeight = mFreeformCellWidth;

            // Put each of the tasks in the progress map at a fixed index (does not need to actually
            // map to a scroll position, just by index)
            int taskCount = freeformTasks.size();
            for (int i = taskCount - 1; i >= 0; i--) {
                Task task = freeformTasks.get(i);
                mTaskIndexMap.put(task.key, taskCount - i - 1);
            }

            if (DEBUG) {
                Log.d(TAG, "mFreeformCellXCount: " + mFreeformCellXCount);
                Log.d(TAG, "mFreeformCellYCount: " + mFreeformCellYCount);
                Log.d(TAG, "mFreeformCellWidth: " + mFreeformCellWidth);
                Log.d(TAG, "mFreeformCellHeight: " + mFreeformCellHeight);
            }
        }
    }

    /**
     * Returns whether the transform is available for the given task.
     */
    public boolean isTransformAvailable(Task task, TaskStackLayoutAlgorithm stackLayout) {
        if (stackLayout.mNumFreeformTasks == 0 || task == null) {
            return false;
        }
        return mTaskIndexMap.containsKey(task.key);
    }

    /**
     * Returns the transform for the given task.  Any rect returned will be offset by the actual
     * transform for the freeform workspace.
     */
    public TaskViewTransform getTransform(Task task, TaskViewTransform transformOut,
            TaskStackLayoutAlgorithm stackLayout) {
        if (mTaskIndexMap.containsKey(task.key)) {
            // This is a freeform task, so lay it out in the freeform workspace
            int taskIndex = mTaskIndexMap.get(task.key);
            int topOffset = (stackLayout.mFreeformRect.top - stackLayout.mTaskRect.top);
            int x = taskIndex % mFreeformCellXCount;
            int y = taskIndex / mFreeformCellXCount;
            float scale = (float) mFreeformCellWidth / stackLayout.mTaskRect.width();
            int scaleXOffset = (int) (((1f - scale) * stackLayout.mTaskRect.width()) / 2);
            int scaleYOffset = (int) (((1f - scale) * stackLayout.mTaskRect.height()) / 2);
            transformOut.scale = scale * 0.9f;
            transformOut.translationX = x * mFreeformCellWidth - scaleXOffset;
            transformOut.translationY = topOffset + y * mFreeformCellHeight - scaleYOffset;
            transformOut.translationZ = stackLayout.mMaxTranslationZ;
            transformOut.rect.set(stackLayout.mTaskRect);
            transformOut.rect.offset(transformOut.translationX, transformOut.translationY);
            Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
            transformOut.visible = true;
            transformOut.p = 1f;

            if (DEBUG) {
                Log.d(TAG, "getTransform: " + task.key + ", " + transformOut);
            }

            return transformOut;
        }
        return null;
    }
}
