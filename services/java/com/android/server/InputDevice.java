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
        
        MotionEvent generateMotion(InputDevice device, long curTime, long curTimeNano,
                boolean isAbs, Display display, int orientation,
                int metaState) {
            float scaledX = x;
            float scaledY = y;
            float temp;
            float scaledPressure = 1.0f;
            float scaledSize = 0;
            int edgeFlags = 0;
            
            int action;
            if (down != lastDown) {
                if (isAbs) {
                    final AbsoluteInfo absX = device.absX;
                    final AbsoluteInfo absY = device.absY;
                    if (down && absX != null && absY != null) {
                        // We don't let downs start unless we are
                        // inside of the screen.  There are two reasons for
                        // this: to avoid spurious touches when holding
                        // the edges of the device near the touchscreen,
                        // and to avoid reporting events if there are virtual
                        // keys on the touchscreen outside of the display
                        // area.
                        if (scaledX < absX.minValue || scaledX > absX.maxValue
                                || scaledY < absY.minValue || scaledY > absY.maxValue) {
                            if (false) Log.v("InputDevice", "Rejecting (" + scaledX + ","
                                    + scaledY + "): outside of ("
                                    + absX.minValue + "," + absY.minValue
                                    + ")-(" + absX.maxValue + ","
                                    + absY.maxValue + ")");
                            return null;
                        }
                    }
                } else {
                    x = y = 0;
                }
                lastDown = down;
                if (down) {
                    action = MotionEvent.ACTION_DOWN;
                    downTime = curTime;
                } else {
                    action = MotionEvent.ACTION_UP;
                }
                currentMove = null;
            } else {
                action = MotionEvent.ACTION_MOVE;
            }
            
            if (isAbs) {
                final int dispW = display.getWidth()-1;
                final int dispH = display.getHeight()-1;
                int w = dispW;
                int h = dispH;
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

                if (action != MotionEvent.ACTION_DOWN) {
                    if (scaledX <= 0) {
                        edgeFlags += MotionEvent.EDGE_LEFT;
                    } else if (scaledX >= dispW) {
                        edgeFlags += MotionEvent.EDGE_RIGHT;
                    }
                    if (scaledY <= 0) {
                        edgeFlags += MotionEvent.EDGE_TOP;
                    } else if (scaledY >= dispH) {
                        edgeFlags += MotionEvent.EDGE_BOTTOM;
                    }
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
            
            MotionEvent me = MotionEvent.obtainNano(downTime, curTime,
                    curTimeNano, action, scaledX, scaledY,
                    scaledPressure, scaledSize, metaState,
                    xPrecision, yPrecision, device.id, edgeFlags);
            if (action == MotionEvent.ACTION_MOVE) {
                currentMove = me;
            }
            return me;
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
