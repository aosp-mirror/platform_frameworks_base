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
import android.graphics.RectF;
import android.util.ArrayMap;

import com.android.systemui.R;
import com.android.systemui.recents.model.Task;

import java.util.Collections;
import java.util.List;

/**
 * The layout logic for the contents of the freeform workspace.
 */
public class FreeformWorkspaceLayoutAlgorithm {

    // Optimization, allows for quick lookup of task -> rect
    private ArrayMap<Task.TaskKey, RectF> mTaskRectMap = new ArrayMap<>();

    private int mTaskPadding;

    public FreeformWorkspaceLayoutAlgorithm(Context context) {
        reloadOnConfigurationChange(context);
    }

    /**
     * Reloads the layout for the current configuration.
     */
    public void reloadOnConfigurationChange(Context context) {
        // This is applied to the edges of each task
        mTaskPadding = context.getResources().getDimensionPixelSize(
                R.dimen.recents_freeform_layout_task_padding) / 2;
    }

    /**
     * Updates the layout for each of the freeform workspace tasks.  This is called after the stack
     * layout is updated.
     */
    public void update(List<Task> freeformTasks, TaskStackLayoutAlgorithm stackLayout) {
        Collections.reverse(freeformTasks);
        mTaskRectMap.clear();

        int numFreeformTasks = stackLayout.mNumFreeformTasks;
        if (!freeformTasks.isEmpty()) {

            // Normalize the widths so that we can calculate the best layout below
            int workspaceWidth = stackLayout.mFreeformRect.width();
            int workspaceHeight = stackLayout.mFreeformRect.height();
            float normalizedWorkspaceWidth = (float) workspaceWidth / workspaceHeight;
            float normalizedWorkspaceHeight = 1f;
            float[] normalizedTaskWidths = new float[numFreeformTasks];
            for (int i = 0; i < numFreeformTasks; i++) {
                Task task = freeformTasks.get(i);
                float rowTaskWidth;
                if (task.bounds != null) {
                    rowTaskWidth = (float) task.bounds.width() / task.bounds.height();
                } else {
                    // If this is a stack task that was dragged into the freeform workspace, then
                    // the task will not yet have an associated bounds, so assume the full workspace
                    // width for the time being
                    rowTaskWidth = normalizedWorkspaceWidth;
                }
                // Bound the task width to the workspace width so that at the worst case, it will
                // fit its own row
                normalizedTaskWidths[i] = Math.min(rowTaskWidth, normalizedWorkspaceWidth);
            }

            // Determine the scale to best fit each of the tasks in the workspace
            float rowScale = 0.85f;
            float rowWidth = 0f;
            float maxRowWidth = 0f;
            int rowCount = 1;
            for (int i = 0; i < numFreeformTasks;) {
                float width = normalizedTaskWidths[i] * rowScale;
                if (rowWidth + width > normalizedWorkspaceWidth) {
                    // That is too long for this row, create new row
                    if ((rowCount + 1) * rowScale > normalizedWorkspaceHeight) {
                        // The new row is too high, so we need to try fitting again.  Update the
                        // scale to be the smaller of the scale needed to fit the task in the
                        // previous row, or the scale needed to fit the new row
                        rowScale = Math.min(normalizedWorkspaceWidth / (rowWidth + width),
                                normalizedWorkspaceHeight / (rowCount + 1));
                        rowCount = 1;
                        rowWidth = 0;
                        i = 0;
                    } else {
                        // The new row fits, so continue
                        rowWidth = width;
                        rowCount++;
                        i++;
                    }
                } else {
                    // Task is OK in this row
                    rowWidth += width;
                    i++;
                }
                maxRowWidth = Math.max(rowWidth, maxRowWidth);
            }

            // Normalize each of the actual rects to that scale
            float defaultRowLeft = ((1f - (maxRowWidth / normalizedWorkspaceWidth)) *
                    workspaceWidth) / 2f;
            float rowLeft = defaultRowLeft;
            float rowTop = ((1f - (rowScale * rowCount)) * workspaceHeight) / 2f;
            float rowHeight = rowScale * workspaceHeight;
            for (int i = 0; i < numFreeformTasks; i++) {
                Task task = freeformTasks.get(i);
                float width = rowHeight * normalizedTaskWidths[i];
                if (rowLeft + width > workspaceWidth) {
                    // This goes on the next line
                    rowTop += rowHeight;
                    rowLeft = defaultRowLeft;
                }
                RectF rect = new RectF(rowLeft, rowTop, rowLeft + width, rowTop + rowHeight);
                rect.inset(mTaskPadding, mTaskPadding);
                rowLeft += width;
                mTaskRectMap.put(task.key, rect);
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
        return mTaskRectMap.containsKey(task.key);
    }

    /**
     * Returns the transform for the given task.  Any rect returned will be offset by the actual
     * transform for the freeform workspace.
     */
    public TaskViewTransform getTransform(Task task, TaskViewTransform transformOut,
            TaskStackLayoutAlgorithm stackLayout) {
        if (mTaskRectMap.containsKey(task.key)) {
            final RectF ffRect = mTaskRectMap.get(task.key);

            transformOut.scale = 1f;
            transformOut.alpha = 1f;
            transformOut.translationZ = stackLayout.mMaxTranslationZ;
            transformOut.dimAlpha = 0f;
            transformOut.viewOutlineAlpha = TaskStackLayoutAlgorithm.OUTLINE_ALPHA_MAX_VALUE;
            transformOut.rect.set(ffRect);
            transformOut.rect.offset(stackLayout.mFreeformRect.left, stackLayout.mFreeformRect.top);
            transformOut.visible = true;
            return transformOut;
        }
        return null;
    }
}
