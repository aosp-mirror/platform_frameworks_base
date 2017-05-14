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


import com.android.settingslib.TestConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class ThreadUtilsTest {

    @Test
    public void testMainThread() throws InterruptedException {
        assertThat(ThreadUtils.isMainThread()).isTrue();
        Thread background = new Thread(new Runnable() {
            public void run() {
                assertThat(ThreadUtils.isMainThread()).isFalse();
            }
        });
        background.start();
        background.join();
    }

    @Test
    public void testEnsureMainThread() throws InterruptedException {
        ThreadUtils.ensureMainThread();
        Thread background = new Thread(new Runnable() {
            public void run() {
                try {
                    ThreadUtils.ensureMainThread();
                    fail("Should not pass ensureMainThread in a background thread");
                } catch (RuntimeException e) {
                }
            }
        });
        background.start();
        background.join();
    }
}
