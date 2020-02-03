/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm.testing;

/**
 * Assertions for WM tests.
 */
public class Assert {

    /**
     * Runs {@code r} and asserts that an exception of type {@code expectedThrowable} is thrown.
     * @param expectedThrowable the type of throwable that is expected to be thrown
     * @param r the {@link Runnable} which is run and expected to throw.
     * @throws AssertionError if {@code r} does not throw, or throws a runnable that is not an
     *                        instance of {@code expectedThrowable}.
     */
    // TODO: remove once Android migrates to JUnit 4.13, which provides assertThrows
    public static void assertThrows(Class<? extends Throwable> expectedThrowable, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            if (expectedThrowable.isInstance(t)) {
                return;
            } else if (t instanceof Exception) {
                throw new AssertionError("Expected " + expectedThrowable
                        + ", but got " + t.getClass(), t);
            } else {
                // Re-throw Errors and other non-Exception throwables.
                throw t;
            }
        }
        throw new AssertionError("Expected " + expectedThrowable + ", but nothing was thrown");
    }
}
