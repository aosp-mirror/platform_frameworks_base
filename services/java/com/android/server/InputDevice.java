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

    /** Maximum number of pointers we will track and report. */
    static final int MAX_POINTERS = 2;
    
    final int id;
    final int classes;
    final String name;
    final AbsoluteInfo absX;
    final AbsoluteInfo absY;
    final AbsoluteInfo absPressure;
    final AbsoluteInfo absSize;
    
    long mKeyDownTime = 0;
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
        boolean mLastAnyDown = false;
        long mDownTime = 0;
        final boolean[] mLastDown = new boolean[MAX_POINTERS];
        final boolean[] mDown = new boolean[MAX_POINTERS];
        final int[] mLastData = new int[MotionEvent.NUM_SAMPLE_DATA * MAX_POINTERS];
        final int[] mCurData = new int[MotionEvent.NUM_SAMPLE_DATA * MAX_POINTERS];
        final float[] mReportData = new float[MotionEvent.NUM_SAMPLE_DATA * MAX_POINTERS];
        
        MotionState(int mx, int my) {
            xPrecision = mx;
            yPrecision = my;
            xMoveScale = mx != 0 ? (1.0f/mx) : 1.0f;
            yMoveScale = my != 0 ? (1.0f/my) : 1.0f;
        }
        
        MotionEvent generateAbsMotion(InputDevice device, long curTime,
                long curTimeNano, Display display, int orientation,
                int metaState) {
            
            final float[] scaled = mReportData;
            final int[] cur = mCurData;
            
            boolean anyDown = false;
            int firstDownChanged = -1;
            int numPointers = 0;
            for (int i=0; i<MAX_POINTERS; i++) {
                boolean d = mDown[i];
                anyDown |= d;
                if (d != mLastDown[i] && firstDownChanged < 0) {
                    firstDownChanged = i;
                    mLastDown[i] = mDown[i];
                    d = true;
                }
                
                if (d) {
                    final int src = i * MotionEvent.NUM_SAMPLE_DATA;
                    final int dest = numPointers * MotionEvent.NUM_SAMPLE_DATA;
                    numPointers++;
                    scaled[dest + MotionEvent.SAMPLE_X] = cur[src + MotionEvent.SAMPLE_X];
                    scaled[dest + MotionEvent.SAMPLE_Y] = cur[src + MotionEvent.SAMPLE_Y];
                    scaled[dest + MotionEvent.SAMPLE_PRESSURE] = cur[src + MotionEvent.SAMPLE_PRESSURE];
                    scaled[dest + MotionEvent.SAMPLE_SIZE] = cur[src + MotionEvent.SAMPLE_SIZE];
                }
            }
            
            if (numPointers <= 0) {
                return null;
            }
            
            int action;
            int edgeFlags = 0;
            if (anyDown != mLastAnyDown) {
                final AbsoluteInfo absX = device.absX;
                final AbsoluteInfo absY = device.absY;
                if (anyDown && absX != null && absY != null) {
                    // We don't let downs start unless we are
                    // inside of the screen.  There are two reasons for
                    // this: to avoid spurious touches when holding
                    // the edges of the device near the touchscreen,
                    // and to avoid reporting events if there are virtual
                    // keys on the touchscreen outside of the display
                    // area.
                    // Note that we are only looking at the first pointer,
                    // since what we are handling here is the first pointer
                    // going down, and this is the coordinate that will be
                    // used to dispatch the event.
                    if (cur[MotionEvent.SAMPLE_X] < absX.minValue
                            || cur[MotionEvent.SAMPLE_X] > absX.maxValue
                            || cur[MotionEvent.SAMPLE_Y] < absY.minValue
                            || cur[MotionEvent.SAMPLE_Y] > absY.maxValue) {
                        if (false) Log.v("InputDevice", "Rejecting ("
                                + cur[MotionEvent.SAMPLE_X] + ","
                                + cur[MotionEvent.SAMPLE_Y] + "): outside of ("
                                + absX.minValue + "," + absY.minValue
                                + ")-(" + absX.maxValue + ","
                                + absY.maxValue + ")");
                        return null;
                    }
                }
                mLastAnyDown = anyDown;
                if (anyDown) {
                    action = MotionEvent.ACTION_DOWN;
                    mDownTime = curTime;
                } else {
                    action = MotionEvent.ACTION_UP;
                }
                currentMove = null;
            } else if (firstDownChanged >= 0) {
                if (mDown[firstDownChanged]) {
                    action = MotionEvent.ACTION_POINTER_DOWN
                            | (firstDownChanged << MotionEvent.ACTION_POINTER_SHIFT);
                } else {
                    action = MotionEvent.ACTION_POINTER_UP
                            | (firstDownChanged << MotionEvent.ACTION_POINTER_SHIFT);
                }
                currentMove = null;
            } else {
                action = MotionEvent.ACTION_MOVE;
            }
            
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
            
            final AbsoluteInfo absX = device.absX;
            final AbsoluteInfo absY = device.absY;
            final AbsoluteInfo absPressure = device.absPressure;
            final AbsoluteInfo absSize = device.absSize;
            for (int i=0; i<numPointers; i++) {
                final int j = i * MotionEvent.NUM_SAMPLE_DATA;
            
                if (absX != null) {
                    scaled[j + MotionEvent.SAMPLE_X] =
                            ((scaled[j + MotionEvent.SAMPLE_X]-absX.minValue)
                                / absX.range) * w;
                }
                if (absY != null) {
                    scaled[j + MotionEvent.SAMPLE_Y] =
                            ((scaled[j + MotionEvent.SAMPLE_Y]-absY.minValue)
                                / absY.range) * h;
                }
                if (absPressure != null) {
                    scaled[j + MotionEvent.SAMPLE_PRESSURE] = 
                            ((scaled[j + MotionEvent.SAMPLE_PRESSURE]-absPressure.minValue)
                                / (float)absPressure.range);
                }
                if (absSize != null) {
                    scaled[j + MotionEvent.SAMPLE_SIZE] = 
                            ((scaled[j + MotionEvent.SAMPLE_SIZE]-absSize.minValue)
                                / (float)absSize.range);
                }
                
                switch (orientation) {
                    case Surface.ROTATION_90: {
                        final float temp = scaled[MotionEvent.SAMPLE_X];
                        scaled[j + MotionEvent.SAMPLE_X] = scaled[j + MotionEvent.SAMPLE_Y];
                        scaled[j + MotionEvent.SAMPLE_Y] = w-temp;
                        break;
                    }
                    case Surface.ROTATION_180: {
                        scaled[j + MotionEvent.SAMPLE_X] = w-scaled[j + MotionEvent.SAMPLE_X];
                        scaled[j + MotionEvent.SAMPLE_Y] = h-scaled[j + MotionEvent.SAMPLE_Y];
                        break;
                    }
                    case Surface.ROTATION_270: {
                        final float temp = scaled[i + MotionEvent.SAMPLE_X];
                        scaled[j + MotionEvent.SAMPLE_X] = h-scaled[j + MotionEvent.SAMPLE_Y];
                        scaled[j + MotionEvent.SAMPLE_Y] = temp;
                        break;
                    }
                }
            }
            
            // We only consider the first pointer when computing the edge
            // flags, since they are global to the event.
            if (action != MotionEvent.ACTION_DOWN) {
                if (scaled[MotionEvent.SAMPLE_X] <= 0) {
                    edgeFlags |= MotionEvent.EDGE_LEFT;
                } else if (scaled[MotionEvent.SAMPLE_X] >= dispW) {
                    edgeFlags |= MotionEvent.EDGE_RIGHT;
                }
                if (scaled[MotionEvent.SAMPLE_Y] <= 0) {
                    edgeFlags |= MotionEvent.EDGE_TOP;
                } else if (scaled[MotionEvent.SAMPLE_Y] >= dispH) {
                    edgeFlags |= MotionEvent.EDGE_BOTTOM;
                }
            }
            
            if (currentMove != null) {
                if (false) Log.i("InputDevice", "Adding batch x="
                        + scaled[MotionEvent.SAMPLE_X]
                        + " y=" + scaled[MotionEvent.SAMPLE_Y]
                        + " to " + currentMove);
                currentMove.addBatch(curTime, scaled, metaState);
                if (WindowManagerPolicy.WATCH_POINTER) {
                    Log.i("KeyInputQueue", "Updating: " + currentMove);
                }
                return null;
            }
            
            MotionEvent me = MotionEvent.obtainNano(mDownTime, curTime,
                    curTimeNano, action, numPointers, scaled, metaState,
                    xPrecision, yPrecision, device.id, edgeFlags);
            if (action == MotionEvent.ACTION_MOVE) {
                currentMove = me;
            }
            return me;
        }
        
        MotionEvent generateRelMotion(InputDevice device, long curTime,
                long curTimeNano, int orientation, int metaState) {
            
            final float[] scaled = mReportData;
            
            // For now we only support 1 pointer with relative motions.
            scaled[MotionEvent.SAMPLE_X] = mCurData[MotionEvent.SAMPLE_X];
            scaled[MotionEvent.SAMPLE_Y] = mCurData[MotionEvent.SAMPLE_Y];
            scaled[MotionEvent.SAMPLE_PRESSURE] = 1.0f;
            scaled[MotionEvent.SAMPLE_SIZE] = 0;
            int edgeFlags = 0;
            
            int action;
            if (mDown[0] != mLastDown[0]) {
                mCurData[MotionEvent.SAMPLE_X] =
                        mCurData[MotionEvent.SAMPLE_Y] = 0;
                mLastDown[0] = mDown[0];
                if (mDown[0]) {
                    action = MotionEvent.ACTION_DOWN;
                    mDownTime = curTime;
                } else {
                    action = MotionEvent.ACTION_UP;
                }
                currentMove = null;
            } else {
                action = MotionEvent.ACTION_MOVE;
            }
            
            scaled[MotionEvent.SAMPLE_X] *= xMoveScale;
            scaled[MotionEvent.SAMPLE_Y] *= yMoveScale;
            switch (orientation) {
                case Surface.ROTATION_90: {
                    final float temp = scaled[MotionEvent.SAMPLE_X];
                    scaled[MotionEvent.SAMPLE_X] = scaled[MotionEvent.SAMPLE_Y];
                    scaled[MotionEvent.SAMPLE_Y] = -temp;
                    break;
                }
                case Surface.ROTATION_180: {
                    scaled[MotionEvent.SAMPLE_X] = -scaled[MotionEvent.SAMPLE_X];
                    scaled[MotionEvent.SAMPLE_Y] = -scaled[MotionEvent.SAMPLE_Y];
                    break;
                }
                case Surface.ROTATION_270: {
                    final float temp = scaled[MotionEvent.SAMPLE_X];
                    scaled[MotionEvent.SAMPLE_X] = -scaled[MotionEvent.SAMPLE_Y];
                    scaled[MotionEvent.SAMPLE_Y] = temp;
                    break;
                }
            }
            
            if (currentMove != null) {
                if (false) Log.i("InputDevice", "Adding batch x="
                        + scaled[MotionEvent.SAMPLE_X]
                        + " y=" + scaled[MotionEvent.SAMPLE_Y]
                        + " to " + currentMove);
                currentMove.addBatch(curTime, scaled, metaState);
                if (WindowManagerPolicy.WATCH_POINTER) {
                    Log.i("KeyInputQueue", "Updating: " + currentMove);
                }
                return null;
            }
            
            MotionEvent me = MotionEvent.obtainNano(mDownTime, curTime,
                    curTimeNano, action, 1, scaled, metaState,
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
