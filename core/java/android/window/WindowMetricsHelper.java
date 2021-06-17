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

package android.window;

import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.navigationBars;

import android.annotation.NonNull;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowMetrics;

/**
 * Helper class to calculate size with {@link android.graphics.Insets} based on provided
 * {@link WindowMetrics}
 *
 * @hide
 */
public final class WindowMetricsHelper {
    private WindowMetricsHelper() {}

    /**
     * Returns bounds excluding navigation bar and display cutout (but including status bar).
     * This has the same behavior as {@link Display#getSize(Point)}.
     */
    public static Rect getBoundsExcludingNavigationBarAndCutout(
            @NonNull WindowMetrics windowMetrics) {
        final WindowInsets windowInsets = windowMetrics.getWindowInsets();
        final Rect result = new Rect(windowMetrics.getBounds());
        result.inset(windowInsets.getInsetsIgnoringVisibility(navigationBars() | displayCutout()));
        return result;
    }
}
