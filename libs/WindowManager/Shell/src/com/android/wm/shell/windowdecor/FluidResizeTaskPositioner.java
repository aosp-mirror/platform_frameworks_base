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
import android.view.SurfaceControl;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;

import java.util.function.Supplier;

/**
 * A task positioner that resizes/relocates task contents as it is dragged.
 * Utilizes {@link DragPositioningCallbackUtility} to determine new task bounds.
 */
class FluidResizeTaskPositioner implements DragPositioningCallback {
    private final ShellTaskOrganizer mTaskOrganizer;
    private final WindowDecoration mWindowDecoration;
    private final Supplier<SurfaceControl.Transaction> mTransactionSupplier;
    private DisplayController mDisplayController;
    private DragPositioningCallbackUtility.DragStartListener mDragStartListener;
    private final Rect mStableBounds = new Rect();
    private final Rect mTaskBoundsAtDragStart = new Rect();
    private final PointF mRepositionStartPoint = new PointF();
    private final Rect mRepositionTaskBounds = new Rect();
    // If a task move (not resize) finishes with the positions y less than this value, do not
    // finalize the bounds there using WCT#setBounds
    private final int mDisallowedAreaForEndBoundsHeight;
    private boolean mHasDragResized;
    private int mCtrlType;

    FluidResizeTaskPositioner(ShellTaskOrganizer taskOrganizer, WindowDecoration windowDecoration,
            DisplayController displayController, int disallowedAreaForEndBoundsHeight) {
        this(taskOrganizer, windowDecoration, displayController, dragStartListener -> {},
                SurfaceControl.Transaction::new, disallowedAreaForEndBoundsHeight);
    }

    FluidResizeTaskPositioner(ShellTaskOrganizer taskOrganizer, WindowDecoration windowDecoration,
            DisplayController displayController,
            DragPositioningCallbackUtility.DragStartListener dragStartListener,
            Supplier<SurfaceControl.Transaction> supplier,
            int disallowedAreaForEndBoundsHeight) {
        mTaskOrganizer = taskOrganizer;
        mWindowDecoration = windowDecoration;
        mDisplayController = displayController;
        mDragStartListener = dragStartListener;
        mTransactionSupplier = supplier;
        mDisallowedAreaForEndBoundsHeight = disallowedAreaForEndBoundsHeight;
        mDisplayController.getDisplayLayout(windowDecoration.mDisplay.getDisplayId())
                .getStableBounds(mStableBounds);
    }

    @Override
    public void onDragPositioningStart(int ctrlType, float x, float y) {
        mCtrlType = ctrlType;
        mTaskBoundsAtDragStart.set(
                mWindowDecoration.mTaskInfo.configuration.windowConfiguration.getBounds());
        mRepositionStartPoint.set(x, y);
        mDragStartListener.onDragStart(mWindowDecoration.mTaskInfo.taskId);
        if (mCtrlType != CTRL_TYPE_UNDEFINED && !mWindowDecoration.mTaskInfo.isFocused) {
            WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.reorder(mWindowDecoration.mTaskInfo.token, true);
            mTaskOrganizer.applyTransaction(wct);
        }
        mRepositionTaskBounds.set(mTaskBoundsAtDragStart);
    }

    @Override
    public void onDragPositioningMove(float x, float y) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        PointF delta = DragPositioningCallbackUtility.calculateDelta(x, y, mRepositionStartPoint);
        if (isResizing() && DragPositioningCallbackUtility.changeBounds(mCtrlType,
                mRepositionTaskBounds, mTaskBoundsAtDragStart, mStableBounds, delta,
                mDisplayController, mWindowDecoration)) {
            // The task is being resized, send the |dragResizing| hint to core with the first
            // bounds-change wct.
            if (!mHasDragResized) {
                // This is the first bounds change since drag resize operation started.
                wct.setDragResizing(mWindowDecoration.mTaskInfo.token, true /* dragResizing */);
            }
            DragPositioningCallbackUtility.applyTaskBoundsChange(wct, mWindowDecoration,
                    mRepositionTaskBounds, mTaskOrganizer);
            mHasDragResized = true;
        } else if (mCtrlType == CTRL_TYPE_UNDEFINED) {
            final SurfaceControl.Transaction t = mTransactionSupplier.get();
            DragPositioningCallbackUtility.setPositionOnDrag(mWindowDecoration,
                    mRepositionTaskBounds, mTaskBoundsAtDragStart, mRepositionStartPoint, t, x, y);
            t.apply();
        }
    }

    @Override
    public void onDragPositioningEnd(float x, float y) {
        // If task has been resized or task was dragged into area outside of
        // mDisallowedAreaForEndBounds, apply WCT to finish it.
        if (isResizing() && mHasDragResized) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setDragResizing(mWindowDecoration.mTaskInfo.token, false /* dragResizing */);
            PointF delta = DragPositioningCallbackUtility.calculateDelta(x, y,
                    mRepositionStartPoint);
            if (DragPositioningCallbackUtility.changeBounds(mCtrlType, mRepositionTaskBounds,
                    mTaskBoundsAtDragStart, mStableBounds, delta, mDisplayController,
                    mWindowDecoration)) {
                wct.setBounds(mWindowDecoration.mTaskInfo.token, mRepositionTaskBounds);
            }
            mTaskOrganizer.applyTransaction(wct);
        } else if (mCtrlType == CTRL_TYPE_UNDEFINED
                && y > mDisallowedAreaForEndBoundsHeight) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            DragPositioningCallbackUtility.onDragEnd(mRepositionTaskBounds,
                    mTaskBoundsAtDragStart, mStableBounds, mRepositionStartPoint, x, y);
            wct.setBounds(mWindowDecoration.mTaskInfo.token, mRepositionTaskBounds);
            mTaskOrganizer.applyTransaction(wct);
        }

        mTaskBoundsAtDragStart.setEmpty();
        mRepositionStartPoint.set(0, 0);
        mCtrlType = CTRL_TYPE_UNDEFINED;
        mHasDragResized = false;
    }

    private boolean isResizing() {
        return (mCtrlType & CTRL_TYPE_TOP) != 0 || (mCtrlType & CTRL_TYPE_BOTTOM) != 0
                || (mCtrlType & CTRL_TYPE_LEFT) != 0 || (mCtrlType & CTRL_TYPE_RIGHT) != 0;
    }

}
