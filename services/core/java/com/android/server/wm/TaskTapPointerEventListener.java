/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW;
import static android.view.PointerIcon.TYPE_NOT_SPECIFIED;
import static android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW;
import static android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW;
import static android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW;

import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants.PointerEventListener;

import com.android.server.wm.WindowManagerService.H;

/**
 * 1. Adjust the top most focus display if touch down on some display.
 * 2. Adjust the pointer icon when cursor moves to the task bounds.
 */
public class TaskTapPointerEventListener implements PointerEventListener {

    private final Region mTouchExcludeRegion = new Region();
    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final Rect mTmpRect = new Rect();
    private int mPointerIconType = TYPE_NOT_SPECIFIED;

    public TaskTapPointerEventListener(WindowManagerService service,
            DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
    }

    private void restorePointerIcon(int x, int y) {
        if (mPointerIconType != TYPE_NOT_SPECIFIED) {
            mPointerIconType = TYPE_NOT_SPECIFIED;
            // Find the underlying window and ask it to restore the pointer icon.
            mService.mH.removeMessages(H.RESTORE_POINTER_ICON);
            mService.mH.obtainMessage(H.RESTORE_POINTER_ICON,
                    x, y, mDisplayContent).sendToTarget();
        }
    }

    @Override
    public void onPointerEvent(MotionEvent motionEvent) {
        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                final int x;
                final int y;
                if (motionEvent.getSource() == InputDevice.SOURCE_MOUSE) {
                    x = (int) motionEvent.getXCursorPosition();
                    y = (int) motionEvent.getYCursorPosition();
                } else {
                    x = (int) motionEvent.getX();
                    y = (int) motionEvent.getY();
                }

                synchronized (this) {
                    if (!mTouchExcludeRegion.contains(x, y)) {
                        mService.mTaskPositioningController.handleTapOutsideTask(
                                mDisplayContent, x, y);
                    }
                }
            }
            break;
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE: {
                final int x = (int) motionEvent.getX();
                final int y = (int) motionEvent.getY();
                if (mTouchExcludeRegion.contains(x, y)) {
                    restorePointerIcon(x, y);
                    break;
                }
                final Task task = mDisplayContent.findTaskForResizePoint(x, y);
                int iconType = TYPE_NOT_SPECIFIED;
                if (task != null) {
                    task.getDimBounds(mTmpRect);
                    if (!mTmpRect.isEmpty() && !mTmpRect.contains(x, y)) {
                        if (x < mTmpRect.left) {
                            iconType =
                                (y < mTmpRect.top) ? TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW :
                                (y > mTmpRect.bottom) ? TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW :
                                TYPE_HORIZONTAL_DOUBLE_ARROW;
                        } else if (x > mTmpRect.right) {
                            iconType =
                                (y < mTmpRect.top) ? TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW :
                                (y > mTmpRect.bottom) ? TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW :
                                TYPE_HORIZONTAL_DOUBLE_ARROW;
                        } else if (y < mTmpRect.top || y > mTmpRect.bottom) {
                            iconType = TYPE_VERTICAL_DOUBLE_ARROW;
                        }
                    }
                }
                if (mPointerIconType != iconType) {
                    mPointerIconType = iconType;
                    if (mPointerIconType == TYPE_NOT_SPECIFIED) {
                        // Find the underlying window and ask it restore the pointer icon.
                        mService.mH.removeMessages(H.RESTORE_POINTER_ICON);
                        mService.mH.obtainMessage(H.RESTORE_POINTER_ICON,
                                x, y, mDisplayContent).sendToTarget();
                    } else {
                        InputManager.getInstance().setPointerIconType(mPointerIconType);
                    }
                }
            }
            break;
            case MotionEvent.ACTION_HOVER_EXIT: {
                final int x = (int) motionEvent.getX();
                final int y = (int) motionEvent.getY();
                restorePointerIcon(x, y);
            }
            break;
        }
    }

    void setTouchExcludeRegion(Region newRegion) {
        synchronized (this) {
           mTouchExcludeRegion.set(newRegion);
        }
    }
}
