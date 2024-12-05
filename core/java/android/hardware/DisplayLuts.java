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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.hardware.flags.Flags;
import android.util.IntArray;

import java.util.ArrayList;

/**
 * DisplayLuts provides the developers to apply Lookup Tables (Luts) to a
 * {@link android.view.SurfaceControl}. Luts provides ways to control tonemapping
 * for specific content.
 *
 * The general flow is as follows:
 * <p>
 *      <img src="{@docRoot}reference/android/images/graphics/DisplayLuts.png" />
 *      <figcaption style="text-align: center;">DisplayLuts flow</figcaption>
 * </p>
 *
 * @see LutProperties
 */
@FlaggedApi(Flags.FLAG_LUTS_API)
public final class DisplayLuts {
    private ArrayList<Entry> mEntries;
    private IntArray mOffsets;
    private int mTotalLength;

    /**
     * Create a {@link DisplayLuts} instance.
     */
    @FlaggedApi(Flags.FLAG_LUTS_API)
    public DisplayLuts() {
        mEntries = new ArrayList<>();
        mOffsets = new IntArray();
        mTotalLength = 0;
    }

    @FlaggedApi(Flags.FLAG_LUTS_API)
    public static class Entry {
        private float[] mBuffer;
        private @LutProperties.Dimension int mDimension;
        private int mSize;
        private @LutProperties.SamplingKey int mSamplingKey;

        private static final int LUT_LENGTH_LIMIT = 100000;

        /**
         * Create a Lut entry.
         *
         * <p>
         * Noted that 1D Lut(s) are treated as gain curves.
         * For 3D Lut(s), 3D Lut(s) are used for direct color manipulations.
         * The values of 3D Lut(s) data should be normalized to the range {@code 0.0}
         * to {@code 1.0}, inclusive. And 3D Lut(s) data is organized in the order of
         * R, G, B channels.
         *
         * @param buffer The raw lut data
         * @param dimension Either 1D or 3D
         * @param samplingKey The sampling kay used for the Lut
         */
        @FlaggedApi(Flags.FLAG_LUTS_API)
        public Entry(@NonNull float[] buffer,
                    @LutProperties.Dimension int dimension,
                    @LutProperties.SamplingKey int samplingKey) {
            if (buffer == null || buffer.length < 1) {
                throw new IllegalArgumentException("The buffer cannot be empty!");
            }

            if (buffer.length >= LUT_LENGTH_LIMIT) {
                throw new IllegalArgumentException("The lut length is too big to handle!");
            }

            if (dimension != LutProperties.ONE_DIMENSION
                    && dimension != LutProperties.THREE_DIMENSION) {
                throw new IllegalArgumentException("The dimension should be either 1D or 3D!");
            }

            if (dimension == LutProperties.THREE_DIMENSION) {
                if (buffer.length <= 3) {
                    throw new IllegalArgumentException(
                            "The 3d lut size of each dimension should be over 1!");
                }
                int lengthPerChannel = buffer.length;
                if (lengthPerChannel % 3 != 0) {
                    throw new IllegalArgumentException(
                            "The lut buffer of 3dlut should have 3 channels!");
                }
                lengthPerChannel /= 3;

                double size = Math.cbrt(lengthPerChannel);
                if (size == (int) size) {
                    mSize = (int) size;
                } else {
                    throw new IllegalArgumentException(
                            "Cannot get the cube root of the 3d lut buffer!");
                }
            } else {
                mSize = buffer.length;
            }

            mBuffer = buffer;
            mDimension = dimension;
            mSamplingKey = samplingKey;
        }

        /**
         * @return the dimension of the lut entry
         */
        @FlaggedApi(Flags.FLAG_LUTS_API)
        public int getDimension() {
            return mDimension;
        }

        /**
         * @return the size of the lut for each dimension
         * @hide
         */
        public int getSize() {
            return mSize;
        }

        /**
         * @return the lut raw data of the lut
         */
        @FlaggedApi(Flags.FLAG_LUTS_API)
        public @NonNull float[] getBuffer() {
            return mBuffer;
        }

        /**
         * @return the sampling key used by the lut
         */
        @FlaggedApi(Flags.FLAG_LUTS_API)
        public int getSamplingKey() {
            return mSamplingKey;
        }

        @Override
        public String toString() {
            return "Entry{"
                    + "dimension=" + DisplayLuts.Entry.dimensionToString(getDimension())
                    + ", size(each dimension)=" + getSize()
                    + ", samplingKey=" + samplingKeyToString(getSamplingKey()) + "}";
        }

        private static String dimensionToString(int dimension) {
            switch(dimension) {
                case LutProperties.ONE_DIMENSION:
                    return "ONE_DIMENSION";
                case LutProperties.THREE_DIMENSION:
                    return "THREE_DIMENSION";
                default:
                    return "";
            }
        }

        private static String samplingKeyToString(int key) {
            switch(key) {
                case LutProperties.SAMPLING_KEY_RGB:
                    return "SAMPLING_KEY_RGB";
                case LutProperties.SAMPLING_KEY_MAX_RGB:
                    return "SAMPLING_KEY_MAX_RGB";
                case LutProperties.SAMPLING_KEY_CIE_Y:
                    return "SAMPLING_KEY_CIE_Y";
                default:
                    return "";
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DisplayLuts{");
        sb.append("\n");
        for (DisplayLuts.Entry entry: mEntries) {
            sb.append(entry.toString());
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private void addEntry(Entry entry) {
        mEntries.add(entry);
        mOffsets.add(mTotalLength);
        mTotalLength += entry.getBuffer().length;
    }

    private void clear() {
        mOffsets.clear();
        mTotalLength = 0;
        mEntries.clear();
    }

    /**
     * Set a Lut to be applied.
     *
     * <p>Use either this or {@link #set(Entry, Entry)}. The function will
     * replace any previously set lut(s).</p>
     *
     * @param entry Either an 1D Lut or a 3D Lut
     */
    @FlaggedApi(Flags.FLAG_LUTS_API)
    public void set(@NonNull Entry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("The entry is null!");
        }
        clear();
        addEntry(entry);
    }

    /**
     * Set Luts in order to be applied.
     *
     * <p> An 1D Lut and 3D Lut will be applied in order. Use either this or
     * {@link #set(Entry)}. The function will replace any previously set lut(s)</p>
     *
     * @param first An 1D Lut
     * @param second A 3D Lut
     */
    @FlaggedApi(Flags.FLAG_LUTS_API)
    public void set(@NonNull Entry first, @NonNull Entry second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("The entry is null!");
        }
        if (first.getDimension() != LutProperties.ONE_DIMENSION
                || second.getDimension() != LutProperties.THREE_DIMENSION) {
            throw new IllegalArgumentException("The entries should be 1D and 3D in order!");
        }
        clear();
        addEntry(first);
        addEntry(second);
    }

    /**
     * @hide
     */
    public boolean valid() {
        return mEntries.size() > 0;
    }

    /**
     * @hide
     */
    public float[] getLutBuffers() {
        float[] buffer = new float[mTotalLength];

        for (int i = 0; i < mEntries.size(); i++) {
            float[] lutBuffer = mEntries.get(i).getBuffer();
            System.arraycopy(lutBuffer, 0, buffer, mOffsets.get(i), lutBuffer.length);
        }
        return buffer;
    }

    /**
     * @hide
     */
    public int[] getOffsets() {
        return mOffsets.toArray();
    }

    /**
     * @hide
     */
    public int[] getLutSizes() {
        int[] sizes = new int[mEntries.size()];
        for (int i = 0; i < mEntries.size(); i++) {
            sizes[i] = mEntries.get(i).getSize();
        }
        return sizes;
    }

    /**
     * @hide
     */
    public int[] getLutDimensions() {
        int[] dimensions = new int[mEntries.size()];
        for (int i = 0; i < mEntries.size(); i++) {
            dimensions[i] = mEntries.get(i).getDimension();
        }
        return dimensions;
    }

    /**
     * @hide
     */
    public int[] getLutSamplingKeys() {
        int[] samplingKeys = new int[mEntries.size()];
        for (int i = 0; i < mEntries.size(); i++) {
            samplingKeys[i] = mEntries.get(i).getSamplingKey();
        }
        return samplingKeys;
    }
}
