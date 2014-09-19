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

/**
 * A color filter that transforms colors through a 4x5 color matrix. This filter
 * can be used to change the saturation of pixels, convert from YUV to RGB, etc.
 *
 * @see ColorMatrix
 */
public class ColorMatrixColorFilter extends ColorFilter {
    private final ColorMatrix mMatrix = new ColorMatrix();

    /**
     * Create a color filter that transforms colors through a 4x5 color matrix.
     *
     * @param matrix 4x5 matrix used to transform colors. It is copied into
     *               the filter, so changes made to the matrix after the filter
     *               is constructed will not be reflected in the filter.
     */
    public ColorMatrixColorFilter(ColorMatrix matrix) {
        mMatrix.set(matrix);
        update();
    }

    /**
     * Create a color filter that transforms colors through a 4x5 color matrix.
     *
     * @param array Array of floats used to transform colors, treated as a 4x5
     *              matrix. The first 20 entries of the array are copied into
     *              the filter. See ColorMatrix.
     */
    public ColorMatrixColorFilter(float[] array) {
        if (array.length < 20) {
            throw new ArrayIndexOutOfBoundsException();
        }
        mMatrix.set(array);
        update();
    }

    /**
     * Returns the {@link ColorMatrix} used by this filter. The returned
     * value is never null. Modifying the returned matrix does not have
     * any effect until you call {@link #setColorMatrix(ColorMatrix)}.
     *
     * @see #setColorMatrix(ColorMatrix)
     *
     * @hide
     */
    public ColorMatrix getColorMatrix() {
        return mMatrix;
    }

    /**
     * Specifies the color matrix used by this filter. If the specified
     * color matrix is null, this filter's color matrix will be reset to
     * the identity matrix.
     *
     * @param matrix A {@link ColorMatrix} or null
     *
     * @see #getColorMatrix()
     * @see android.graphics.ColorMatrix#reset()
     * @see #setColorMatrix(float[])
     *
     * @hide
     */
    public void setColorMatrix(ColorMatrix matrix) {
        if (matrix == null) {
            mMatrix.reset();
        } else if (matrix != mMatrix) {
            mMatrix.set(matrix);
        }
        update();
    }

    /**
     * Specifies the color matrix used by this filter. If the specified
     * color matrix is null, this filter's color matrix will be reset to
     * the identity matrix.
     *
     * @param array Array of floats used to transform colors, treated as a 4x5
     *              matrix. The first 20 entries of the array are copied into
     *              the filter. See {@link ColorMatrix}.
     *
     * @see #getColorMatrix()
     * @see android.graphics.ColorMatrix#reset()
     * @see #setColorMatrix(ColorMatrix)
     *
     * @throws ArrayIndexOutOfBoundsException if the specified array's
     *         length is < 20
     *
     * @hide
     */
    public void setColorMatrix(float[] array) {
        if (array == null) {
            mMatrix.reset();
        } else {
            if (array.length < 20) {
                throw new ArrayIndexOutOfBoundsException();
            }

            mMatrix.set(array);
        }
        update();
    }

    private void update() {
        final float[] colorMatrix = mMatrix.getArray();
        destroyFilter(native_instance);
        native_instance = nativeColorMatrixFilter(colorMatrix);
    }

    private static native long nativeColorMatrixFilter(float[] array);
}
