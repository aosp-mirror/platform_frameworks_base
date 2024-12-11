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

package com.android.wm.shell.shared;

import android.annotation.NonNull;

import java.util.function.Supplier;

/**
 * This class provides generic utility methods and classes for shell
 */
public class Utils {

    /**
     * Lazily returns object from a supplier with caching
     * @param <T> type of object to get
     */
    public static class Lazy<T> {
        private T mInstance;

        /**
         * @param supplier the supplier to use, when the instance has not yet been initialized
         * @return the cached value or the value from the supplier
         */
        public final T get(@NonNull Supplier<T> supplier) {
            if (mInstance == null) {
                mInstance = supplier.get();
            }
            return mInstance;
        }
    }
}
