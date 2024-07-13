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

import static android.content.res.Configuration.UI_MODE_TYPE_MASK;
import static android.content.res.Configuration.UI_MODE_TYPE_VR_HEADSET;

import android.annotation.NonNull;
import android.content.res.Configuration;
import android.graphics.Rect;

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

    /**
     * Returns the aspect ratio of the given {@code rect}.
     */
    static float computeAspectRatio(Rect rect) {
        final int width = rect.width();
        final int height = rect.height();
        if (width == 0 || height == 0) {
            return 0;
        }
        return Math.max(width, height) / (float) Math.min(width, height);
    }

    /**
     * @param config The current {@link Configuration}
     * @return {@code true} if using a VR headset.
     */
    static boolean isInVrUiMode(Configuration config) {
        return (config.uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_VR_HEADSET;
    }
}
