/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class UptimeTimerTest {
    private static final String TAG = "UptimeTimerTest";

    @Test
    public void testBasic() throws InterruptedException {
        AtomicBoolean taskRan = new AtomicBoolean(false);
        UptimeTimer timer = new UptimeTimer("TestTimer");
        timer.createTask(() -> taskRan.set(true), 100);
        Thread.sleep(50);
        boolean before = taskRan.get();
        Thread.sleep(100);
        boolean after = taskRan.get();
        assertFalse(before);
        assertTrue(after);
    }

    @Test
    public void testCancel() throws InterruptedException {
        AtomicBoolean taskRan = new AtomicBoolean(false);
        UptimeTimer timer = new UptimeTimer("TestTimer");
        UptimeTimer.Task task = timer.createTask(() -> taskRan.set(true), 100);
        Thread.sleep(50);
        boolean before = taskRan.get();
        task.cancel();
        Thread.sleep(100);
        boolean after = taskRan.get();
        assertFalse(before);
        assertFalse(after);
    }
}
