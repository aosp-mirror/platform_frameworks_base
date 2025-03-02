/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.ravenwoodtest.coretest;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.platform.test.ravenwood.RavenwoodUtils;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class RavenwoodMainThreadTest {
    private static final boolean RUN_UNSAFE_TESTS =
            "1".equals(System.getenv("RAVENWOOD_RUN_UNSAFE_TESTS"));

    @Test
    public void testRunOnMainThread() {
        AtomicReference<Thread> thr = new AtomicReference<>();
        RavenwoodUtils.runOnMainThreadSync(() -> {
            thr.set(Thread.currentThread());
        });
        var th = thr.get();
        assertThat(th).isNotNull();
        assertThat(th).isNotEqualTo(Thread.currentThread());
    }

    /**
     * Sleep a long time on the main thread. This test would then "pass", but Ravenwood
     * should show the stack traces.
     *
     * This is "unsafe" because this test is slow.
     */
    @Test
    public void testUnsafeMainThreadHang() {
        assumeTrue(RUN_UNSAFE_TESTS);

        // The test should time out.
        RavenwoodUtils.runOnMainThreadSync(() -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                fail("Interrupted");
            }
        });
    }

    /**
     * AssertionError on the main thread would be swallowed and reported "normally".
     * (Other kinds of exceptions would be caught by the unhandled exception handler, and kills
     * the process)
     *
     * This is "unsafe" only because this feature can be disabled via the env var.
     */
    @Test
    public void testUnsafeAssertFailureOnMainThread() {
        assumeTrue(RUN_UNSAFE_TESTS);

        assertThrows(AssertionError.class, () -> {
            RavenwoodUtils.runOnMainThreadSync(() -> {
                fail();
            });
        });
    }
}
