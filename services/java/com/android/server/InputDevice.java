/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManagerPolicy;

public class InputDevice {
    /** Amount that trackball needs to move in order to generate a key event. */
    static final int TRACKBALL_MOVEMENT_THRESHOLD = 6;

    final int id;
    final int classes;
    final String name;
    final AbsoluteInfo absX;
    final AbsoluteInfo absY;
    final AbsoluteInfo absPressure;
    final AbsoluteInfo absSize;
    
    long mDownTime = 0;
    int mMetaKeysState = 0;
    
    final MotionState mAbs = new MotionState(0, 0);
    final MotionState mRel = new MotionState(TRACKBALL_MOVEMENT_THRESHOLD,
            TRACKBALL_MOVEMENT_THRESHOLD);
    
    static class MotionState {
        int xPrecision;
        int yPrecision;
        float xMoveScale;
        float yMoveScale;
        MotionEvent currentMove = null;
        boolean changed = false;
        boolean down = false;
        boolean lastDown = false;
        long downTime = 0;
        int x = 0;
        int y = 0;
        int pressure = 1;
        int size = 0;
        
        MotionState(int mx, int my) {
            xPrecision = mx;
            yPrecision = my;
            xMoveScale = mx != 0 ? (1.0f/mx) : 1.0f;
            yMoveScale = my != 0 ? (1.0f/my) : 1.0f;
        }
        
        MotionEvent generateMotion(InputDevice device, long curTime,
                boolean isAbs, Display display, int orientation,
                int metaState) {
            if (!changed) {
                return null;
            }
            
            float scaledX = x;
            float scaledY = y;
            float temp;
            float scaledPressure = 1.0f;
            float scaledSize = 0;
            int edgeFlags = 0;
            if (isAbs) {
                int w = display.getWidth()-1;
                int h = display.getHeight()-1;
                if (orientation == Surface.ROTATION_90
                        || orientation == Surface.ROTATION_270) {
                    int tmp = w;
                    w = h;
                    h = tmp;
                }
                if (device.absX != null) {
                    scaledX = ((scaledX-device.absX.minValue)
                                / device.absX.range) * w;
                }
                if (device.absY != null) {
                    scaledY = ((scaledY-device.absY.minValue)
                                / device.absY.range) * h;
                }
                if (device.absPressure != null) {
                    scaledPressure = 
                        ((pressure-device.absPressure.minValue)
                                / (float)device.absPressure.range);
                }
                if (device.absSize != null) {
                    scaledSize = 
                        ((size-device.absSize.minValue)
                                / (float)device.absSize.range);
                }
                switch (orientation) {
                    case Surface.ROTATION_90:
                        temp = scaledX;
                        scaledX = scaledY;
                        scaledY = w-temp;
                        break;
                    case Surface.ROTATION_180:
                        scaledX = w-scaledX;
                        scaledY = h-scaledY;
                        break;
                    case Surface.ROTATION_270:
                        temp = scaledX;
                        scaledX = h-scaledY;
                        scaledY = temp;
                        break;
                }

                if (scaledX == 0) {
                    edgeFlags += MotionEvent.EDGE_LEFT;
                } else if (scaledX == display.getWidth() - 1.0f) {
                    edgeFlags += MotionEvent.EDGE_RIGHT;
                }
                
                if (scaledY == 0) {
                    edgeFlags += MotionEvent.EDGE_TOP;
                } else if (scaledY == display.getHeight() - 1.0f) {
                    edgeFlags += MotionEvent.EDGE_BOTTOM;
                }
                
            } else {
                scaledX *= xMoveScale;
                scaledY *= yMoveScale;
                switch (orientation) {
                    case Surface.ROTATION_90:
                        temp = scaledX;
                        scaledX = scaledY;
                        scaledY = -temp;
                        break;
                    case Surface.ROTATION_180:
                        scaledX = -scaledX;
                        scaledY = -scaledY;
                        break;
                    case Surface.ROTATION_270:
                        temp = scaledX;
                        scaledX = -scaledY;
                        scaledY = temp;
                        break;
                }
            }
            
            changed = false;
            if (down != lastDown) {
                int action;
                lastDown = down;
                if (down) {
                    action = MotionEvent.ACTION_DOWN;
                    downTime = curTime;
                } else {
                    action = MotionEvent.ACTION_UP;
                }
                currentMove = null;
                if (!isAbs) {
                    x = y = 0;
                }
                return MotionEvent.obtain(downTime, curTime, action,
                        scaledX, scaledY, scaledPressure, scaledSize, metaState,
                        xPrecision, yPrecision, device.id, edgeFlags);
            } else {
                if (currentMove != null) {
                    if (false) Log.i("InputDevice", "Adding batch x=" + scaledX
                            + " y=" + scaledY + " to " + currentMove);
                    currentMove.addBatch(curTime, scaledX, scaledY,
                            scaledPressure, scaledSize, metaState);
                    if (WindowManagerPolicy.WATCH_POINTER) {
                        Log.i("KeyInputQueue", "Updating: " + currentMove);
                    }
                    return null;
                }
                MotionEvent me = MotionEvent.obtain(downTime, curTime,
                        MotionEvent.ACTION_MOVE, scaledX, scaledY,
                        scaledPressure, scaledSize, metaState,
                        xPrecision, yPrecision, device.id, edgeFlags);
                currentMove = me;
                return me;
            }
        }
    }
    
    static class AbsoluteInfo {
        int minValue;
        int maxValue;
        int range;
        int flat;
        int fuzz;
    };
    
    InputDevice(int _id, int _classes, String _name,
            AbsoluteInfo _absX, AbsoluteInfo _absY,
            AbsoluteInfo _absPressure, AbsoluteInfo _absSize) {
        id = _id;
        classes = _classes;
        name = _name;
        absX = _absX;
        absY = _absY;
        absPressure = _absPressure;
        absSize = _absSize;
    }
};
