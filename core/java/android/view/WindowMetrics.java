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
import android.graphics.Point;
import android.graphics.Rect;

/**
 * Metrics about a Window, consisting of the bounds and {@link WindowInsets}.
 * <p>
 * This is usually obtained from {@link WindowManager#getCurrentWindowMetrics()} and
 * {@link WindowManager#getMaximumWindowMetrics()}.
 *
 * @see WindowInsets#getInsets(int)
 * @see WindowManager#getCurrentWindowMetrics()
 * @see WindowManager#getMaximumWindowMetrics()
 */
public final class WindowMetrics {
    private final @NonNull Rect mBounds;
    private final @NonNull WindowInsets mWindowInsets;

    public WindowMetrics(@NonNull Rect bounds, @NonNull WindowInsets windowInsets) {
        mBounds = bounds;
        mWindowInsets = windowInsets;
    }

    /**
     * Returns the bounds of the area associated with this window or visual context.
     * <p>
     * <b>Note that the size of the reported bounds can have different size than
     * {@link Display#getSize(Point)}.</b> This method reports the window size including all system
     * bar areas, while {@link Display#getSize(Point)} reports the area excluding navigation bars
     * and display cutout areas. The value reported by {@link Display#getSize(Point)} can be
     * obtained by using:
     * <pre class="prettyprint">
     * final WindowMetrics metrics = windowManager.getCurrentMetrics();
     * // Gets all excluding insets
     * final WindowInsets windowInsets = metrics.getWindowInsets();
     * Insets insets = windowInsets.getInsets(WindowInsets.Type.navigationBars());
     * final DisplayCutout cutout = windowInsets.getCutout();
     * if (cutout != null) {
     *     final Insets cutoutSafeInsets = Insets.of(cutout.getSafeInsetsLeft(), ...);
     *     insets = insets.max(insets, cutoutSafeInsets);
     * }
     *
     * int insetsWidth = insets.right + insets.left;
     * int insetsHeight = insets.top + insets.bottom;
     *
     * // Legacy size that Display#getSize reports
     * final Size legacySize = new Size(metrics.getWidth() - insetsWidth,
     *         metrics.getHeight() - insetsHeight);
     * </pre>
     * </p>
     *
     * @return window bounds in pixels.
     */
    public @NonNull Rect getBounds() {
        return mBounds;
    }

    /**
     * Returns the {@link WindowInsets} of the area associated with this window or visual context.
     *
     * @return the {@link WindowInsets} of the visual area.
     */
    public @NonNull WindowInsets getWindowInsets() {
        return mWindowInsets;
    }
}
