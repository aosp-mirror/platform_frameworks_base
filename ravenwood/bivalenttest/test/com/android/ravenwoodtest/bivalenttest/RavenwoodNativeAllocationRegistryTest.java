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
package com.android.ravenwoodtest.bivalenttest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import libcore.util.NativeAllocationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RavenwoodNativeAllocationRegistryTest {
    private static final String TAG = RavenwoodNativeAllocationRegistryTest.class.getSimpleName();
    static {
        RavenwoodJniTest.initializeJni();
    }

    @Rule
    public final RavenwoodRule mRavenwoodRule = new RavenwoodRule();

    private static class Data {
        private final long mNativePtr;

        private static native long nMalloc(int value);
        private static native int nGet(long ptr);
        private static native long nGetNativeFinalizer();

        public static native int nGetTotalAlloc();

        public int get() {
            return nGet(mNativePtr);
        }

        private static class NarHolder {
            public static final NativeAllocationRegistry sRegistry =
                    NativeAllocationRegistry.createMalloced(
                            Data.class.getClassLoader(), nGetNativeFinalizer());
        }

        public Data(int value) {
            mNativePtr = nMalloc(value);
            NarHolder.sRegistry.registerNativeAllocation(this, mNativePtr);
        }
    }

    @Test
    public void testNativeAllocationRegistry() {

        final long timeoutTime = mRavenwoodRule.realCurrentTimeMillis() + 10_000;

        final int startAlloc = Data.nGetTotalAlloc();

        int totalAlloc = 0;

        // Keep allocation new objects, until some get released.

        while (true) {
            for (int i = 0; i < 1000; i++) {
                totalAlloc++;
                Data d = new Data(i);
                assertEquals(i, d.get());
            }
            System.gc();

            final int currentAlloc = Data.nGetTotalAlloc() - startAlloc;
            Log.i(TAG, "# of currently allocated objects=" + currentAlloc);

            if (currentAlloc < totalAlloc) {
                break; // Good, some objects have been released;
            }
            if (mRavenwoodRule.realCurrentTimeMillis() > timeoutTime) {
                fail("No objects have been released before timeout");
            }
        }
    }
}
