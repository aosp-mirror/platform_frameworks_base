/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.view.InputChannel;
import android.view.InputTarget;

/**
 * A specialized list of input targets backed by an array.
 * 
 * This class is part of an InputManager optimization to avoid allocating and copying
 * input target arrays unnecessarily on return from JNI callbacks.  Internally, it keeps
 * an array full of demand-allocated InputTarget objects that it recycles each time the
 * list is cleared.  The used portion of the array is padded with a null.
 * 
 * @hide
 */
public final class InputTargetList {
    private InputTarget[] mArray;
    private int mCount;
    
    /**
     * Creates an empty input target list.
     */
    public InputTargetList() {
        mArray = new InputTarget[8];
    }
    
    /**
     * Clears the input target list.
     */
    public void clear() {
        if (mCount == 0) {
            return;
        }
        
        int count = mCount;
        mCount = 0;
        mArray[count] = mArray[0];
        while (count > 0) {
            count -= 1;
            mArray[count].recycle();
        }
        mArray[0] = null;
    }
    
    /**
     * Adds a new input target to the input target list.
     * @param inputChannel The input channel of the target window.
     * @param flags Input target flags.
     * @param timeoutNanos The input dispatch timeout (before ANR) in nanoseconds or -1 if none.
     * @param xOffset An offset to add to motion X coordinates during delivery.
     * @param yOffset An offset to add to motion Y coordinates during delivery.
     */
    public void add(InputChannel inputChannel, int flags, long timeoutNanos,
            float xOffset, float yOffset) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null");
        }
        
        if (mCount + 1 == mArray.length) {
            InputTarget[] oldArray = mArray;
            mArray = new InputTarget[oldArray.length * 2];
            System.arraycopy(oldArray, 0, mArray, 0, mCount);
        }
        
        // Grab InputTarget from tail (after used section) if available.
        InputTarget inputTarget = mArray[mCount + 1];
        if (inputTarget == null) {
            inputTarget = new InputTarget();
        }
        inputTarget.mInputChannel = inputChannel;
        inputTarget.mFlags = flags;
        inputTarget.mTimeoutNanos = timeoutNanos;
        inputTarget.mXOffset = xOffset;
        inputTarget.mYOffset = yOffset;
        
        mArray[mCount] = inputTarget;
        mCount += 1;
        mArray[mCount] = null;
    }
    
    /**
     * Gets the input targets as a null-terminated array.
     * @return The input target array.
     */
    public InputTarget[] toNullTerminatedArray() {
        return mArray;
    }
}