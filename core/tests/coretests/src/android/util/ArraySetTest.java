/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.util;

import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ConcurrentModificationException;

/**
 * Unit tests for ArraySet that don't belong in CTS.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ArraySetTest {
    private static final String TAG = "ArraySetTest";
    ArraySet<String> mSet = new ArraySet<>();

    @After
    public void tearDown() {
        mSet = null;
    }

    /**
     * Attempt to generate a ConcurrentModificationException in ArraySet.
     * <p>
     * ArraySet is explicitly documented to be non-thread-safe, yet it's easy to accidentally screw
     * this up; ArraySet should (in the spirit of the core Java collection types) make an effort to
     * catch this and throw ConcurrentModificationException instead of crashing somewhere in its
     * internals.
     */
    @Test
    public void testConcurrentModificationException() throws Exception {
        final int testDurMs = 10_000;
        System.out.println("Starting ArraySet concurrency test");
        new Thread(() -> {
            int i = 0;
            while (mSet != null) {
                try {
                    mSet.add(String.format("key %d", i++));
                } catch (ArrayIndexOutOfBoundsException e) {
                    Log.e(TAG, "concurrent modification uncaught, causing indexing failure", e);
                    fail("Concurrent modification uncaught, causing indexing failure: " + e);
                } catch (ClassCastException e) {
                    Log.e(TAG, "concurrent modification uncaught, causing cache corruption", e);
                    fail("Concurrent modification uncaught, causing cache corruption: " + e);
                } catch (ConcurrentModificationException e) {
                    System.out.println("[successfully caught CME at put #" + i
                            + " size=" + (mSet == null ? "??" : String.valueOf(mSet.size())) + "]");
                    if (i % 200 == 0) {
                        System.out.print(".");
                    }
                }
            }
        }).start();
        for (int i = 0; i < (testDurMs / 100); i++) {
            try {
                if (mSet.size() % 4 == 0) {
                    mSet.clear();
                }
                System.out.print("X");
            } catch (ArrayIndexOutOfBoundsException e) {
                Log.e(TAG, "concurrent modification uncaught, causing indexing failure", e);
                fail("Concurrent modification uncaught, causing indexing failure: " + e);
            } catch (ClassCastException e) {
                Log.e(TAG, "concurrent modification uncaught, causing cache corruption", e);
                fail("Concurrent modification uncaught, causing cache corruption: " + e);
            } catch (ConcurrentModificationException e) {
                System.out.println(
                        "[successfully caught CME at clear #" + i + " size=" + mSet.size() + "]");
            }
        }
    }

    /**
     * Check to make sure the same operations behave as expected in a single thread.
     */
    @Test
    public void testNonConcurrentAccesses() throws Exception {
        for (int i = 0; i < 100000; i++) {
            try {
                mSet.add(String.format("key %d", i++));
                if (i % 200 == 0) {
                    System.out.print(".");
                }
                if (i % 500 == 0) {
                    mSet.clear();
                    System.out.print("X");
                }
            } catch (ConcurrentModificationException e) {
                Log.e(TAG, "concurrent modification caught on single thread", e);
                fail();
            }
        }
    }
}
