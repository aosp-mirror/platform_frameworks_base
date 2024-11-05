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
package com.android.ravenwoodtest.runtimetest;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static android.os.Process.THREAD_PRIORITY_DEFAULT;
import static android.os.Process.THREAD_PRIORITY_FOREGROUND;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.os.Process;
import android.system.Os;

import org.junit.Test;

public class ProcessTest {

    @Test
    public void testGetUidPidTid() {
        assertEquals(Os.getuid(), Process.myUid());
        assertEquals(Os.getpid(), Process.myPid());
        assertEquals(Os.gettid(), Process.myTid());
    }

    @Test
    public void testThreadPriority() {
        assertThrows(UnsupportedOperationException.class,
                () -> Process.getThreadPriority(Process.myTid() + 1));
        assertThrows(UnsupportedOperationException.class,
                () -> Process.setThreadPriority(Process.myTid() + 1, THREAD_PRIORITY_DEFAULT));
        assertEquals(THREAD_PRIORITY_DEFAULT, Process.getThreadPriority(Process.myTid()));
        Process.setThreadPriority(THREAD_PRIORITY_FOREGROUND);
        assertEquals(THREAD_PRIORITY_FOREGROUND, Process.getThreadPriority(Process.myTid()));
        Process.setCanSelfBackground(false);
        Process.setThreadPriority(THREAD_PRIORITY_DEFAULT);
        assertEquals(THREAD_PRIORITY_DEFAULT, Process.getThreadPriority(Process.myTid()));
        assertThrows(IllegalArgumentException.class,
                () -> Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND));
    }
}
