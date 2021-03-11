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

import java.util.Random;

/**
 * Represents a centroid in Kmeans algorithms.
 */
public class Mean {
    public float[] center;

    /**
     * Constructor.
     *
     * @param upperBound maximum value of a dimension in the space Kmeans is optimizing in
     * @param random used to generate a random center
     */
    Mean(int upperBound, Random random) {
        center =
                new float[]{
                        random.nextInt(upperBound + 1), random.nextInt(upperBound + 1),
                        random.nextInt(upperBound + 1)
                };
    }

    Mean(float[] center) {
        this.center = center;
    }
}
