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

package com.android.server;

import static android.os.Process.getThreadPriority;
import static android.os.Process.myTid;
import static android.os.Process.setThreadPriority;

import static org.junit.Assert.assertEquals;

import android.os.Process;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Tests for {@link ThreadPriorityBooster}.
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ThreadPriorityBoosterTest
 */
@SmallTest
@Presubmit
public class ThreadPriorityBoosterTest {
    private static final int PRIORITY_BOOST = Process.THREAD_PRIORITY_FOREGROUND;
    private static final int PRIORITY_BOOST_MORE = Process.THREAD_PRIORITY_DISPLAY;

    private final ThreadPriorityBooster mBooster = new ThreadPriorityBooster(PRIORITY_BOOST,
            0 /* lockGuardIndex */);

    @Test
    public void testThreadPriorityBooster() {
        joinNewThread(() -> {
            final int origPriority = Process.THREAD_PRIORITY_DEFAULT;
            setThreadPriority(origPriority);

            boost(() -> {
                assertThreadPriority(PRIORITY_BOOST);
                boost(() -> {
                    // Inside the boost region, the priority should also apply to current thread.
                    mBooster.setBoostToPriority(PRIORITY_BOOST_MORE);
                    assertThreadPriority(PRIORITY_BOOST_MORE);
                });
                // It is still in the boost region so the set priority should be kept.
                assertThreadPriority(PRIORITY_BOOST_MORE);

                joinNewThread(() -> boost(() -> assertThreadPriority(PRIORITY_BOOST_MORE)));
            });
            // The priority should be restored after leaving the boost region.
            assertThreadPriority(origPriority);

            // It doesn't apply to current thread because outside of the boost region, but the boost
            // in other threads will use the set priority.
            mBooster.setBoostToPriority(PRIORITY_BOOST);
            joinNewThread(() -> boost(() -> assertThreadPriority(PRIORITY_BOOST)));

            assertThreadPriority(origPriority);
        });
    }

    private static void assertThreadPriority(int expectedPriority) {
        assertEquals(expectedPriority, getThreadPriority(myTid()));
    }

    private static void joinNewThread(Runnable action) {
        final Thread thread = new Thread(action);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException ignored) {
        }
    }

    private void boost(Runnable action) {
        try {
            mBooster.boost();
            action.run();
        } finally {
            mBooster.reset();
        }
    }
}
