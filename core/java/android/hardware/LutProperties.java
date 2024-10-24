/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Lut properties class.
 *
 * A Lut (Look-Up Table) is a pre-calculated table for color transformation.
 *
 * @hide
 */
public final class LutProperties {
    private final @Dimension int mDimension;
    private final int mSize;
    private final @SamplingKey int[] mSamplingKeys;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SAMPLING_KEY_"}, value = {
        SAMPLING_KEY_RGB,
        SAMPLING_KEY_MAX_RGB
    })
    public @interface SamplingKey {
    }

    /** use r,g,b channel as the gain value of a Lut */
    public static final int SAMPLING_KEY_RGB = 0;

    /** use max of r,g,b channel as the gain value of a Lut */
    public static final int SAMPLING_KEY_MAX_RGB = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
        ONE_DIMENSION,
        THREE_DIMENSION
    })
    public @interface Dimension {
    }

    /** The Lut is one dimensional */
    public static final int ONE_DIMENSION = 1;

    /** The Lut is three dimensional */
    public static final int THREE_DIMENSION = 3;

    public @Dimension int getDimension() {
        return mDimension;
    }

    /**
     * @return the size of the Lut.
     */
    public int getSize() {
        return mSize;
    }

    /**
     * @return the list of sampling keys
     */
    public @SamplingKey int[] getSamplingKeys() {
        if (mSamplingKeys.length == 0) {
            throw new IllegalStateException("no sampling key!");
        }
        return mSamplingKeys;
    }

    /* use in the native code */
    private LutProperties(@Dimension int dimension, int size, @SamplingKey int[] samplingKeys) {
        if (dimension != ONE_DIMENSION || dimension != THREE_DIMENSION) {
            throw new IllegalArgumentException("The dimension is either 1 or 3!");
        }
        mDimension = dimension;
        mSize = size;
        mSamplingKeys = samplingKeys;
    }
}
