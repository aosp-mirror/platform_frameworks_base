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

package com.android.server.display.config;

import android.annotation.Nullable;
import android.util.Slog;
import android.util.Spline;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

public class DisplayDeviceConfigUtils {
    private static final String TAG = "DisplayDeviceConfigUtils";

    /**
     * Create Spline from generic data
     * @param points - points for Spline in format (x0, y0), (x1, y1) etc
     * @param xExtractor - extract X component from generic data
     * @param yExtractor - extract Y component from generic data
     */
    @Nullable
    public static <T> Spline createSpline(List<T> points, Function<T, BigDecimal> xExtractor,
            Function<T, BigDecimal> yExtractor) {
        int size = points.size();
        if (size == 0) {
            return null;
        }

        float[] x = new float[size];
        float[] y = new float[size];

        int i = 0;
        for (T point : points) {
            x[i] = xExtractor.apply(point).floatValue();
            if (i > 0) {
                if (x[i] <= x[i - 1]) {
                    Slog.e(TAG, "spline control points must be strictly increasing, ignoring "
                            + "configuration. x: " + x[i] + " <= " + x[i - 1]);
                    return null;
                }
            }
            y[i] = yExtractor.apply(point).floatValue();
            ++i;
        }

        return Spline.createSpline(x, y);
    }

    /**
     * Get the highest HDR/SDR ratio from the given map.
     * @param points The map of brightness values to HDR/SDR ratios
     * @param extractor Used to retrieve the ratio from the map element
     * @return The highest HDR/SDR ratio
     * @param <T> The type of the map elements
     */
    public static <T> float getHighestHdrSdrRatio(List<T> points,
            Function<T, BigDecimal> extractor) {
        float highestRatio = 1;
        for (T point : points) {
            float ratio = extractor.apply(point).floatValue();
            if (ratio > highestRatio) {
                highestRatio = ratio;
            }
        }
        return highestRatio;
    }
}
