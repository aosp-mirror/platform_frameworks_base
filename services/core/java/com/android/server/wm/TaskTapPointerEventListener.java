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
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy.PointerEventListener;

import com.android.server.wm.WindowManagerService.H;

import static android.view.PointerIcon.TYPE_NOT_SPECIFIED;
import static android.view.PointerIcon.TYPE_DEFAULT;
import static android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW;
import static android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW;
import static android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW;
import static android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW;

public class TaskTapPointerEventListener implements PointerEventListener {

    final private Region mTouchExcludeRegion = new Region();
    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final Rect mTmpRect = new Rect();
    private final Region mNonResizeableRegion = new Region();
    private boolean mTwoFingerScrolling;
    private boolean mInGestureDetection;
    private GestureDetector mGestureDetector;
    private int mPointerIconType = TYPE_NOT_SPECIFIED;

    public TaskTapPointerEventListener(WindowManagerService service,
            DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
    }

    // initialize the object, note this must be done outside WindowManagerService
    // ctor, otherwise it may cause recursion as some code in GestureDetector ctor
    // depends on WMS being already created.
    void init() {
        mGestureDetector = new GestureDetector(
                mService.mContext, new TwoFingerScrollListener(), mService.mH);
    }

    @Override
    public void onPointerEvent(MotionEvent motionEvent) {
        doGestureDetection(motionEvent);

        final int action = motionEvent.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                final int x = (int) motionEvent.getX();
                final int y = (int) motionEvent.getY();

                synchronized (this) {
                    if (!mTouchExcludeRegion.contains(x, y)) {
                        mService.mH.obtainMessage(H.TAP_OUTSIDE_TASK,
                                x, y, mDisplayContent).sendToTarget();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (motionEvent.getPointerCount() != 2) {
                    stopTwoFingerScroll();
                }
                break;
            }

            case MotionEvent.ACTION_HOVER_MOVE: {
                final int x = (int) motionEvent.getX();
                final int y = (int) motionEvent.getY();
                final Task task = mDisplayContent.findTaskForControlPoint(x, y);
                InputDevice inputDevice = motionEvent.getDevice();
                if (task == null || inputDevice == null) {
                    mPointerIconType = TYPE_NOT_SPECIFIED;
                    break;
                }
                task.getDimBounds(mTmpRect);
                if (!mTmpRect.isEmpty() && !mTmpRect.contains(x, y)) {
                    int iconType = TYPE_DEFAULT;
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
                    if (mPointerIconType != iconType) {
                        mPointerIconType = iconType;
                        inputDevice.setPointerType(iconType);
                    }
                } else {
                    mPointerIconType = TYPE_NOT_SPECIFIED;
                }
            } break;

            case MotionEvent.ACTION_HOVER_EXIT:
                mPointerIconType = TYPE_NOT_SPECIFIED;
                InputDevice inputDevice = motionEvent.getDevice();
                if (inputDevice != null) {
                    inputDevice.setPointerType(TYPE_DEFAULT);
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                stopTwoFingerScroll();
                break;
            }
        }
    }

    private void doGestureDetection(MotionEvent motionEvent) {
        if (mGestureDetector == null || mNonResizeableRegion.isEmpty()) {
            return;
        }
        final int action = motionEvent.getAction() & MotionEvent.ACTION_MASK;
        final int x = (int) motionEvent.getX();
        final int y = (int) motionEvent.getY();
        final boolean isTouchInside = mNonResizeableRegion.contains(x, y);
        if (mInGestureDetection || action == MotionEvent.ACTION_DOWN && isTouchInside) {
            // If we receive the following actions, or the pointer goes out of the area
            // we're interested in, stop detecting and cancel the current detection.
            mInGestureDetection = isTouchInside
                    && action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_POINTER_UP
                    && action != MotionEvent.ACTION_CANCEL;
            if (mInGestureDetection) {
                mGestureDetector.onTouchEvent(motionEvent);
            } else {
                MotionEvent cancelEvent = motionEvent.copy();
                cancelEvent.cancel();
                mGestureDetector.onTouchEvent(cancelEvent);
                stopTwoFingerScroll();
            }
        }
    }

    private void onTwoFingerScroll(MotionEvent e) {
        final int x = (int)e.getX(0);
        final int y = (int)e.getY(0);
        if (!mTwoFingerScrolling) {
            mTwoFingerScrolling = true;
            mService.mH.obtainMessage(
                    H.TWO_FINGER_SCROLL_START, x, y, mDisplayContent).sendToTarget();
        }
    }

    private void stopTwoFingerScroll() {
        if (mTwoFingerScrolling) {
            mTwoFingerScrolling = false;
            mService.mH.obtainMessage(H.FINISH_TASK_POSITIONING).sendToTarget();
        }
    }

    private final class TwoFingerScrollListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2,
                float distanceX, float distanceY) {
            if (e2.getPointerCount() == 2) {
                onTwoFingerScroll(e2);
                return true;
            }
            stopTwoFingerScroll();
            return false;
        }
    }

    void setTouchExcludeRegion(Region newRegion, Region nonResizeableRegion) {
        synchronized (this) {
           mTouchExcludeRegion.set(newRegion);
           mNonResizeableRegion.set(nonResizeableRegion);
        }
    }
}
