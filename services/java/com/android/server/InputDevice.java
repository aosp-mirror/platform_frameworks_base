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

import android.util.Slog;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManagerPolicy;

import java.io.PrintWriter;

public class InputDevice {
    static final boolean DEBUG_POINTERS = false;
    static final boolean DEBUG_HACKS = false;
    
    /** Amount that trackball needs to move in order to generate a key event. */
    static final int TRACKBALL_MOVEMENT_THRESHOLD = 6;

    /** Maximum number of pointers we will track and report. */
    static final int MAX_POINTERS = 10;
    
    /**
     * Slop distance for jumpy pointer detection.
     * The vertical range of the screen divided by this is our epsilon value.
     */
    private static final int JUMPY_EPSILON_DIVISOR = 212;
    
    /** Number of jumpy points to drop for touchscreens that need it. */
    private static final int JUMPY_TRANSITION_DROPS = 3;
    private static final int JUMPY_DROP_LIMIT = 3;
    
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
        boolean everChanged = false;
        long mDownTime = 0;
        
        // The currently assigned pointer IDs, corresponding to the last data.
        int[] mPointerIds = new int[MAX_POINTERS];
        
        // This is the last generated pointer data, ordered to match
        // mPointerIds.
        boolean mSkipLastPointers;
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
        
        // Used to determine whether we dropped bad data, to avoid doing
        // it repeatedly.
        final boolean[] mDroppedBadPoint = new boolean[MAX_POINTERS];

        // Used to count the number of jumpy points dropped.
        private int mJumpyPointsDropped = 0;
        
        // Used to perform averaging of reported coordinates, to smooth
        // the data and filter out transients during a release.
        static final int HISTORY_SIZE = 5;
        int[] mHistoryDataStart = new int[MAX_POINTERS];
        int[] mHistoryDataEnd = new int[MAX_POINTERS];
        final int[] mHistoryData = new int[(MotionEvent.NUM_SAMPLE_DATA * MAX_POINTERS)
                                        * HISTORY_SIZE];
        final int[] mAveragedData = new int[MotionEvent.NUM_SAMPLE_DATA * MAX_POINTERS];
        
        // Temporary data structures for doing the pointer ID mapping.
        final int[] mLast2Next = new int[MAX_POINTERS];
        final int[] mNext2Last = new int[MAX_POINTERS];
        final long[] mNext2LastDistance = new long[MAX_POINTERS];
        
        // Temporary data structure for generating the final motion data.
        final float[] mReportData = new float[MotionEvent.NUM_SAMPLE_DATA * MAX_POINTERS];
        
        // This is not used here, but can be used by callers for state tracking.
        int mAddingPointerOffset = 0;
        final boolean[] mDown = new boolean[MAX_POINTERS];
        
        void dumpIntArray(PrintWriter pw, int[] array) {
            pw.print("[");
            for (int i=0; i<array.length; i++) {
                if (i > 0) pw.print(", ");
                pw.print(array[i]);
            }
            pw.print("]");
        }
        
        void dumpBooleanArray(PrintWriter pw, boolean[] array) {
            pw.print("[");
            for (int i=0; i<array.length; i++) {
                if (i > 0) pw.print(", ");
                pw.print(array[i] ? "true" : "false");
            }
            pw.print("]");
        }
        
        void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.print("xPrecision="); pw.print(xPrecision);
                    pw.print(" yPrecision="); pw.println(yPrecision);
            pw.print(prefix); pw.print("xMoveScale="); pw.print(xMoveScale);
                    pw.print(" yMoveScale="); pw.println(yMoveScale);
            if (currentMove != null) {
                pw.print(prefix); pw.print("currentMove="); pw.println(currentMove);
            }
            if (changed || mDownTime != 0) {
                pw.print(prefix); pw.print("changed="); pw.print(changed);
                        pw.print(" mDownTime="); pw.println(mDownTime);
            }
            pw.print(prefix); pw.print("mPointerIds="); dumpIntArray(pw, mPointerIds);
                    pw.println("");
            if (mSkipLastPointers || mLastNumPointers != 0) {
                pw.print(prefix); pw.print("mSkipLastPointers="); pw.print(mSkipLastPointers);
                        pw.print(" mLastNumPointers="); pw.println(mLastNumPointers);
                pw.print(prefix); pw.print("mLastData="); dumpIntArray(pw, mLastData);
                        pw.println("");
            }
            if (mNextNumPointers != 0) {
                pw.print(prefix); pw.print("mNextNumPointers="); pw.println(mNextNumPointers);
                pw.print(prefix); pw.print("mNextData="); dumpIntArray(pw, mNextData);
                        pw.println("");
            }
            pw.print(prefix); pw.print("mDroppedBadPoint=");
                    dumpBooleanArray(pw, mDroppedBadPoint); pw.println("");
            pw.print(prefix); pw.print("mAddingPointerOffset="); pw.println(mAddingPointerOffset);
            pw.print(prefix); pw.print("mDown=");
                    dumpBooleanArray(pw, mDown); pw.println("");
        }
        
        MotionState(int mx, int my) {
            xPrecision = mx;
            yPrecision = my;
            xMoveScale = mx != 0 ? (1.0f/mx) : 1.0f;
            yMoveScale = my != 0 ? (1.0f/my) : 1.0f;
            for (int i=0; i<MAX_POINTERS; i++) {
                mPointerIds[i] = i;
            }
        }
        
        /**
         * Special hack for devices that have bad screen data: if one of the
         * points has moved more than a screen height from the last position,
         * then drop it.
         */
        void dropBadPoint(InputDevice dev) {
            // We should always have absY, but let's be paranoid.
            if (dev.absY == null) {
                return;
            }
            // Don't do anything if a finger is going down or up.  We run
            // here before assigning pointer IDs, so there isn't a good
            // way to do per-finger matching.
            if (mNextNumPointers != mLastNumPointers) {
                return;
            }
            
            // We consider a single movement across more than a 7/16 of
            // the long size of the screen to be bad.  This was a magic value
            // determined by looking at the maximum distance it is feasible
            // to actually move in one sample.
            final int maxDy = ((dev.absY.maxValue-dev.absY.minValue)*7)/16;
            
            // Look through all new points and see if any are farther than
            // acceptable from all previous points.
            for (int i=mNextNumPointers-1; i>=0; i--) {
                final int ioff = i * MotionEvent.NUM_SAMPLE_DATA;
                //final int x = mNextData[ioff + MotionEvent.SAMPLE_X];
                final int y = mNextData[ioff + MotionEvent.SAMPLE_Y];
                if (DEBUG_HACKS) Slog.v("InputDevice", "Looking at next point #" + i + ": y=" + y);
                boolean dropped = false;
                if (!mDroppedBadPoint[i] && mLastNumPointers > 0) {
                    dropped = true;
                    int closestDy = -1;
                    int closestY = -1;
                    // We will drop this new point if it is sufficiently
                    // far away from -all- last points.
                    for (int j=mLastNumPointers-1; j>=0; j--) {
                        final int joff = j * MotionEvent.NUM_SAMPLE_DATA;
                        //int dx = x - mLastData[joff + MotionEvent.SAMPLE_X];
                        int dy = y - mLastData[joff + MotionEvent.SAMPLE_Y];
                        //if (dx < 0) dx = -dx;
                        if (dy < 0) dy = -dy;
                        if (DEBUG_HACKS) Slog.v("InputDevice", "Comparing with last point #" + j
                                + ": y=" + mLastData[joff] + " dy=" + dy);
                        if (dy < maxDy) {
                            dropped = false;
                            break;
                        } else if (closestDy < 0 || dy < closestDy) {
                            closestDy = dy;
                            closestY = mLastData[joff + MotionEvent.SAMPLE_Y];
                        }
                    }
                    if (dropped) {
                        dropped = true;
                        Slog.i("InputDevice", "Dropping bad point #" + i
                                + ": newY=" + y + " closestDy=" + closestDy
                                + " maxDy=" + maxDy);
                        mNextData[ioff + MotionEvent.SAMPLE_Y] = closestY;
                        break;
                    }
                }
                mDroppedBadPoint[i] = dropped;
            }
        }
        
        void dropJumpyPoint(InputDevice dev) {
            // We should always have absY, but let's be paranoid.
            if (dev.absY == null) {
                return;
            }
            final int jumpyEpsilon = dev.absY.range / JUMPY_EPSILON_DIVISOR;
            
            final int nextNumPointers = mNextNumPointers;
            final int lastNumPointers = mLastNumPointers;
            final int[] nextData = mNextData;
            final int[] lastData = mLastData;
            
            if (nextNumPointers != mLastNumPointers) {
                if (DEBUG_HACKS) {
                    Slog.d("InputDevice", "Different pointer count " + lastNumPointers + 
                            " -> " + nextNumPointers);
                    for (int i = 0; i < nextNumPointers; i++) {
                        int ioff = i * MotionEvent.NUM_SAMPLE_DATA;
                        Slog.d("InputDevice", "Pointer " + i + " (" + 
                                mNextData[ioff + MotionEvent.SAMPLE_X] + ", " +
                                mNextData[ioff + MotionEvent.SAMPLE_Y] + ")");
                    }
                }
                
                // Just drop the first few events going from 1 to 2 pointers.
                // They're bad often enough that they're not worth considering.
                if (lastNumPointers == 1 && nextNumPointers == 2
                        && mJumpyPointsDropped < JUMPY_TRANSITION_DROPS) {
                    mNextNumPointers = 1;
                    mJumpyPointsDropped++;
                } else if (lastNumPointers == 2 && nextNumPointers == 1
                        && mJumpyPointsDropped < JUMPY_TRANSITION_DROPS) {
                    // The event when we go from 2 -> 1 tends to be messed up too
                    System.arraycopy(lastData, 0, nextData, 0, 
                            lastNumPointers * MotionEvent.NUM_SAMPLE_DATA);
                    mNextNumPointers = lastNumPointers;
                    mJumpyPointsDropped++;
                    
                    if (DEBUG_HACKS) {
                        for (int i = 0; i < mNextNumPointers; i++) {
                            int ioff = i * MotionEvent.NUM_SAMPLE_DATA;
                            Slog.d("InputDevice", "Pointer " + i + " replaced (" + 
                                    mNextData[ioff + MotionEvent.SAMPLE_X] + ", " +
                                    mNextData[ioff + MotionEvent.SAMPLE_Y] + ")");
                        }
                    }
                } else {
                    mJumpyPointsDropped = 0;
                    
                    if (DEBUG_HACKS) {
                        Slog.d("InputDevice", "Transition - drop limit reset");
                    }
                }
                return;
            }
            
            // A 'jumpy' point is one where the coordinate value for one axis
            // has jumped to the other pointer's location. No need to do anything
            // else if we only have one pointer.
            if (nextNumPointers < 2) {
                return;
            }
            
            int badPointerIndex = -1;
            int badPointerReplaceXWith = 0;
            int badPointerReplaceYWith = 0;
            int badPointerDistance = Integer.MIN_VALUE;
            for (int i = nextNumPointers - 1; i >= 0; i--) {
                boolean dropx = false;
                boolean dropy = false;
                
                // Limit how many times a jumpy point can get dropped.
                if (mJumpyPointsDropped < JUMPY_DROP_LIMIT) {
                    final int ioff = i * MotionEvent.NUM_SAMPLE_DATA;
                    final int x = nextData[ioff + MotionEvent.SAMPLE_X];
                    final int y = nextData[ioff + MotionEvent.SAMPLE_Y];
                    
                    if (DEBUG_HACKS) {
                        Slog.d("InputDevice", "Point " + i + " (" + x + ", " + y + ")");
                    }

                    // Check if a touch point is too close to another's coordinates
                    for (int j = 0; j < nextNumPointers && !dropx && !dropy; j++) {
                        if (j == i) {
                            continue;
                        }

                        final int joff = j * MotionEvent.NUM_SAMPLE_DATA;
                        final int xOther = nextData[joff + MotionEvent.SAMPLE_X];
                        final int yOther = nextData[joff + MotionEvent.SAMPLE_Y];

                        dropx = Math.abs(x - xOther) <= jumpyEpsilon;
                        dropy = Math.abs(y - yOther) <= jumpyEpsilon;
                    }
                    
                    if (dropx) {
                        int xreplace = lastData[MotionEvent.SAMPLE_X];
                        int yreplace = lastData[MotionEvent.SAMPLE_Y];
                        int distance = Math.abs(yreplace - y);
                        for (int j = 1; j < lastNumPointers; j++) {
                            final int joff = j * MotionEvent.NUM_SAMPLE_DATA;
                            int lasty = lastData[joff + MotionEvent.SAMPLE_Y];   
                            int currDist = Math.abs(lasty - y);
                            if (currDist < distance) {
                                xreplace = lastData[joff + MotionEvent.SAMPLE_X];
                                yreplace = lasty;
                                distance = currDist;
                            }
                        }
                        
                        int badXDelta = Math.abs(xreplace - x);
                        if (badXDelta > badPointerDistance) {
                            badPointerDistance = badXDelta;
                            badPointerIndex = i;
                            badPointerReplaceXWith = xreplace;
                            badPointerReplaceYWith = yreplace;
                        }
                    } else if (dropy) {
                        int xreplace = lastData[MotionEvent.SAMPLE_X];
                        int yreplace = lastData[MotionEvent.SAMPLE_Y];
                        int distance = Math.abs(xreplace - x);
                        for (int j = 1; j < lastNumPointers; j++) {
                            final int joff = j * MotionEvent.NUM_SAMPLE_DATA;
                            int lastx = lastData[joff + MotionEvent.SAMPLE_X];   
                            int currDist = Math.abs(lastx - x);
                            if (currDist < distance) {
                                xreplace = lastx;
                                yreplace = lastData[joff + MotionEvent.SAMPLE_Y];
                                distance = currDist;
                            }
                        }
                        
                        int badYDelta = Math.abs(yreplace - y);
                        if (badYDelta > badPointerDistance) {
                            badPointerDistance = badYDelta;
                            badPointerIndex = i;
                            badPointerReplaceXWith = xreplace;
                            badPointerReplaceYWith = yreplace;
                        }
                    }
                }
            }
            if (badPointerIndex >= 0) {
                if (DEBUG_HACKS) {
                    Slog.d("InputDevice", "Replacing bad pointer " + badPointerIndex +
                            " with (" + badPointerReplaceXWith + ", " + badPointerReplaceYWith +
                            ")");
                }

                final int offset = badPointerIndex * MotionEvent.NUM_SAMPLE_DATA;
                nextData[offset + MotionEvent.SAMPLE_X] = badPointerReplaceXWith;
                nextData[offset + MotionEvent.SAMPLE_Y] = badPointerReplaceYWith;
                mJumpyPointsDropped++;
            } else {
                mJumpyPointsDropped = 0;
            }
        }
        
        /**
         * Special hack for devices that have bad screen data: aggregate and
         * compute averages of the coordinate data, to reduce the amount of
         * jitter seen by applications.
         */
        int[] generateAveragedData(int upOrDownPointer, int lastNumPointers,
                int nextNumPointers) {
            final int numPointers = mLastNumPointers;
            final int[] rawData = mLastData;
            if (DEBUG_HACKS) Slog.v("InputDevice", "lastNumPointers=" + lastNumPointers
                    + " nextNumPointers=" + nextNumPointers
                    + " numPointers=" + numPointers);
            for (int i=0; i<numPointers; i++) {
                final int ioff = i * MotionEvent.NUM_SAMPLE_DATA;
                // We keep the average data in offsets based on the pointer
                // ID, so we don't need to move it around as fingers are
                // pressed and released.
                final int p = mPointerIds[i];
                final int poff = p * MotionEvent.NUM_SAMPLE_DATA * HISTORY_SIZE;
                if (i == upOrDownPointer && lastNumPointers != nextNumPointers) {
                    if (lastNumPointers < nextNumPointers) {
                        // This pointer is going down.  Clear its history
                        // and start fresh.
                        if (DEBUG_HACKS) Slog.v("InputDevice", "Pointer down @ index "
                                + upOrDownPointer + " id " + mPointerIds[i]);
                        mHistoryDataStart[i] = 0;
                        mHistoryDataEnd[i] = 0;
                        System.arraycopy(rawData, ioff, mHistoryData, poff,
                                MotionEvent.NUM_SAMPLE_DATA);
                        System.arraycopy(rawData, ioff, mAveragedData, ioff,
                                MotionEvent.NUM_SAMPLE_DATA);
                        continue;
                    } else {
                        // The pointer is going up.  Just fall through to
                        // recompute the last averaged point (and don't add
                        // it as a new point to include in the average).
                        if (DEBUG_HACKS) Slog.v("InputDevice", "Pointer up @ index "
                                + upOrDownPointer + " id " + mPointerIds[i]);
                    }
                } else {
                    int end = mHistoryDataEnd[i];
                    int eoff = poff + (end*MotionEvent.NUM_SAMPLE_DATA);
                    int oldX = mHistoryData[eoff + MotionEvent.SAMPLE_X];
                    int oldY = mHistoryData[eoff + MotionEvent.SAMPLE_Y];
                    int newX = rawData[ioff + MotionEvent.SAMPLE_X];
                    int newY = rawData[ioff + MotionEvent.SAMPLE_Y];
                    int dx = newX-oldX;
                    int dy = newY-oldY;
                    int delta = dx*dx + dy*dy;
                    if (DEBUG_HACKS) Slog.v("InputDevice", "Delta from last: " + delta);
                    if (delta >= (75*75)) {
                        // Magic number, if moving farther than this, turn
                        // off filtering to avoid lag in response.
                        mHistoryDataStart[i] = 0;
                        mHistoryDataEnd[i] = 0;
                        System.arraycopy(rawData, ioff, mHistoryData, poff,
                                MotionEvent.NUM_SAMPLE_DATA);
                        System.arraycopy(rawData, ioff, mAveragedData, ioff,
                                MotionEvent.NUM_SAMPLE_DATA);
                        continue;
                    } else {
                        end++;
                        if (end >= HISTORY_SIZE) {
                            end -= HISTORY_SIZE;
                        }
                        mHistoryDataEnd[i] = end;
                        int noff = poff + (end*MotionEvent.NUM_SAMPLE_DATA);
                        mHistoryData[noff + MotionEvent.SAMPLE_X] = newX;
                        mHistoryData[noff + MotionEvent.SAMPLE_Y] = newY;
                        mHistoryData[noff + MotionEvent.SAMPLE_PRESSURE]
                                = rawData[ioff + MotionEvent.SAMPLE_PRESSURE];
                        int start = mHistoryDataStart[i];
                        if (end == start) {
                            start++;
                            if (start >= HISTORY_SIZE) {
                                start -= HISTORY_SIZE;
                            }
                            mHistoryDataStart[i] = start;
                        }
                    }
                }
                
                // Now compute the average.
                int start = mHistoryDataStart[i];
                int end = mHistoryDataEnd[i];
                int x=0, y=0;
                int totalPressure = 0;
                while (start != end) {
                    int soff = poff + (start*MotionEvent.NUM_SAMPLE_DATA);
                    int pressure = mHistoryData[soff + MotionEvent.SAMPLE_PRESSURE];
                    if (pressure <= 0) pressure = 1;
                    x += mHistoryData[soff + MotionEvent.SAMPLE_X] * pressure;
                    y += mHistoryData[soff + MotionEvent.SAMPLE_Y] * pressure;
                    totalPressure += pressure;
                    start++;
                    if (start >= HISTORY_SIZE) start = 0;
                }
                int eoff = poff + (end*MotionEvent.NUM_SAMPLE_DATA);
                int pressure = mHistoryData[eoff + MotionEvent.SAMPLE_PRESSURE];
                if (pressure <= 0) pressure = 1;
                x += mHistoryData[eoff + MotionEvent.SAMPLE_X] * pressure;
                y += mHistoryData[eoff + MotionEvent.SAMPLE_Y] * pressure;
                totalPressure += pressure;
                x /= totalPressure;
                y /= totalPressure;
                if (DEBUG_HACKS) Slog.v("InputDevice", "Averaging " + totalPressure
                        + " weight: (" + x + "," + y + ")");
                mAveragedData[ioff + MotionEvent.SAMPLE_X] = x;
                mAveragedData[ioff + MotionEvent.SAMPLE_Y] = y;
                mAveragedData[ioff + MotionEvent.SAMPLE_PRESSURE] =
                        rawData[ioff + MotionEvent.SAMPLE_PRESSURE];
                mAveragedData[ioff + MotionEvent.SAMPLE_SIZE] =
                        rawData[ioff + MotionEvent.SAMPLE_SIZE];
            }
            return mAveragedData;
        }
        
        private boolean assignPointer(int nextIndex, boolean allowOverlap) {
            final int lastNumPointers = mLastNumPointers;
            final int[] next2Last = mNext2Last;
            final long[] next2LastDistance = mNext2LastDistance;
            final int[] last2Next = mLast2Next;
            final int[] lastData = mLastData;
            final int[] nextData = mNextData;
            final int id = nextIndex * MotionEvent.NUM_SAMPLE_DATA;
            
            if (DEBUG_POINTERS) Slog.v("InputDevice", "assignPointer: nextIndex="
                    + nextIndex + " dataOff=" + id);
            final int x1 = nextData[id + MotionEvent.SAMPLE_X];
            final int y1 = nextData[id + MotionEvent.SAMPLE_Y];
            
            long bestDistance = -1;
            int bestIndex = -1;
            for (int j=0; j<lastNumPointers; j++) {
                // If we are not allowing multiple new points to be assigned
                // to the same old pointer, then skip this one if it is already
                // detected as a conflict (-2).
                if (!allowOverlap && last2Next[j] < -1) {
                    continue;
                }
                final int jd = j * MotionEvent.NUM_SAMPLE_DATA;
                final int xd = lastData[jd + MotionEvent.SAMPLE_X] - x1;
                final int yd = lastData[jd + MotionEvent.SAMPLE_Y] - y1;
                final long distance = xd*(long)xd + yd*(long)yd;
                if (bestDistance == -1 || distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = j;
                }
            }
            
            if (DEBUG_POINTERS) Slog.v("InputDevice", "New index " + nextIndex
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
            
            if (DEBUG_POINTERS) Slog.v("InputDevice", "Old index " + bestIndex
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
            
            if (DEBUG_POINTERS) Slog.v("InputDevice",
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
                if (DEBUG_POINTERS) Slog.v("InputDevice", "Resolving conflicts");
                
                for (int i=0; i<lastNumPointers; i++) {
                    if (last2Next[i] != -2) {
                        continue;
                    }
                    
                    // Note that this algorithm is far from perfect.  Ideally
                    // we should do something like the one described at
                    // http://portal.acm.org/citation.cfm?id=997856
                    
                    if (DEBUG_POINTERS) Slog.v("InputDevice",
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
                            if (DEBUG_POINTERS) Slog.v("InputDevice",
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
                if (DEBUG_POINTERS) Slog.v("InputDevice", "Adding new pointer");
                int nextId = 0;
                int i=0;
                while (i < lastNumPointers) {
                    if (mPointerIds[i] > nextId) {
                        // Found a hole, insert the pointer here.
                        if (DEBUG_POINTERS) Slog.v("InputDevice",
                                "Inserting new pointer at hole " + i);
                        System.arraycopy(mPointerIds, i, mPointerIds,
                                i+1, lastNumPointers-i);
                        System.arraycopy(lastData, i*MotionEvent.NUM_SAMPLE_DATA,
                                lastData, (i+1)*MotionEvent.NUM_SAMPLE_DATA,
                                (lastNumPointers-i)*MotionEvent.NUM_SAMPLE_DATA);
                        System.arraycopy(next2Last, i, next2Last,
                                i+1, lastNumPointers-i);
                        break;
                    }
                    i++;
                    nextId++;
                }
                
                if (DEBUG_POINTERS) Slog.v("InputDevice",
                        "New pointer id " + nextId + " at index " + i);
                
                mLastNumPointers++;
                retIndex = i;
                mPointerIds[i] = nextId;
                
                // And assign this identifier to the first new pointer.
                for (int j=0; j<nextNumPointers; j++) {
                    if (next2Last[j] < 0) {
                        if (DEBUG_POINTERS) Slog.v("InputDevice",
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
                    if (DEBUG_POINTERS) Slog.v("InputDevice",
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
                if (DEBUG_POINTERS) Slog.v("InputDevice", "Removing old pointer");
                for (int i=0; i<lastNumPointers; i++) {
                    if (last2Next[i] == -1) {
                        if (DEBUG_POINTERS) Slog.v("InputDevice",
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
            
            if (mSkipLastPointers) {
                mSkipLastPointers = false;
                mLastNumPointers = 0;
            }
            
            if (mNextNumPointers <= 0 && mLastNumPointers <= 0) {
                return null;
            }
            
            final int lastNumPointers = mLastNumPointers;
            final int nextNumPointers = mNextNumPointers;
            if (mNextNumPointers > MAX_POINTERS) {
                Slog.w("InputDevice", "Number of pointers " + mNextNumPointers
                        + " exceeded maximum of " + MAX_POINTERS);
                mNextNumPointers = MAX_POINTERS;
            }
            
            int upOrDownPointer = updatePointerIdentifiers();
            
            final float[] reportData = mReportData;
            final int[] rawData;
            if (KeyInputQueue.BAD_TOUCH_HACK) {
                rawData = generateAveragedData(upOrDownPointer, lastNumPointers,
                        nextNumPointers);
            } else {
                rawData = mLastData;
            }
            
            final int numPointers = mLastNumPointers;
            
            if (DEBUG_POINTERS) Slog.v("InputDevice", "Processing "
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
                                | (upOrDownPointer << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                    }
                } else {
                    if (numPointers == 1) {
                        action = MotionEvent.ACTION_UP;
                    } else {
                        action = MotionEvent.ACTION_POINTER_UP
                                | (upOrDownPointer << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
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
                if (false) Slog.i("InputDevice", "Adding batch x="
                        + reportData[MotionEvent.SAMPLE_X]
                        + " y=" + reportData[MotionEvent.SAMPLE_Y]
                        + " to " + currentMove);
                currentMove.addBatch(curTime, reportData, metaState);
                if (WindowManagerPolicy.WATCH_POINTER) {
                    Slog.i("KeyInputQueue", "Updating: " + currentMove);
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
                if (false) Slog.i("InputDevice", "Adding batch x="
                        + scaled[MotionEvent.SAMPLE_X]
                        + " y=" + scaled[MotionEvent.SAMPLE_Y]
                        + " to " + currentMove);
                currentMove.addBatch(curTime, scaled, metaState);
                if (WindowManagerPolicy.WATCH_POINTER) {
                    Slog.i("KeyInputQueue", "Updating: " + currentMove);
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
        
        final void dump(PrintWriter pw) {
            pw.print("minValue="); pw.print(minValue);
            pw.print(" maxValue="); pw.print(maxValue);
            pw.print(" range="); pw.print(range);
            pw.print(" flat="); pw.print(flat);
            pw.print(" fuzz="); pw.print(fuzz);
        }
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
