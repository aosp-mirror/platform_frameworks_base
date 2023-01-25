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

import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;

import android.graphics.PointF;
import android.view.MotionEvent;

/**
 * A detector for touch inputs that differentiates between drag and click inputs. It receives a flow
 * of {@link MotionEvent} and generates a new flow of motion events with slop in consideration to
 * the event handler. In particular, it always passes down, up and cancel events. It'll pass move
 * events only when there is at least one move event that's beyond the slop threshold. For the
 * purpose of convenience it also passes all events of other actions.
 *
 * All touch events must be passed through this class to track a drag event.
 */
class DragDetector {
    private final MotionEventHandler mEventHandler;

    private final PointF mInputDownPoint = new PointF();
    private int mTouchSlop;
    private boolean mIsDragEvent;
    private int mDragPointerId;

    private boolean mResultOfDownAction;

    DragDetector(MotionEventHandler eventHandler) {
        resetState();
        mEventHandler = eventHandler;
    }

    /**
     * The receiver of the {@link MotionEvent} flow.
     *
     * @return the result returned by {@link #mEventHandler}, or the result when
     * {@link #mEventHandler} handles the previous down event if the event shouldn't be passed
    */
    boolean onMotionEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case ACTION_DOWN: {
                // Only touch screens generate noisy moves.
                mIsDragEvent = (ev.getSource() & SOURCE_TOUCHSCREEN) != SOURCE_TOUCHSCREEN;
                mDragPointerId = ev.getPointerId(0);
                float rawX = ev.getRawX(0);
                float rawY = ev.getRawY(0);
                mInputDownPoint.set(rawX, rawY);
                mResultOfDownAction = mEventHandler.handleMotionEvent(ev);
                return mResultOfDownAction;
            }
            case ACTION_MOVE: {
                if (!mIsDragEvent) {
                    int dragPointerIndex = ev.findPointerIndex(mDragPointerId);
                    float dx = ev.getRawX(dragPointerIndex) - mInputDownPoint.x;
                    float dy = ev.getRawY(dragPointerIndex) - mInputDownPoint.y;
                    mIsDragEvent = Math.hypot(dx, dy) > mTouchSlop;
                }
                if (mIsDragEvent) {
                    return mEventHandler.handleMotionEvent(ev);
                } else {
                    return mResultOfDownAction;
                }
            }
            case ACTION_UP:
            case ACTION_CANCEL: {
                resetState();
                return mEventHandler.handleMotionEvent(ev);
            }
            default:
                return mEventHandler.handleMotionEvent(ev);
        }
    }

    void setTouchSlop(int touchSlop) {
        mTouchSlop = touchSlop;
    }

    private void resetState() {
        mIsDragEvent = false;
        mInputDownPoint.set(0, 0);
        mDragPointerId = -1;
        mResultOfDownAction = false;
    }

    interface MotionEventHandler {
        boolean handleMotionEvent(MotionEvent ev);
    }
}
