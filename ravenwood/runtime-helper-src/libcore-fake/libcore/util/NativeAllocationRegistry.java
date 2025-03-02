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

import com.android.ravenwood.RavenwoodRuntimeNative;

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
        return new NativeAllocationRegistry(classLoader, freeFunction, size);
    }

    public static NativeAllocationRegistry createNonmalloced(
            Class clazz, long freeFunction, long size) {
        return new NativeAllocationRegistry(clazz.getClassLoader(), freeFunction, size);
    }

    public static NativeAllocationRegistry createMalloced(
            ClassLoader classLoader, long freeFunction, long size) {
        return new NativeAllocationRegistry(classLoader, freeFunction, size);
    }

    public static NativeAllocationRegistry createMalloced(
            ClassLoader classLoader, long freeFunction) {
        return new NativeAllocationRegistry(classLoader, freeFunction, 0);
    }

    public static NativeAllocationRegistry createMalloced(
            Class clazz, long freeFunction, long size) {
        return new NativeAllocationRegistry(clazz.getClassLoader(), freeFunction, size);
    }

    public NativeAllocationRegistry(ClassLoader classLoader, long freeFunction, long size) {
        if (size < 0) {
            throw new IllegalArgumentException("Invalid native allocation size: " + size);
        }
        mFreeFunction = freeFunction;
    }

    private class CleanerThunk implements Runnable {
        private long nativePtr;

        public CleanerThunk() {
            nativePtr = 0;
        }

        public void setNativePtr(long ptr) {
            nativePtr = ptr;
        }

        @Override
        public void run() {
            if (nativePtr != 0) {
                applyFreeFunction(mFreeFunction, nativePtr);
            }
        }
    }

    private static class CleanableRunner implements Runnable {
        private final Cleaner.Cleanable mCleanable;

        public CleanableRunner(Cleaner.Cleanable cleanable) {
            mCleanable = cleanable;
        }

        public void run() {
            mCleanable.clean();
        }
    }

    public Runnable registerNativeAllocation(Object referent, long nativePtr) {
        if (referent == null) {
            throw new IllegalArgumentException("referent is null");
        }
        if (mFreeFunction == 0) {
            return () -> {}; // do nothing
        }
        if (nativePtr == 0) {
            throw new IllegalArgumentException("nativePtr is null");
        }

        final CleanerThunk thunk;
        final CleanableRunner result;
        try {
            thunk = new CleanerThunk();
            final var cleanable = sCleaner.register(referent, thunk);
            result = new CleanableRunner(cleanable);
        } catch (VirtualMachineError vme /* probably OutOfMemoryError */) {
            applyFreeFunction(mFreeFunction, nativePtr);
            throw vme;
        }

        // Enable the cleaner only after we can no longer throw anything, including OOME.
        thunk.setNativePtr(nativePtr);
        // Ensure that cleaner doesn't get invoked before we enable it.
        Reference.reachabilityFence(referent);
        return result;
    }

    public static void applyFreeFunction(long freeFunction, long nativePtr) {
        RavenwoodRuntimeNative.applyFreeFunction(freeFunction, nativePtr);
    }
}
