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

import java.util.HashSet;
import java.util.Set;

class MeanBucket {
    float[] mTotal = {0.f, 0.f, 0.f};
    int mCount = 0;
    Set<Integer> mColors = new HashSet<>();

    void add(float[] colorAsDoubles, int color, int colorCount) {
        assert (colorAsDoubles.length == 3);
        mColors.add(color);
        mTotal[0] += (colorAsDoubles[0] * colorCount);
        mTotal[1] += (colorAsDoubles[1] * colorCount);
        mTotal[2] += (colorAsDoubles[2] * colorCount);
        mCount += colorCount;
    }

    float[] getCentroid() {
        if (mCount == 0) {
            return null;
        }
        return new float[]{mTotal[0] / mCount, mTotal[1] / mCount, mTotal[2] / mCount};
    }
}
