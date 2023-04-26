/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.windowdecor;

import android.graphics.PointF;
import android.graphics.Rect;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;

/**
 * A task positioner that resizes/relocates task contents as it is dragged.
 * Utilizes {@link DragPositioningCallbackUtility} to determine new task bounds.
 */
class FluidResizeTaskPositioner implements DragPositioningCallback {
    private final ShellTaskOrganizer mTaskOrganizer;
    private final WindowDecoration mWindowDecoration;
    private DisplayController mDisplayController;
    private DragPositioningCallbackUtility.DragStartListener mDragStartListener;
    private final Rect mStableBounds = new Rect();
    private final Rect mTaskBoundsAtDragStart = new Rect();
    private final PointF mRepositionStartPoint = new PointF();
    private final Rect mRepositionTaskBounds = new Rect();
    private int mCtrlType;
    private boolean mHasMoved;

    FluidResizeTaskPositioner(ShellTaskOrganizer taskOrganizer, WindowDecoration windowDecoration,
            DisplayController displayController) {
        this(taskOrganizer, windowDecoration, displayController, dragStartListener -> {});
    }

    FluidResizeTaskPositioner(ShellTaskOrganizer taskOrganizer, WindowDecoration windowDecoration,
            DisplayController displayController,
            DragPositioningCallbackUtility.DragStartListener dragStartListener) {
        mTaskOrganizer = taskOrganizer;
        mWindowDecoration = windowDecoration;
        mDisplayController = displayController;
        mDragStartListener = dragStartListener;
    }

    @Override
    public void onDragPositioningStart(int ctrlType, float x, float y) {
        mCtrlType = ctrlType;
        mTaskBoundsAtDragStart.set(
                mWindowDecoration.mTaskInfo.configuration.windowConfiguration.getBounds());
        mRepositionStartPoint.set(x, y);
        mDragStartListener.onDragStart(mWindowDecoration.mTaskInfo.taskId);
    }

    @Override
    public void onDragPositioningMove(float x, float y) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        PointF delta = DragPositioningCallbackUtility.calculateDelta(x, y, mRepositionStartPoint);
        if (DragPositioningCallbackUtility.changeBounds(mCtrlType, mHasMoved,
                mRepositionTaskBounds, mTaskBoundsAtDragStart, mStableBounds, delta,
                mDisplayController, mWindowDecoration)) {
            // The task is being resized, send the |dragResizing| hint to core with the first
            // bounds-change wct.
            if (!mHasMoved && mCtrlType != CTRL_TYPE_UNDEFINED) {
                // This is the first bounds change since drag resize operation started.
                wct.setDragResizing(mWindowDecoration.mTaskInfo.token, true /* dragResizing */);
            }
            DragPositioningCallbackUtility.applyTaskBoundsChange(wct, mWindowDecoration,
                    mRepositionTaskBounds, mTaskOrganizer);
            mHasMoved = true;
        }
    }

    @Override
    public void onDragPositioningEnd(float x, float y) {
        // |mHasMoved| being false means there is no real change to the task bounds in WM core, so
        // we don't need a WCT to finish it.
        if (mHasMoved) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setDragResizing(mWindowDecoration.mTaskInfo.token, false /* dragResizing */);
            PointF delta = DragPositioningCallbackUtility.calculateDelta(x, y,
                    mRepositionStartPoint);
            if (DragPositioningCallbackUtility.changeBounds(mCtrlType, mHasMoved,
                    mRepositionTaskBounds, mTaskBoundsAtDragStart, mStableBounds, delta,
                    mDisplayController, mWindowDecoration)) {
                wct.setBounds(mWindowDecoration.mTaskInfo.token, mRepositionTaskBounds);
            }
            mTaskOrganizer.applyTransaction(wct);
        }

        mTaskBoundsAtDragStart.setEmpty();
        mRepositionStartPoint.set(0, 0);
        mCtrlType = CTRL_TYPE_UNDEFINED;
        mHasMoved = false;
    }
}
