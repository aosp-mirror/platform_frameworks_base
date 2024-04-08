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

import java.util.function.Supplier;

/**
 * Metrics about a Window, consisting of the bounds and {@link WindowInsets}.
 * <p>
 * This is usually obtained from {@link WindowManager#getCurrentWindowMetrics()} and
 * {@link WindowManager#getMaximumWindowMetrics()}.
 * </p>
 * After {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, it also provides density.
 * <h3>Obtains Window Dimensions in Density-independent Pixel(DP)</h3>
 * <p>
 * While {@link #getDensity()} is provided, the dimension in density-independent pixel could also be
 * calculated with {@code WindowMetrics} properties, which is similar to
 * {@link android.content.res.Configuration#screenWidthDp}
 * <pre class="prettyprint">
 * float widthInDp = windowMetrics.getBounds().width() / windowMetrics.getDensity();
 * float heightInDp = windowMetrics.getBounds().height() / windowMetrics.getDensity();
 * </pre>
 * Also, the density in DPI can be obtained by:
 * <pre class="prettyprint">
 * float densityDp = DisplayMetrics.DENSITY_DEFAULT * windowMetrics.getDensity();
 * </pre>
 * </p>
 *
 * @see WindowInsets#getInsets(int)
 * @see WindowManager#getCurrentWindowMetrics()
 * @see WindowManager#getMaximumWindowMetrics()
 */
public final class WindowMetrics {
    @NonNull
    private final Rect mBounds;

    private WindowInsets mWindowInsets;
    private Supplier<WindowInsets> mWindowInsetsSupplier;

    /** @see android.util.DisplayMetrics#density */
    private final float mDensity;

    /** @deprecated use {@link #WindowMetrics(Rect, WindowInsets, float)} instead. */
    @Deprecated
    public WindowMetrics(@NonNull Rect bounds, @NonNull WindowInsets windowInsets) {
        this(bounds, windowInsets, 1.0f);
    }

    /**
     * The constructor to create a {@link WindowMetrics} instance.
     * <p>
     * Note that in most cases {@link WindowMetrics} is obtained from
     * {@link WindowManager#getCurrentWindowMetrics()} or
     * {@link WindowManager#getMaximumWindowMetrics()}.
     * </p>
     *
     * @param bounds The window bounds
     * @param windowInsets The {@link WindowInsets} of the window
     * @param density The window density
     */
    public WindowMetrics(@NonNull Rect bounds, @NonNull WindowInsets windowInsets, float density) {
        mBounds = bounds;
        mWindowInsets = windowInsets;
        mDensity = density;
    }

    /**
     * Similar to {@link #WindowMetrics(Rect, WindowInsets, float)} but the window insets are
     * computed when {@link #getWindowInsets()} is first time called. This reduces unnecessary
     * calculation and the overhead of obtaining insets state from server side because most
     * callers are usually only interested in {@link #getBounds()}.
     *
     * @hide
     */
    public WindowMetrics(@NonNull Rect bounds, @NonNull Supplier<WindowInsets> windowInsetsSupplier,
            float density) {
        mBounds = bounds;
        mWindowInsetsSupplier = windowInsetsSupplier;
        mDensity = density;
    }

    /**
     * Returns the bounds of the area associated with this window or {@code UiContext}.
     * <p>
     * <b>Note that the size of the reported bounds can have different size than
     * {@link Display#getSize(Point)} based on your target API level and calling context.</b>
     * This method reports the window size including all system
     * bar areas, while {@link Display#getSize(Point)} can report the area excluding navigation bars
     * and display cutout areas depending on the calling context and target SDK level. Please refer
     * to {@link Display#getSize(Point)} for details.
     * <p>
     * The value reported by {@link Display#getSize(Point)} excluding system decoration areas can be
     * obtained by using:
     * <pre class="prettyprint">
     * final WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
     * // Gets all excluding insets
     * final WindowInsets windowInsets = metrics.getWindowInsets();
     * Insets insets = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()
     *         | WindowInsets.Type.displayCutout());
     *
     * int insetsWidth = insets.right + insets.left;
     * int insetsHeight = insets.top + insets.bottom;
     *
     * // Legacy size that Display#getSize reports
     * final Rect bounds = metrics.getBounds();
     * final Size legacySize = new Size(bounds.width() - insetsWidth,
     *         bounds.height() - insetsHeight);
     * </pre>
     * </p>
     *
     * @return window bounds in pixels.
     */
    @NonNull
    public Rect getBounds() {
        return mBounds;
    }

    /**
     * Returns the {@link WindowInsets} of the area associated with this window or
     * {@code UiContext}.
     *
     * @return the {@link WindowInsets} of the visual area.
     */
    @NonNull
    public WindowInsets getWindowInsets() {
        if (mWindowInsets != null) {
            return mWindowInsets;
        }
        return mWindowInsets = mWindowInsetsSupplier.get();
    }

    /**
     * Returns the density of the area associated with this window or {@code UiContext},
     * which uses the same units as {@link android.util.DisplayMetrics#density}.
     *
     * @see android.util.DisplayMetrics#DENSITY_DEFAULT
     * @see android.util.DisplayMetrics#density
     */
    public float getDensity() {
        return mDensity;
    }

    @Override
    public String toString() {
        return WindowMetrics.class.getSimpleName() + ":{"
                + "bounds=" + mBounds
                + ", windowInsets=" + mWindowInsets
                + ", density=" + mDensity
                + "}";
    }
}
