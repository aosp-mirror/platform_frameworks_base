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

package android.view;

import android.annotation.NonNull;
import android.util.Size;

/**
 * Metrics about a Window, consisting of the size and {@link WindowInsets}.
 * <p>
 * This is usually obtained from {@link WindowManager#getCurrentWindowMetrics()} and
 * {@link WindowManager#getMaximumWindowMetrics()}.
 *
 * @see WindowInsets#getInsets(int)
 * @see WindowManager#getCurrentWindowMetrics()
 * @see WindowManager#getMaximumWindowMetrics()
 */
public final class WindowMetrics {
    private final @NonNull Size mSize;
    private final @NonNull WindowInsets mWindowInsets;

    public WindowMetrics(@NonNull Size size, @NonNull WindowInsets windowInsets) {
        mSize = size;
        mWindowInsets = windowInsets;
    }

    /**
     * Returns the size of the window.
     *
     * @return window size in pixel.
     */
    public @NonNull Size getSize() {
        return mSize;
    }

    /**
     * Returns the {@link WindowInsets} of the window.
     *
     * @return the {@link WindowInsets} of the window.
     */
    public @NonNull WindowInsets getWindowInsets() {
        return mWindowInsets;
    }
}
