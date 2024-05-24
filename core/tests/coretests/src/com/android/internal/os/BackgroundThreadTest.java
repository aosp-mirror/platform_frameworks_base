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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.ravenwood.RavenwoodRule;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.Executor;

public class BackgroundThreadTest {

    @Rule
    public final RavenwoodRule mRavenwood =
            new RavenwoodRule.Builder().setProvideMainThread(true).build();

    @Test
    public void test_get() {
        BackgroundThread thread = BackgroundThread.get();
        assertThat(thread.getLooper()).isNotEqualTo(Looper.getMainLooper());
    }

    @Test
    public void test_getHandler() {
        Handler handler = BackgroundThread.getHandler();
        ConditionVariable done = new ConditionVariable();
        handler.post(done::open);
        boolean success = done.block(5000);
        assertThat(success).isTrue();
    }

    @Test
    public void test_getExecutor() {
        Executor executor = BackgroundThread.getExecutor();
        ConditionVariable done = new ConditionVariable();
        executor.execute(done::open);
        boolean success = done.block(5000);
        assertThat(success).isTrue();
    }
}
