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
package com.android.settingslib.utils;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
public class ThreadUtilsTest {

    @Test
    public void testMainThread() throws InterruptedException {
        assertThat(ThreadUtils.isMainThread()).isTrue();
        Thread background = new Thread(() -> assertThat(ThreadUtils.isMainThread()).isFalse());
        background.start();
        background.join();
    }

    @Test
    public void testEnsureMainThread() throws InterruptedException {
        ThreadUtils.ensureMainThread();
        Thread background = new Thread(() -> {
            try {
                ThreadUtils.ensureMainThread();
                fail("Should not pass ensureMainThread in a background thread");
            } catch (RuntimeException expected) {
            }
        });
        background.start();
        background.join();
    }

    @Test
    public void testPostOnMainThread_shouldRunOnMainTread() {
        TestRunnable cr = new TestRunnable();
        ShadowLooper.pauseMainLooper();
        ThreadUtils.postOnMainThread(cr);
        assertThat(cr.called).isFalse();

        ShadowLooper.runUiThreadTasks();
        assertThat(cr.called).isTrue();
        assertThat(cr.onUiThread).isTrue();
    }

    private static class TestRunnable implements Runnable {
        volatile boolean called;
        volatile boolean onUiThread;

        public void run() {
            this.called = true;
            this.onUiThread = ThreadUtils.isMainThread();
        }
    }

}
