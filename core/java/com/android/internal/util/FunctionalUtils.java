/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.util;

import java.util.function.Supplier;

/**
 * Utilities specific to functional programming
 */
public class FunctionalUtils {
    private FunctionalUtils() {}

    /**
     * An equivalent of {@link Runnable} that allows throwing checked exceptions
     *
     * This can be used to specify a lambda argument without forcing all the checked exceptions
     * to be handled within it
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * An equivalent of {@link Supplier} that allows throwing checked exceptions
     *
     * This can be used to specify a lambda argument without forcing all the checked exceptions
     * to be handled within it
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * An equivalent of {@link java.util.function.Consumer} that allows throwing checked exceptions
     *
     * This can be used to specify a lambda argument without forcing all the checked exceptions
     * to be handled within it
     */
    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }
}
