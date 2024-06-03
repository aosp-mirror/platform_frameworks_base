/*
 * Copyright (C) 2024 The Android Open Source Project
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
package libcore.util;

import com.android.ravenwood.common.RavenwoodRuntimeNative;

import java.lang.ref.Cleaner;
import java.lang.ref.Reference;

/**
 * Re-implementation of ART's NativeAllocationRegistry for Ravenwood.
 * - We don't track the native allocation size on Ravenwood.
 * - sun.misc.Cleaner isn't available on the desktop JVM, so we use java.lang.ref.Cleaner.
 *   (Should ART switch to java.lang.ref.Cleaner?)
 */
public class NativeAllocationRegistry {
    private final long mFreeFunction;
    private static final Cleaner sCleaner = Cleaner.create();

    public static NativeAllocationRegistry createNonmalloced(
            ClassLoader classLoader, long freeFunction, long size) {
        return new NativeAllocationRegistry(classLoader, freeFunction, size, false);
    }

    public static NativeAllocationRegistry createMalloced(
            ClassLoader classLoader, long freeFunction, long size) {
        return new NativeAllocationRegistry(classLoader, freeFunction, size, true);
    }

    public static NativeAllocationRegistry createMalloced(
            ClassLoader classLoader, long freeFunction) {
        return new NativeAllocationRegistry(classLoader, freeFunction, 0, true);
    }

    public NativeAllocationRegistry(ClassLoader classLoader, long freeFunction, long size) {
        this(classLoader, freeFunction, size, size == 0);
    }

    private NativeAllocationRegistry(ClassLoader classLoader, long freeFunction, long size,
            boolean mallocAllocation) {
        if (size < 0) {
            throw new IllegalArgumentException("Invalid native allocation size: " + size);
        }
        mFreeFunction = freeFunction;
    }

    public Runnable registerNativeAllocation(Object referent, long nativePtr) {
        if (referent == null) {
            throw new IllegalArgumentException("referent is null");
        }
        if (nativePtr == 0) {
            throw new IllegalArgumentException("nativePtr is null");
        }

        final Runnable releaser = () -> {
            RavenwoodRuntimeNative.applyFreeFunction(mFreeFunction, nativePtr);
        };
        sCleaner.register(referent, releaser);

        // Ensure that cleaner doesn't get invoked before we enable it.
        Reference.reachabilityFence(referent);
        return releaser;
    }
}
