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

package com.android.inputmethod.stresstest;

import static com.android.compatibility.common.util.SystemUtil.eventually;

import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Intent;
import android.platform.test.annotations.RootPermissionTest;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RootPermissionTest
@RunWith(AndroidJUnit4.class)
public class ImeOpenCloseStressTest {

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(5);
    private static final int NUM_TEST_ITERATIONS = 100;

    private Instrumentation mInstrumentation;

    @Test
    public void test() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent()
                .setAction(Intent.ACTION_MAIN)
                .setClass(mInstrumentation.getContext(), TestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        TestActivity activity = (TestActivity) mInstrumentation.startActivitySync(intent);
        eventually(() -> assertThat(callOnMainSync(activity::hasWindowFocus)).isTrue(), TIMEOUT);
        for (int i = 0; i < NUM_TEST_ITERATIONS; i++) {
            mInstrumentation.runOnMainSync(activity::showIme);
            eventually(() -> assertThat(callOnMainSync(activity::isImeShown)).isTrue(), TIMEOUT);
            mInstrumentation.runOnMainSync(activity::hideIme);
            eventually(() -> assertThat(callOnMainSync(activity::isImeShown)).isFalse(), TIMEOUT);
        }
    }

    private <V> V callOnMainSync(Callable<V> callable) {
        AtomicReference<V> result = new AtomicReference<>();
        AtomicReference<Exception> thrownException = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> {
            try {
                result.set(callable.call());
            } catch (Exception e) {
                thrownException.set(e);
            }
        });
        if (thrownException.get() != null) {
            throw new RuntimeException("Exception thrown from Main thread", thrownException.get());
        }
        return result.get();
    }
}
