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

interface CentroidProvider {
    /**
     * @return 3 dimensions representing the color
     */
    float[] getCentroid(@ColorInt int color);

    /**
     * @param centroid 3 dimensions representing the color
     * @return 32-bit ARGB representation
     */
    @ColorInt
    int getColor(float[] centroid);

    /**
     * Distance between two centroids.
     */
    float distance(float[] a, float[] b);
}
