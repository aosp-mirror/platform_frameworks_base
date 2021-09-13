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

package androidx.window.extensions.embedding;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.window.extensions.organizer.SplitController;

/**
 * Provider for the reference implementation of androidx.window.extensions.embedding OEM interface
 * for use with WindowManager Jetpack.
 */
public class ActivityEmbeddingComponentProvider {
    private static SplitController sInstance;

    private ActivityEmbeddingComponentProvider() {
    }

    /**
     * Returns {@code true} if {@link ActivityEmbeddingComponent} is present on the device,
     * {@code false} otherwise. If the component is not available the developer will receive a
     * single callback with empty data or default values where possible.
     */
    public static boolean isEmbeddingComponentAvailable() {
        return true;
    }

    /**
     * Returns the OEM implementation of {@link ActivityEmbeddingComponent} if it is supported on
     * the device. The implementation must match the API level reported in
     * {@link androidx.window.extensions.WindowLibraryInfo}. An
     * {@link UnsupportedOperationException} will be thrown if the device does not support
     * Activity Embedding. Use
     * {@link ActivityEmbeddingComponentProvider#isEmbeddingComponentAvailable()} to determine if
     * {@link ActivityEmbeddingComponent} is present.
     * @return the OEM implementation of {@link ActivityEmbeddingComponent}
     */
    @NonNull
    public static ActivityEmbeddingComponent getActivityEmbeddingComponent(
            @NonNull Context context) {
        if (sInstance == null) {
            synchronized (ActivityEmbeddingComponentProvider.class) {
                if (sInstance == null) {
                    sInstance = new SplitController();
                }
            }
        }
        return sInstance;
    }
}
