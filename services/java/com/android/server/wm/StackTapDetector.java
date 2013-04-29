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
import android.os.Looper;
import android.view.DisplayInfo;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;

import com.android.server.wm.WindowManagerService.H;

public class StackTapDetector extends InputEventReceiver {
    private static final int TAP_TIMEOUT_MSEC = 300;
    private static final float TAP_MOTION_SLOP_INCHES = 0.125f;

    private final int mMotionSlop;
    private float mDownX;
    private float mDownY;
    private int mPointerId;
    final private Region mTouchExcludeRegion;
    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;

    public StackTapDetector(WindowManagerService service, DisplayContent displayContent,
            InputChannel inputChannel, Looper looper) {
        super(inputChannel, looper);
        mService = service;
        mDisplayContent = displayContent;
        mTouchExcludeRegion = displayContent.mTouchExcludeRegion;
        DisplayInfo info = displayContent.getDisplayInfo();
        mMotionSlop = (int)(info.logicalDensityDpi * TAP_MOTION_SLOP_INCHES);
    }

    @Override
    public void onInputEvent(InputEvent event) {
        if (!(event instanceof MotionEvent)
                || !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            return;
        }
        final MotionEvent motionEvent = (MotionEvent)event;
        final int action = motionEvent.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                mPointerId = motionEvent.getPointerId(0);
                mDownX = motionEvent.getX();
                mDownY = motionEvent.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (mPointerId >= 0) {
                    int index = motionEvent.findPointerIndex(mPointerId);
                    if ((motionEvent.getEventTime() - motionEvent.getDownTime()) > TAP_TIMEOUT_MSEC
                            || (motionEvent.getX(index) - mDownX) > mMotionSlop
                            || (motionEvent.getY(index) - mDownY) > mMotionSlop) {
                        mPointerId = -1;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = (action & MotionEvent.ACTION_POINTER_INDEX_MASK)
                        >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                // Extract the index of the pointer that left the touch sensor
                if (mPointerId == motionEvent.getPointerId(index)) {
                    final int x = (int)motionEvent.getX(index);
                    final int y = (int)motionEvent.getY(index);
                    synchronized (this) {
                        if ((motionEvent.getEventTime() - motionEvent.getDownTime())
                                < TAP_TIMEOUT_MSEC
                                && (x - mDownX) < mMotionSlop && (y - mDownY) < mMotionSlop
                                && !mTouchExcludeRegion.contains(x, y)) {
                            mService.mH.obtainMessage(H.TAP_OUTSIDE_STACK, x, y, mDisplayContent)
                                    .sendToTarget();
                        }
                    }
                    mPointerId = -1;
                }
                break;
            }
        }
    }
}
