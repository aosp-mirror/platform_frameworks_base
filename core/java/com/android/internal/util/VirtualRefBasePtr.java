/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.util;

/**
 * Helper class that contains a strong reference to a VirtualRefBase native
 * object. This will incStrong in the ctor, and decStrong in the finalizer.
 * It currently does no accounting of natively allocated memory, for the
 * benefit of either GC triggering or heap profiling.
 */
public final class VirtualRefBasePtr {
    // TODO(b/231729094): Convert to NativeAllocationRegistry?
    private long mNativePtr;

    public VirtualRefBasePtr(long ptr) {
        mNativePtr = ptr;
        nIncStrong(mNativePtr);
    }

    /*
     * Return the RefBase / VirtualLightRefBase native pointer.  Warning: The
     * caller must ensure that the VirtualRefBasePtr object remains reachable
     * while the result is in use. Ideally, the caller should invoke
     * {@link java.lang.ref.Reference#reachabilityFence}
     * on the VirtualRefBasePtr object (or on an object that refers to it) as
     * soon as the result is no longer needed.
     */
    public long get() {
        return mNativePtr;
    }

    public void release() {
        if (mNativePtr != 0) {
            nDecStrong(mNativePtr);
            mNativePtr = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }

    private static native void nIncStrong(long ptr);
    private static native void nDecStrong(long ptr);
}
