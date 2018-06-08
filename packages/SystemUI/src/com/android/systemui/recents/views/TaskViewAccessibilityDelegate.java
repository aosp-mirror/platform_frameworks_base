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
 * limitations under the License
 */

package com.android.systemui.recents.views;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Point;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.recents.model.TaskStack;

public class TaskViewAccessibilityDelegate extends View.AccessibilityDelegate {
    private static final String TAG = "TaskViewAccessibilityDelegate";

    private final TaskView mTaskView;

    protected static final int SPLIT_TASK_TOP = R.id.action_split_task_to_top;
    protected static final int SPLIT_TASK_LEFT = R.id.action_split_task_to_left;
    protected static final int SPLIT_TASK_RIGHT = R.id.action_split_task_to_right;

    protected final SparseArray<AccessibilityAction> mActions = new SparseArray<>();

    public TaskViewAccessibilityDelegate(TaskView taskView) {
        mTaskView = taskView;
        Context context = taskView.getContext();
        mActions.put(SPLIT_TASK_TOP, new AccessibilityAction(SPLIT_TASK_TOP,
                context.getString(R.string.recents_accessibility_split_screen_top)));
        mActions.put(SPLIT_TASK_LEFT, new AccessibilityAction(SPLIT_TASK_LEFT,
                context.getString(R.string.recents_accessibility_split_screen_left)));
        mActions.put(SPLIT_TASK_RIGHT, new AccessibilityAction(SPLIT_TASK_RIGHT,
                context.getString(R.string.recents_accessibility_split_screen_right)));
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        if (ActivityManager.supportsSplitScreenMultiWindow(mTaskView.getContext())
                && !Recents.getSystemServices().hasDockedTask()) {
            DockState[] dockStates = Recents.getConfiguration()
                    .getDockStatesForCurrentOrientation();
            for (DockState dockState: dockStates) {
                if (dockState == DockState.TOP) {
                    info.addAction(mActions.get(SPLIT_TASK_TOP));
                } else if (dockState == DockState.LEFT) {
                    info.addAction(mActions.get(SPLIT_TASK_LEFT));
                } else if (dockState == DockState.RIGHT) {
                    info.addAction(mActions.get(SPLIT_TASK_RIGHT));
                }
            }
        }
    }

    @Override
    public boolean performAccessibilityAction(View host, int action, Bundle args) {
        if (action == SPLIT_TASK_TOP) {
            simulateDragIntoMultiwindow(DockState.TOP);
        } else if (action == SPLIT_TASK_LEFT) {
            simulateDragIntoMultiwindow(DockState.LEFT);
        } else if (action == SPLIT_TASK_RIGHT) {
            simulateDragIntoMultiwindow(DockState.RIGHT);
        } else {
            return super.performAccessibilityAction(host, action, args);
        }
        return true;
    }

    /** Simulate a user drag event to split the screen to the respected side */
    private void simulateDragIntoMultiwindow(DockState dockState) {
        EventBus.getDefault().send(new DragStartEvent(mTaskView.getTask(), mTaskView,
                new Point(0,0), false /* isUserTouchInitiated */));
        EventBus.getDefault().send(new DragEndEvent(mTaskView.getTask(), mTaskView, dockState));
    }
}
