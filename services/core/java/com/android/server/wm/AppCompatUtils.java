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

package com.android.server.wm;

import android.annotation.NonNull;

import java.util.function.BooleanSupplier;

/**
 * Utilities for App Compat policies and overrides.
 */
class AppCompatUtils {

    /**
     * Lazy version of a {@link BooleanSupplier} which access an existing BooleanSupplier and
     * caches the value.
     *
     * @param supplier The BooleanSupplier to decorate.
     * @return A lazy implementation of a BooleanSupplier
     */
    @NonNull
    static BooleanSupplier asLazy(@NonNull BooleanSupplier supplier) {
        return new BooleanSupplier() {
            private boolean mRead;
            private boolean mValue;

            @Override
            public boolean getAsBoolean() {
                if (!mRead) {
                    mRead = true;
                    mValue = supplier.getAsBoolean();
                }
                return mValue;
            }
        };
    }
}
