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
    static final boolean DEBUG_POINTERS = false;
    
    /** Amount that trackball needs to move in order to generate a key event. */
    static final int TRACKBALL_MOVEMENT_THRESHOLD = 6;

    /** Maximum number of pointers we will track and report. */
    static final int MAX_POINTERS = 10;
    
    final int id;
    final int classes;
    final String name;
    final AbsoluteInfo absX;
    final AbsoluteInfo absY;
    final AbsoluteInfo absPressure;
    final AbsoluteInfo absSize;
    
    long mKeyDownTime = 0;
    int mMetaKeysState = 0;
    
    // For use by KeyInputQueue for keeping track of the current touch
    // data in the old non-multi-touch protocol.
    final int[] curTouchVals = new int[MotionEvent.NUM_SAMPLE_DATA * 2];
    
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
        long mDownTime = 0;
        
        // The currently assigned pointer IDs, corresponding to the last data.
        int[] mPointerIds = new int[MAX_POINTERS];
        
        // This is the last generated pointer data, ordered to match
        // mPointerIds.
        int mLastNumPointers = 0;
        final int[] mLastData = new int[MotionEvent.NUM_SAMPLE_DATA * MAX_POINTERS];
        
        // This is the next set of pointer data being generated.  It is not
        // in any known order, and will be propagated in to mLastData
        // as part of mapping it to the appropriate pointer IDs.
        // Note that we have one extra sample of data here, to help clients
        // avoid doing bounds checking.
        int mNextNumPointers = 0;
        final int[] mNextData = new int[(MotionEvent.NUM_SAMPLE_DATA * MAX_POINTERS)
                                        + MotionEvent.NUM_SAMPLE_DATA];
        
        // Temporary data structures for doing the pointer ID mapping.
        final int[] mLast2Next = new int[MAX_POINTERS];
        final int[] mNext2Last = new int[MAX_POINTERS];
        final long[] mNext2LastDistance = new long[MAX_POINTERS];
        
        // Temporary data structure for generating the final motion data.
        final float[] mReportData = new float[MotionEvent.NUM_SAMPLE_DATA * MAX_POINTERS];
        
        // This is not used here, but can be used by callers for state tracking.
        int mAddingPointerOffset = 0;
        final boolean[] mDown = new boolean[MAX_POINTERS];
        
        MotionState(int mx, int my) {
            xPrecision = mx;
            yPrecision = my;
            xMoveScale = mx != 0 ? (1.0f/mx) : 1.0f;
            yMoveScale = my != 0 ? (1.0f/my) : 1.0f;
            for (int i=0; i<MAX_POINTERS; i++) {
                mPointerIds[i] = i;
            }
        }
        
        private boolean assignPointer(int nextIndex, boolean allowOverlap) {
            final int lastNumPointers = mLastNumPointers;
            final int[] next2Last = mNext2Last;
            final long[] next2LastDistance = mNext2LastDistance;
            final int[] last2Next = mLast2Next;
            final int[] lastData = mLastData;
            final int[] nextData = mNextData;
            final int id = nextIndex * MotionEvent.NUM_SAMPLE_DATA;
            
            if (DEBUG_POINTERS) Log.v("InputDevice", "assignPointer: nextIndex="
                    + nextIndex + " dataOff=" + id);
            final int x1 = nextData[id + MotionEvent.SAMPLE_X];
            final int y1 = nextData[id + MotionEvent.SAMPLE_Y];
            
            long bestDistance = -1;
            int bestIndex = -1;
            for (int j=0; j<lastNumPointers; j++) {
                if (!allowOverlap && last2Next[j] < 0) {
                    continue;
                }
                final int jd = j * MotionEvent.NUM_SAMPLE_DATA;
                final int xd = lastData[jd + MotionEvent.SAMPLE_X] - x1;
                final int yd = lastData[jd + MotionEvent.SAMPLE_Y] - y1;
                final long distance = xd*(long)xd + yd*(long)yd;
                if (j == 0 || distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = j;
                }
            }
            
            if (DEBUG_POINTERS) Log.v("InputDevice", "New index " + nextIndex
                    + " best old index=" + bestIndex + " (distance="
                    + bestDistance + ")");
            next2Last[nextIndex] = bestIndex;
            next2LastDistance[nextIndex] = bestDistance;
            
            if (bestIndex < 0) {
                return true;
            }
            
            if (last2Next[bestIndex] == -1) {
                last2Next[bestIndex] = nextIndex;
                return false;
            }
            
            if (DEBUG_POINTERS) Log.v("InputDevice", "Old index " + bestIndex
                    + " has multiple best new pointers!");
            
            last2Next[bestIndex] = -2;
            return true;
        }
        
        private int updatePointerIdentifiers() {
            final int[] lastData = mLastData;
            final int[] nextData = mNextData;
            final int nextNumPointers = mNextNumPointers;
            final int lastNumPointers = mLastNumPointers;
            
            if (nextNumPointers == 1 && lastNumPointers == 1) {
                System.arraycopy(nextData, 0, lastData, 0,
                        MotionEvent.NUM_SAMPLE_DATA);
                return -1;
            }
            
            // Clear our old state.
            final int[] last2Next = mLast2Next;
            for (int i=0; i<lastNumPointers; i++) {
                last2Next[i] = -1;
            }
            
            if (DEBUG_POINTERS) Log.v("InputDevice",
                    "Update pointers: lastNumPointers=" + lastNumPointers
                    + " nextNumPointers=" + nextNumPointers);
            
            // Figure out the closes new points to the previous points.
            final int[] next2Last = mNext2Last;
            final long[] next2LastDistance = mNext2LastDistance;
            boolean conflicts = false;
            for (int i=0; i<nextNumPointers; i++) {
                conflicts |= assignPointer(i, true);
            }
            
            // Resolve ambiguities in pointer mappings, when two or more
            // new pointer locations find their best previous location is
            // the same.
            if (conflicts) {
                if (DEBUG_POINTERS) Log.v("InputDevice", "Resolving conflicts");
                
                for (int i=0; i<lastNumPointers; i++) {
                    if (last2Next[i] != -2) {
                        continue;
                    }
                    
                    // Note that this algorithm is far from perfect.  Ideally
                    // we should do something like the one described at
                    // http://portal.acm.org/citation.cfm?id=997856
                    
                    if (DEBUG_POINTERS) Log.v("InputDevice",
                            "Resolving last index #" + i);
                    
                    int numFound;
                    do {
                        numFound = 0;
                        long worstDistance = 0;
                        int worstJ = -1;
                        for (int j=0; j<nextNumPointers; j++) {
                            if (next2Last[j] != i) {
                                continue;
                            }
                            numFound++;
                            if (worstDistance < next2LastDistance[j]) {
                                worstDistance = next2LastDistance[j];
                                worstJ = j;
                            }
                        }
                        
                        if (worstJ >= 0) {
                            if (DEBUG_POINTERS) Log.v("InputDevice",
                                    "Worst new pointer: " + worstJ
                                    + " (distance=" + worstDistance + ")");
                            if (assignPointer(worstJ, false)) {
                                // In this case there is no last pointer
                                // remaining for this new one!
                                next2Last[worstJ] = -1;
                            }
                        }
                    } while (numFound > 2);
                }
            }
            
            int retIndex = -1;
            
            if (lastNumPointers < nextNumPointers) {
                // We have one or more new pointers that are down.  Create a
                // new pointer identifier for one of them.
                if (DEBUG_POINTERS) Log.v("InputDevice", "Adding new pointer");
                int nextId = 0;
                int i=0;
                while (i < lastNumPointers) {
                    if (mPointerIds[i] > nextId) {
                        // Found a hole, insert the pointer here.
                        if (DEBUG_POINTERS) Log.v("InputDevice",
                                "Inserting new pointer at hole " + i);
                        System.arraycopy(mPointerIds, i, mPointerIds,
                                i+1, lastNumPointers-i);
                        System.arraycopy(lastData, i*MotionEvent.NUM_SAMPLE_DATA,
                                lastData, (i+1)*MotionEvent.NUM_SAMPLE_DATA,
                                (lastNumPointers-i)*MotionEvent.NUM_SAMPLE_DATA);
                        break;
                    }
                    i++;
                    nextId++;
                }
                
                if (DEBUG_POINTERS) Log.v("InputDevice",
                        "New pointer id " + nextId + " at index " + i);
                
                mLastNumPointers++;
                retIndex = i;
                mPointerIds[i] = nextId;
                
                // And assign this identifier to the first new pointer.
                for (int j=0; j<nextNumPointers; j++) {
                    if (next2Last[j] < 0) {
                        if (DEBUG_POINTERS) Log.v("InputDevice",
                                "Assigning new id to new pointer index " + j);
                        next2Last[j] = i;
                        break;
                    }
                }
            }
            
            // Propagate all of the current data into the appropriate
            // location in the old data to match the pointer ID that was
            // assigned to it.
            for (int i=0; i<nextNumPointers; i++) {
                int lastIndex = next2Last[i];
                if (lastIndex >= 0) {
                    if (DEBUG_POINTERS) Log.v("InputDevice",
                            "Copying next pointer index " + i
                            + " to last index " + lastIndex);
                    System.arraycopy(nextData, i*MotionEvent.NUM_SAMPLE_DATA,
                            lastData, lastIndex*MotionEvent.NUM_SAMPLE_DATA,
                            MotionEvent.NUM_SAMPLE_DATA);
                }
            }
            
            if (lastNumPointers > nextNumPointers) {
                // One or more pointers has gone up.  Find the first one,
                // and adjust accordingly.
                if (DEBUG_POINTERS) Log.v("InputDevice", "Removing old pointer");
                for (int i=0; i<lastNumPointers; i++) {
                    if (last2Next[i] == -1) {
                        if (DEBUG_POINTERS) Log.v("InputDevice",
                                "Removing old pointer at index " + i);
                        retIndex = i;
                        break;
                    }
                }
            }
            
            return retIndex;
        }
        
        void removeOldPointer(int index) {
            final int lastNumPointers = mLastNumPointers;
            if (index >= 0 && index < lastNumPointers) {
                System.arraycopy(mPointerIds, index+1, mPointerIds,
                        index, lastNumPointers-index-1);
                System.arraycopy(mLastData, (index+1)*MotionEvent.NUM_SAMPLE_DATA,
                        mLastData, (index)*MotionEvent.NUM_SAMPLE_DATA,
                        (lastNumPointers-index-1)*MotionEvent.NUM_SAMPLE_DATA);
                mLastNumPointers--;
            }
        }
        
        MotionEvent generateAbsMotion(InputDevice device, long curTime,
                long curTimeNano, Display display, int orientation,
                int metaState) {
            
            if (mNextNumPointers <= 0 && mLastNumPointers <= 0) {
                return null;
            }
            
            final int lastNumPointers = mLastNumPointers;
            final int nextNumPointers = mNextNumPointers;
            if (mNextNumPointers > MAX_POINTERS) {
                Log.w("InputDevice", "Number of pointers " + mNextNumPointers
                        + " exceeded maximum of " + MAX_POINTERS);
                mNextNumPointers = MAX_POINTERS;
            }
            
            int upOrDownPointer = updatePointerIdentifiers();
            
            final float[] reportData = mReportData;
            final int[] rawData = mLastData;
            
            final int numPointers = mLastNumPointers;
            
            if (DEBUG_POINTERS) Log.v("InputDevice", "Processing "
                    + numPointers + " pointers (going from " + lastNumPointers
                    + " to " + nextNumPointers + ")");
            
            for (int i=0; i<numPointers; i++) {
                final int pos = i * MotionEvent.NUM_SAMPLE_DATA;
                reportData[pos + MotionEvent.SAMPLE_X] = rawData[pos + MotionEvent.SAMPLE_X];
                reportData[pos + MotionEvent.SAMPLE_Y] = rawData[pos + MotionEvent.SAMPLE_Y];
                reportData[pos + MotionEvent.SAMPLE_PRESSURE] = rawData[pos + MotionEvent.SAMPLE_PRESSURE];
                reportData[pos + MotionEvent.SAMPLE_SIZE] = rawData[pos + MotionEvent.SAMPLE_SIZE];
            }
            
            int action;
            int edgeFlags = 0;
            if (nextNumPointers != lastNumPointers) {
                if (nextNumPointers > lastNumPointers) {
                    if (lastNumPointers == 0) {
                        action = MotionEvent.ACTION_DOWN;
                        mDownTime = curTime;
                    } else {
                        action = MotionEvent.ACTION_POINTER_DOWN
                                | (upOrDownPointer << MotionEvent.ACTION_POINTER_ID_SHIFT);
                    }
                } else {
                    if (numPointers == 1) {
                        action = MotionEvent.ACTION_UP;
                    } else {
                        action = MotionEvent.ACTION_POINTER_UP
                                | (upOrDownPointer << MotionEvent.ACTION_POINTER_ID_SHIFT);
                    }
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
                    reportData[j + MotionEvent.SAMPLE_X] =
                            ((reportData[j + MotionEvent.SAMPLE_X]-absX.minValue)
                                / absX.range) * w;
                }
                if (absY != null) {
                    reportData[j + MotionEvent.SAMPLE_Y] =
                            ((reportData[j + MotionEvent.SAMPLE_Y]-absY.minValue)
                                / absY.range) * h;
                }
                if (absPressure != null) {
                    reportData[j + MotionEvent.SAMPLE_PRESSURE] = 
                            ((reportData[j + MotionEvent.SAMPLE_PRESSURE]-absPressure.minValue)
                                / (float)absPressure.range);
                }
                if (absSize != null) {
                    reportData[j + MotionEvent.SAMPLE_SIZE] = 
                            ((reportData[j + MotionEvent.SAMPLE_SIZE]-absSize.minValue)
                                / (float)absSize.range);
                }
                
                switch (orientation) {
                    case Surface.ROTATION_90: {
                        final float temp = reportData[j + MotionEvent.SAMPLE_X];
                        reportData[j + MotionEvent.SAMPLE_X] = reportData[j + MotionEvent.SAMPLE_Y];
                        reportData[j + MotionEvent.SAMPLE_Y] = w-temp;
                        break;
                    }
                    case Surface.ROTATION_180: {
                        reportData[j + MotionEvent.SAMPLE_X] = w-reportData[j + MotionEvent.SAMPLE_X];
                        reportData[j + MotionEvent.SAMPLE_Y] = h-reportData[j + MotionEvent.SAMPLE_Y];
                        break;
                    }
                    case Surface.ROTATION_270: {
                        final float temp = reportData[j + MotionEvent.SAMPLE_X];
                        reportData[j + MotionEvent.SAMPLE_X] = h-reportData[j + MotionEvent.SAMPLE_Y];
                        reportData[j + MotionEvent.SAMPLE_Y] = temp;
                        break;
                    }
                }
            }
            
            // We only consider the first pointer when computing the edge
            // flags, since they are global to the event.
            if (action == MotionEvent.ACTION_DOWN) {
                if (reportData[MotionEvent.SAMPLE_X] <= 0) {
                    edgeFlags |= MotionEvent.EDGE_LEFT;
                } else if (reportData[MotionEvent.SAMPLE_X] >= dispW) {
                    edgeFlags |= MotionEvent.EDGE_RIGHT;
                }
                if (reportData[MotionEvent.SAMPLE_Y] <= 0) {
                    edgeFlags |= MotionEvent.EDGE_TOP;
                } else if (reportData[MotionEvent.SAMPLE_Y] >= dispH) {
                    edgeFlags |= MotionEvent.EDGE_BOTTOM;
                }
            }
            
            if (currentMove != null) {
                if (false) Log.i("InputDevice", "Adding batch x="
                        + reportData[MotionEvent.SAMPLE_X]
                        + " y=" + reportData[MotionEvent.SAMPLE_Y]
                        + " to " + currentMove);
                currentMove.addBatch(curTime, reportData, metaState);
                if (WindowManagerPolicy.WATCH_POINTER) {
                    Log.i("KeyInputQueue", "Updating: " + currentMove);
                }
                return null;
            }
            
            MotionEvent me = MotionEvent.obtainNano(mDownTime, curTime,
                    curTimeNano, action, numPointers, mPointerIds, reportData,
                    metaState, xPrecision, yPrecision, device.id, edgeFlags);
            if (action == MotionEvent.ACTION_MOVE) {
                currentMove = me;
            }
            
            if (nextNumPointers < lastNumPointers) {
                removeOldPointer(upOrDownPointer);
            }
            
            return me;
        }
        
        boolean hasMore() {
            return mLastNumPointers != mNextNumPointers;
        }
        
        void finish() {
            mNextNumPointers = mAddingPointerOffset = 0;
            mNextData[MotionEvent.SAMPLE_PRESSURE] = 0;
        }
        
        MotionEvent generateRelMotion(InputDevice device, long curTime,
                long curTimeNano, int orientation, int metaState) {
            
            final float[] scaled = mReportData;
            
            // For now we only support 1 pointer with relative motions.
            scaled[MotionEvent.SAMPLE_X] = mNextData[MotionEvent.SAMPLE_X];
            scaled[MotionEvent.SAMPLE_Y] = mNextData[MotionEvent.SAMPLE_Y];
            scaled[MotionEvent.SAMPLE_PRESSURE] = 1.0f;
            scaled[MotionEvent.SAMPLE_SIZE] = 0;
            int edgeFlags = 0;
            
            int action;
            if (mNextNumPointers != mLastNumPointers) {
                mNextData[MotionEvent.SAMPLE_X] =
                        mNextData[MotionEvent.SAMPLE_Y] = 0;
                if (mNextNumPointers > 0 && mLastNumPointers == 0) {
                    action = MotionEvent.ACTION_DOWN;
                    mDownTime = curTime;
                } else if (mNextNumPointers == 0) {
                    action = MotionEvent.ACTION_UP;
                } else {
                    action = MotionEvent.ACTION_MOVE;
                }
                mLastNumPointers = mNextNumPointers;
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
                    curTimeNano, action, 1, mPointerIds, scaled, metaState,
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
