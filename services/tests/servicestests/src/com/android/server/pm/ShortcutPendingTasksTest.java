/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.pm;

import android.os.Handler;
import android.os.Looper;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.LargeTest;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Run with:
 adb install \
   -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutPendingTasksTest \
   -w com.android.frameworks.servicestests
 */
@LargeTest
public class ShortcutPendingTasksTest extends BaseShortcutManagerTest {
    public void testAll() {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();

        final AtomicBoolean threadCheckerResult = new AtomicBoolean(true);

        final Handler handler = new Handler(Looper.getMainLooper());

        final ShortcutPendingTasks tasks = new ShortcutPendingTasks(
                handler::post,
                threadCheckerResult::get,
                thrown::set);

        // No pending tasks, shouldn't block.
        assertTrue(tasks.waitOnAllTasks());

        final AtomicInteger counter = new AtomicInteger();

        // Run one task.
        tasks.addTask(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignore) {
            }
            counter.incrementAndGet();
        });

        assertTrue(tasks.waitOnAllTasks());
        assertNull(thrown.get());

        assertEquals(1, counter.get());

        // Run 3 tasks.

        // We use this ID to make sure only one task can run at the same time.
        final AtomicInteger currentTaskId = new AtomicInteger();

        tasks.addTask(() -> {
            currentTaskId.set(1);
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignore) {
            }
            counter.incrementAndGet();
            assertEquals(1, currentTaskId.get());
        });
        tasks.addTask(() -> {
            currentTaskId.set(2);
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignore) {
            }
            counter.incrementAndGet();
            assertEquals(2, currentTaskId.get());
        });
        tasks.addTask(() -> {
            currentTaskId.set(3);
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignore) {
            }
            counter.incrementAndGet();
            assertEquals(3, currentTaskId.get());
        });

        assertTrue(tasks.waitOnAllTasks());
        assertNull(thrown.get());
        assertEquals(4, counter.get());

        // No tasks running, shouldn't block.
        assertTrue(tasks.waitOnAllTasks());
        assertNull(thrown.get());
        assertEquals(4, counter.get());

        // Now the thread checker returns false, so waitOnAllTasks() returns false.
        threadCheckerResult.set(false);
        assertFalse(tasks.waitOnAllTasks());

        threadCheckerResult.set(true);

        // Make sure the exception handler is called.
        tasks.addTask(() -> {
            throw new RuntimeException("XXX");
        });
        assertTrue(tasks.waitOnAllTasks());
        assertNotNull(thrown.get());
        MoreAsserts.assertContainsRegex("XXX", thrown.get().getMessage());
    }
}
