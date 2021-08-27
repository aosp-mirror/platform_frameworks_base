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

package com.android.internal.graphics.palette;

import android.annotation.ColorInt;

/**
 * Interface that allows quantizers to have a plug-and-play interface for experimenting with
 * quantization in different color spaces.
 */
public interface PointProvider {
    /** Convert a color to 3 coordinates representing the color in a color space. */
    float[] fromInt(@ColorInt int argb);

    /** Convert 3 coordinates in the color space into a color */
    @ColorInt
    int toInt(float[] point);

    /** Find the distance between two colosrin the color space */
    float distance(float[] a, float[] b);
}
