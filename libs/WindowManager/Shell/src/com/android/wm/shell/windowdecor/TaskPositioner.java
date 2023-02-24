/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntDef;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;

class TaskPositioner implements DragPositioningCallback {

    @IntDef({CTRL_TYPE_UNDEFINED, CTRL_TYPE_LEFT, CTRL_TYPE_RIGHT, CTRL_TYPE_TOP, CTRL_TYPE_BOTTOM})
    @interface CtrlType {}

    static final int CTRL_TYPE_UNDEFINED = 0;
    static final int CTRL_TYPE_LEFT = 1;
    static final int CTRL_TYPE_RIGHT = 2;
    static final int CTRL_TYPE_TOP = 4;
    static final int CTRL_TYPE_BOTTOM = 8;

    private final ShellTaskOrganizer mTaskOrganizer;
    private final DisplayController mDisplayController;
    private final WindowDecoration mWindowDecoration;

    private final Rect mTaskBoundsAtDragStart = new Rect();
    private final PointF mRepositionStartPoint = new PointF();
    private final Rect mRepositionTaskBounds = new Rect();
    private boolean mHasMoved = false;

    private int mCtrlType;
    private DragStartListener mDragStartListener;

    TaskPositioner(ShellTaskOrganizer taskOrganizer, WindowDecoration windowDecoration,
            DisplayController displayController) {
        this(taskOrganizer, windowDecoration, displayController, dragStartListener -> {});
    }

    TaskPositioner(ShellTaskOrganizer taskOrganizer, WindowDecoration windowDecoration,
            DisplayController displayController, DragStartListener dragStartListener) {
        mTaskOrganizer = taskOrganizer;
        mWindowDecoration = windowDecoration;
        mDisplayController = displayController;
        mDragStartListener = dragStartListener;
    }

    @Override
    public void onDragPositioningStart(int ctrlType, float x, float y) {
        mHasMoved = false;

        mDragStartListener.onDragStart(mWindowDecoration.mTaskInfo.taskId);
        mCtrlType = ctrlType;

        mTaskBoundsAtDragStart.set(
                mWindowDecoration.mTaskInfo.configuration.windowConfiguration.getBounds());
        mRepositionStartPoint.set(x, y);
    }

    @Override
    public void onDragPositioningMove(float x, float y) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        if (changeBounds(wct, x, y)) {
            // The task is being resized, send the |dragResizing| hint to core with the first
            // bounds-change wct.
            if (!mHasMoved && mCtrlType != CTRL_TYPE_UNDEFINED) {
                // This is the first bounds change since drag resize operation started.
                wct.setDragResizing(mWindowDecoration.mTaskInfo.token, true /* dragResizing */);
            }
            mTaskOrganizer.applyTransaction(wct);
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
            changeBounds(wct, x, y);
            mTaskOrganizer.applyTransaction(wct);
        }

        mCtrlType = CTRL_TYPE_UNDEFINED;
        mTaskBoundsAtDragStart.setEmpty();
        mRepositionStartPoint.set(0, 0);
        mHasMoved = false;
    }

    private boolean changeBounds(WindowContainerTransaction wct, float x, float y) {
        // |mRepositionTaskBounds| is the bounds last reported if |mHasMoved| is true. If it's not
        // true, we can compare it against |mTaskBoundsAtDragStart|.
        final int oldLeft = mHasMoved ? mRepositionTaskBounds.left : mTaskBoundsAtDragStart.left;
        final int oldTop = mHasMoved ? mRepositionTaskBounds.top : mTaskBoundsAtDragStart.top;
        final int oldRight = mHasMoved ? mRepositionTaskBounds.right : mTaskBoundsAtDragStart.right;
        final int oldBottom =
                mHasMoved ? mRepositionTaskBounds.bottom : mTaskBoundsAtDragStart.bottom;

        final float deltaX = x - mRepositionStartPoint.x;
        final float deltaY = y - mRepositionStartPoint.y;
        mRepositionTaskBounds.set(mTaskBoundsAtDragStart);
        if ((mCtrlType & CTRL_TYPE_LEFT) != 0) {
            mRepositionTaskBounds.left += deltaX;
        }
        if ((mCtrlType & CTRL_TYPE_RIGHT) != 0) {
            mRepositionTaskBounds.right += deltaX;
        }
        if ((mCtrlType & CTRL_TYPE_TOP) != 0) {
            mRepositionTaskBounds.top += deltaY;
        }
        if ((mCtrlType & CTRL_TYPE_BOTTOM) != 0) {
            mRepositionTaskBounds.bottom += deltaY;
        }
        if (mCtrlType == CTRL_TYPE_UNDEFINED) {
            mRepositionTaskBounds.offset((int) deltaX, (int) deltaY);
        }

        // If width or height are negative or less than the minimum width or height, revert the
        // respective bounds to use previous bound dimensions.
        if (mRepositionTaskBounds.width() < getMinWidth()) {
            mRepositionTaskBounds.right = oldRight;
            mRepositionTaskBounds.left = oldLeft;
        }
        if (mRepositionTaskBounds.height() < getMinHeight()) {
            mRepositionTaskBounds.top = oldTop;
            mRepositionTaskBounds.bottom = oldBottom;
        }
        // If there are no changes to the bounds after checking new bounds against minimum width
        // and height, do not set bounds and return false
        if (oldLeft == mRepositionTaskBounds.left && oldTop == mRepositionTaskBounds.top
                && oldRight == mRepositionTaskBounds.right
                && oldBottom == mRepositionTaskBounds.bottom) {
            return false;
        }

        wct.setBounds(mWindowDecoration.mTaskInfo.token, mRepositionTaskBounds);
        return true;
    }

    private float getMinWidth() {
        return mWindowDecoration.mTaskInfo.minWidth < 0 ? getDefaultMinSize()
                : mWindowDecoration.mTaskInfo.minWidth;
    }

    private float getMinHeight() {
        return mWindowDecoration.mTaskInfo.minHeight < 0 ? getDefaultMinSize()
                : mWindowDecoration.mTaskInfo.minHeight;
    }

    private float getDefaultMinSize() {
        float density =  mDisplayController.getDisplayLayout(mWindowDecoration.mTaskInfo.displayId)
                .densityDpi() * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        return mWindowDecoration.mTaskInfo.defaultMinSize * density;
    }

    interface DragStartListener {
        /**
         * Inform the implementing class that a drag resize has started
         * @param taskId id of this positioner's {@link WindowDecoration}
         */
        void onDragStart(int taskId);
    }
}
