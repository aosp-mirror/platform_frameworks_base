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

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;

import android.graphics.PointF;
import android.view.MotionEvent;

/**
 * A detector for touch inputs that differentiates between drag and click inputs.
 * All touch events must be passed through this class to track a drag event.
 */
public class DragDetector {
    private int mTouchSlop;
    private PointF mInputDownPoint;
    private boolean mIsDragEvent;
    private int mDragPointerId;
    public DragDetector(int touchSlop) {
        mTouchSlop = touchSlop;
        mInputDownPoint = new PointF();
        mIsDragEvent = false;
        mDragPointerId = -1;
    }

    /**
     * Determine if {@link MotionEvent} is part of a drag event.
     * @return {@code true} if this is a drag event, {@code false} if not
     */
    public boolean detectDragEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case ACTION_DOWN: {
                mDragPointerId = ev.getPointerId(0);
                float rawX = ev.getRawX(0);
                float rawY = ev.getRawY(0);
                mInputDownPoint.set(rawX, rawY);
                return false;
            }
            case ACTION_MOVE: {
                if (!mIsDragEvent) {
                    int dragPointerIndex = ev.findPointerIndex(mDragPointerId);
                    float dx = ev.getRawX(dragPointerIndex) - mInputDownPoint.x;
                    float dy = ev.getRawY(dragPointerIndex) - mInputDownPoint.y;
                    if (Math.hypot(dx, dy) > mTouchSlop) {
                        mIsDragEvent = true;
                    }
                }
                return mIsDragEvent;
            }
            case ACTION_UP: {
                boolean result = mIsDragEvent;
                mIsDragEvent = false;
                mInputDownPoint.set(0, 0);
                mDragPointerId = -1;
                return result;
            }
            case ACTION_CANCEL: {
                mIsDragEvent = false;
                mInputDownPoint.set(0, 0);
                mDragPointerId = -1;
                return false;
            }
        }
        return mIsDragEvent;
    }

    public void setTouchSlop(int touchSlop) {
        mTouchSlop = touchSlop;
    }
}
