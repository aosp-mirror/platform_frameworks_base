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
 * A task positioner that also takes into account resizing a
 * {@link com.android.wm.shell.windowdecor.ResizeVeil}.
 * If the drag is resizing the task, we resize the veil instead.
 * If the drag is repositioning, we update in the typical manner.
 */
public class VeiledResizeTaskPositioner implements DragPositioningCallback {

    private DesktopModeWindowDecoration mDesktopWindowDecoration;
    private ShellTaskOrganizer mTaskOrganizer;
    private DisplayController mDisplayController;
    private DragPositioningCallbackUtility.DragStartListener mDragStartListener;
    private final Rect mStableBounds = new Rect();
    private final Rect mTaskBoundsAtDragStart = new Rect();
    private final PointF mRepositionStartPoint = new PointF();
    private final Rect mRepositionTaskBounds = new Rect();
    private int mCtrlType;
    private boolean mHasMoved;


    public VeiledResizeTaskPositioner(ShellTaskOrganizer taskOrganizer,
            DesktopModeWindowDecoration windowDecoration, DisplayController displayController,
            DragPositioningCallbackUtility.DragStartListener dragStartListener) {
        mTaskOrganizer = taskOrganizer;
        mDesktopWindowDecoration = windowDecoration;
        mDisplayController = displayController;
        mDragStartListener = dragStartListener;
    }

    @Override
    public void onDragPositioningStart(int ctrlType, float x, float y) {
        mCtrlType = ctrlType;
        mTaskBoundsAtDragStart.set(
                mDesktopWindowDecoration.mTaskInfo.configuration.windowConfiguration.getBounds());
        mRepositionStartPoint.set(x, y);
        if (mCtrlType != CTRL_TYPE_UNDEFINED) {
            mDesktopWindowDecoration.showResizeVeil();
        }
        mHasMoved = false;
        mDragStartListener.onDragStart(mDesktopWindowDecoration.mTaskInfo.taskId);
    }

    @Override
    public void onDragPositioningMove(float x, float y) {
        PointF delta = DragPositioningCallbackUtility.calculateDelta(x, y, mRepositionStartPoint);
        if (DragPositioningCallbackUtility.changeBounds(mCtrlType, mHasMoved,
                mRepositionTaskBounds, mTaskBoundsAtDragStart, mStableBounds, delta,
                mDisplayController, mDesktopWindowDecoration)) {
            if (mCtrlType != CTRL_TYPE_UNDEFINED) {
                mDesktopWindowDecoration.updateResizeVeil(mRepositionTaskBounds);
            } else {
                DragPositioningCallbackUtility.applyTaskBoundsChange(
                        new WindowContainerTransaction(), mDesktopWindowDecoration,
                        mRepositionTaskBounds, mTaskOrganizer);
            }
            mHasMoved = true;
        }
    }

    @Override
    public void onDragPositioningEnd(float x, float y) {
        PointF delta = DragPositioningCallbackUtility.calculateDelta(x, y,
                mRepositionStartPoint);
        if (mHasMoved && DragPositioningCallbackUtility.changeBounds(mCtrlType, mHasMoved,
                mRepositionTaskBounds, mTaskBoundsAtDragStart, mStableBounds, delta,
                mDisplayController, mDesktopWindowDecoration)) {
            DragPositioningCallbackUtility.applyTaskBoundsChange(
                    new WindowContainerTransaction(), mDesktopWindowDecoration,
                    mRepositionTaskBounds, mTaskOrganizer);
        }
        // TODO: (b/279062291) Synchronize the start of hide to the end of the draw triggered above.
        if (mCtrlType != CTRL_TYPE_UNDEFINED) {
            mDesktopWindowDecoration.updateResizeVeil(mRepositionTaskBounds);
            mDesktopWindowDecoration.hideResizeVeil();
        }
        mCtrlType = CTRL_TYPE_UNDEFINED;
        mTaskBoundsAtDragStart.setEmpty();
        mRepositionStartPoint.set(0, 0);
        mHasMoved = false;
    }
}
