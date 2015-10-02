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

import android.graphics.Rect;
import android.graphics.Region;
import android.view.DisplayInfo;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy.PointerEventListener;

import com.android.server.wm.WindowManagerService.H;

import static android.view.PointerIcon.STYLE_NOT_SPECIFIED;
import static android.view.PointerIcon.STYLE_DEFAULT;
import static android.view.PointerIcon.STYLE_HORIZONTAL_DOUBLE_ARROW;
import static android.view.PointerIcon.STYLE_VERTICAL_DOUBLE_ARROW;
import static android.view.PointerIcon.STYLE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW;
import static android.view.PointerIcon.STYLE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW;

public class TaskTapPointerEventListener implements PointerEventListener {
    private static final int TAP_TIMEOUT_MSEC = 300;
    private static final float TAP_MOTION_SLOP_INCHES = 0.125f;

    private final int mMotionSlop;
    private float mDownX;
    private float mDownY;
    private int mPointerId;
    final private Region mTouchExcludeRegion = new Region();
    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final Rect mTmpRect = new Rect();
    private int mPointerIconShape = STYLE_NOT_SPECIFIED;

    public TaskTapPointerEventListener(WindowManagerService service,
            DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        DisplayInfo info = displayContent.getDisplayInfo();
        mMotionSlop = (int)(info.logicalDensityDpi * TAP_MOTION_SLOP_INCHES);
    }

    @Override
    public void onPointerEvent(MotionEvent motionEvent) {
        final int action = motionEvent.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                mPointerId = motionEvent.getPointerId(0);
                mDownX = motionEvent.getX();
                mDownY = motionEvent.getY();

                final int x = (int) mDownX;
                final int y = (int) mDownY;
                synchronized (this) {
                    if (!mTouchExcludeRegion.contains(x, y)) {
                        mService.mH.obtainMessage(H.TAP_DOWN_OUTSIDE_TASK, x, y,
                                mDisplayContent).sendToTarget();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mPointerId >= 0) {
                    int index = motionEvent.findPointerIndex(mPointerId);
                    if ((motionEvent.getEventTime() - motionEvent.getDownTime()) > TAP_TIMEOUT_MSEC
                            || index < 0
                            || Math.abs(motionEvent.getX(index) - mDownX) > mMotionSlop
                            || Math.abs(motionEvent.getY(index) - mDownY) > mMotionSlop) {
                        mPointerId = -1;
                    }
                }
                break;
            }

            case MotionEvent.ACTION_HOVER_MOVE: {
                final int x = (int) motionEvent.getX();
                final int y = (int) motionEvent.getY();
                final WindowState window = mDisplayContent.findWindowForControlPoint(x, y);
                if (window == null) {
                    break;
                }
                window.getVisibleBounds(mTmpRect, false);
                if (!mTmpRect.isEmpty() && !mTmpRect.contains(x, y)) {
                    int iconShape = STYLE_DEFAULT;
                    if (x < mTmpRect.left) {
                        iconShape =
                            (y < mTmpRect.top) ? STYLE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW :
                            (y > mTmpRect.bottom) ? STYLE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW :
                            STYLE_HORIZONTAL_DOUBLE_ARROW;
                    } else if (x > mTmpRect.right) {
                        iconShape =
                            (y < mTmpRect.top) ? STYLE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW :
                            (y > mTmpRect.bottom) ? STYLE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW :
                            STYLE_HORIZONTAL_DOUBLE_ARROW;
                    } else if (y < mTmpRect.top || y > mTmpRect.bottom) {
                        iconShape = STYLE_VERTICAL_DOUBLE_ARROW;
                    }
                    if (mPointerIconShape != iconShape) {
                        mPointerIconShape = iconShape;
                        motionEvent.getDevice().setPointerShape(iconShape);
                    }
                } else {
                    mPointerIconShape = STYLE_NOT_SPECIFIED;
                }
            } break;

            case MotionEvent.ACTION_HOVER_EXIT:
                motionEvent.getDevice().setPointerShape(STYLE_DEFAULT);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = (action & MotionEvent.ACTION_POINTER_INDEX_MASK)
                        >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                // Extract the index of the pointer that left the touch sensor
                if (mPointerId == motionEvent.getPointerId(index)) {
                    final int x = (int)motionEvent.getX(index);
                    final int y = (int)motionEvent.getY(index);
                    synchronized(this) {
                        if ((motionEvent.getEventTime() - motionEvent.getDownTime())
                                < TAP_TIMEOUT_MSEC
                                && Math.abs(x - mDownX) < mMotionSlop
                                && Math.abs(y - mDownY) < mMotionSlop
                                && !mTouchExcludeRegion.contains(x, y)) {
                            mService.mH.obtainMessage(H.TAP_OUTSIDE_TASK, x, y,
                                    mDisplayContent).sendToTarget();
                        }
                    }
                    mPointerId = -1;
                }
                break;
            }
        }
    }

    void setTouchExcludeRegion(Region newRegion) {
        synchronized (this) {
           mTouchExcludeRegion.set(newRegion);
        }
    }
}
