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


/**
 * A specialized list of window information objects backed by an array.
 * 
 * This class is part of an InputManager optimization to avoid allocating objects and arrays
 * unnecessarily.  Internally, it keeps an array full of demand-allocated objects that it
 * recycles each time the list is cleared.  The used portion of the array is padded with a null.
 * 
 * The contents of the list are intended to be Z-ordered from top to bottom.
 * 
 * @hide
 */
public final class InputWindowList {
    private InputWindow[] mArray;
    private int mCount;
    
    /**
     * Creates an empty list.
     */
    public InputWindowList() {
        mArray = new InputWindow[8];
    }
    
    /**
     * Clears the list.
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
     * Adds an uninitialized input window object to the list and returns it.
     */
    public InputWindow add() {
        if (mCount + 1 == mArray.length) {
            InputWindow[] oldArray = mArray;
            mArray = new InputWindow[oldArray.length * 2];
            System.arraycopy(oldArray, 0, mArray, 0, mCount);
        }
        
        // Grab object from tail (after used section) if available.
        InputWindow item = mArray[mCount + 1];
        if (item == null) {
            item = new InputWindow();
        }
        
        mArray[mCount] = item;
        mCount += 1;
        mArray[mCount] = null;
        return item;
    }
    
    /**
     * Gets the input window objects as a null-terminated array.
     * @return The input window array.
     */
    public InputWindow[] toNullTerminatedArray() {
        return mArray;
    }
}