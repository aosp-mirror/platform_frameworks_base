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

import android.annotation.NonNull;
import android.util.IntArray;

import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 */
public final class DisplayLuts {
    private IntArray mOffsets;
    private int mTotalLength;

    private List<float[]> mLutBuffers;
    private IntArray mLutDimensions;
    private IntArray mLutSizes;
    private IntArray mLutSamplingKeys;
    private static final int LUT_LENGTH_LIMIT = 100000;

    public DisplayLuts() {
        mOffsets = new IntArray();
        mTotalLength = 0;

        mLutBuffers = new ArrayList<>();
        mLutDimensions = new IntArray();
        mLutSizes = new IntArray();
        mLutSamplingKeys = new IntArray();
    }

    /**
     * Add the lut to be applied.
     *
     * @param buffer
     * @param dimension either 1D or 3D
     * @param size
     * @param samplingKey
     */
    public void addLut(@NonNull float[] buffer, @LutProperties.Dimension int dimension,
                       int size, @LutProperties.SamplingKey int samplingKey) {

        int lutLength = 0;
        if (dimension == LutProperties.ONE_DIMENSION) {
            lutLength = size;
        } else if (dimension == LutProperties.THREE_DIMENSION) {
            lutLength = size * size * size;
        } else {
            clear();
            throw new IllegalArgumentException("The dimension is either 1D or 3D!");
        }

        if (lutLength >= LUT_LENGTH_LIMIT) {
            clear();
            throw new IllegalArgumentException("The lut length is too big to handle!");
        }

        mOffsets.add(mTotalLength);
        mTotalLength += lutLength;

        mLutBuffers.add(buffer);
        mLutDimensions.add(dimension);
        mLutSizes.add(size);
        mLutSamplingKeys.add(samplingKey);
    }

    private void clear() {
        mTotalLength = 0;
        mOffsets.clear();
        mLutBuffers.clear();
        mLutDimensions.clear();
        mLutSamplingKeys.clear();
    }

    /**
     * @return the array of Lut buffers
     */
    public float[] getLutBuffers() {
        float[] buffer = new float[mTotalLength];

        for (int i = 0; i < mLutBuffers.size(); i++) {
            float[] lutBuffer = mLutBuffers.get(i);
            System.arraycopy(lutBuffer, 0, buffer, mOffsets.get(i), lutBuffer.length);
        }
        return buffer;
    }

    /**
     * @return the starting point of each lut memory region of the lut buffer
     */
    public int[] getOffsets() {
        return mOffsets.toArray();
    }

    /**
     * @return the array of Lut size
     */
    public int[] getLutSizes() {
        return mLutSizes.toArray();
    }

    /**
     * @return the array of Lut dimension
     */
    public int[] getLutDimensions() {
        return mLutDimensions.toArray();
    }

    /**
     * @return the array of sampling key
     */
    public int[] getLutSamplingKeys() {
        return mLutSamplingKeys.toArray();
    }
}
