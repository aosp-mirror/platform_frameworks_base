/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.filterfw.core;

import android.filterfw.core.Frame;

/**
 * @hide
 */
public class NativeBuffer {

    // These are set by the native layer
    private long mDataPointer = 0;
    private int mSize = 0;

    private Frame mAttachedFrame;

    private boolean mOwnsData = false;
    private int mRefCount = 1;

    public NativeBuffer() {
    }

    public NativeBuffer(int count) {
        allocate(count * getElementSize());
        mOwnsData = true;
    }

    public NativeBuffer mutableCopy() {
        NativeBuffer result = null;
        try {
            Class myClass = getClass();
            result = (NativeBuffer)myClass.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Unable to allocate a copy of " + getClass() + "! Make " +
                                       "sure the class has a default constructor!");
        }
        if (mSize > 0 && !nativeCopyTo(result)) {
            throw new RuntimeException("Failed to copy NativeBuffer to mutable instance!");
        }
        return result;
    }

    public int size() {
        return mSize;
    }

    public int count() {
        return (mDataPointer != 0) ? mSize / getElementSize() : 0;
    }

    public int getElementSize() {
        return 1;
    }

    public NativeBuffer retain() {
        if (mAttachedFrame != null) {
            mAttachedFrame.retain();
        } else if (mOwnsData) {
            ++mRefCount;
        }
        return this;
    }

    public NativeBuffer release() {
        // Decrement refcount
        boolean doDealloc = false;
        if (mAttachedFrame != null) {
            doDealloc = (mAttachedFrame.release() == null);
        } else if (mOwnsData) {
            --mRefCount;
            doDealloc = (mRefCount == 0);
        }

        // Deallocate if necessary
        if (doDealloc) {
            deallocate(mOwnsData);
            return null;
        } else {
            return this;
        }
    }

    public boolean isReadOnly() {
        return (mAttachedFrame != null) ? mAttachedFrame.isReadOnly() : false;
    }

    static {
        System.loadLibrary("filterfw");
    }

    void attachToFrame(Frame frame) {
        // We do not auto-retain. We expect the user to call retain() if they want to hold on to
        // the frame.
        mAttachedFrame = frame;
    }

    protected void assertReadable() {
        if (mDataPointer == 0 || mSize == 0
        || (mAttachedFrame != null && !mAttachedFrame.hasNativeAllocation())) {
            throw new NullPointerException("Attempting to read from null data frame!");
        }
    }

    protected void assertWritable() {
        if (isReadOnly()) {
            throw new RuntimeException("Attempting to modify read-only native (structured) data!");
        }
    }

    private native boolean allocate(int size);
    private native boolean deallocate(boolean ownsData);
    private native boolean nativeCopyTo(NativeBuffer buffer);
}
