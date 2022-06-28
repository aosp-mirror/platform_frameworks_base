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

import static com.google.common.truth.Truth.assertWithMessage;

import android.view.View;
import android.view.WindowInsets;

import androidx.test.platform.app.InstrumentationRegistry;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Utility methods for IME stress test. */
public final class ImeStressTestUtil {

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(5);

    private ImeStressTestUtil() {
    }

    /** Checks if the IME is shown on the window that the given view belongs to. */
    public static boolean isImeShown(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        return insets.isVisible(WindowInsets.Type.ime());
    }

    /** Calls the callable on the main thread and returns the result. */
    public static <V> V callOnMainSync(Callable<V> callable) {
        AtomicReference<V> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                result.set(callable.call());
            } catch (Exception e) {
                throw new RuntimeException("Exception was thrown", e);
            }
        });
        return result.get();
    }

    /**
     * Waits until {@code pred} returns true, or throws on timeout.
     *
     * <p>The given {@code pred} will be called on the main thread.
     */
    public static void waitOnMainUntil(String message, Callable<Boolean> pred) {
        eventually(() -> assertWithMessage(message).that(pred.call()).isTrue(), TIMEOUT);
    }

    /** Waits until IME is shown, or throws on timeout. */
    public static void waitOnMainUntilImeIsShown(View view) {
        eventually(() -> assertWithMessage("IME should be shown").that(
                callOnMainSync(() -> isImeShown(view))).isTrue(), TIMEOUT);
    }

    /** Waits until IME is hidden, or throws on timeout. */
    public static void waitOnMainUntilImeIsHidden(View view) {
        //eventually(() -> assertThat(callOnMainSync(() -> isImeShown(view))).isFalse(), TIMEOUT);
        eventually(() -> assertWithMessage("IME should be hidden").that(
                callOnMainSync(() -> isImeShown(view))).isFalse(), TIMEOUT);
    }
}
