/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics;

public class ColorMatrixColorFilter extends ColorFilter {
    /**
     * Create a colorfilter that transforms colors through a 4x5 color matrix.
     *
     * @param matrix 4x5 matrix used to transform colors. It is copied into
     *               the filter, so changes made to the matrix after the filter
     *               is constructed will not be reflected in the filter.
     */
    public ColorMatrixColorFilter(ColorMatrix matrix) {
        final float[] colorMatrix = matrix.getArray();
        native_instance = nativeColorMatrixFilter(colorMatrix);
        nativeColorFilter = nColorMatrixFilter(native_instance, colorMatrix);
    }

    /**
    * Create a colorfilter that transforms colors through a 4x5 color matrix.
     *
     * @param array array of floats used to transform colors, treated as a 4x5
     *              matrix. The first 20 entries of the array are copied into
     *              the filter. See ColorMatrix.
     */
    public ColorMatrixColorFilter(float[] array) {
        if (array.length < 20) {
            throw new ArrayIndexOutOfBoundsException();
        }
        native_instance = nativeColorMatrixFilter(array);
        nativeColorFilter = nColorMatrixFilter(native_instance, array);
    }

    private static native int nativeColorMatrixFilter(float[] array);
    private static native int nColorMatrixFilter(int nativeFilter, float[] array);
}
