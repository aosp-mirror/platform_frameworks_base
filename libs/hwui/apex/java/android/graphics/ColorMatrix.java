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

import java.util.Arrays;

/**
 * 4x5 matrix for transforming the color and alpha components of a Bitmap.
 * The matrix can be passed as single array, and is treated as follows:
 *
 * <pre>
 *  [ a, b, c, d, e,
 *    f, g, h, i, j,
 *    k, l, m, n, o,
 *    p, q, r, s, t ]</pre>
 *
 * <p>
 * When applied to a color <code>[R, G, B, A]</code>, the resulting color
 * is computed as:
 * </p>
 *
 * <pre>
 *   R&rsquo; = a*R + b*G + c*B + d*A + e;
 *   G&rsquo; = f*R + g*G + h*B + i*A + j;
 *   B&rsquo; = k*R + l*G + m*B + n*A + o;
 *   A&rsquo; = p*R + q*G + r*B + s*A + t;</pre>
 *
 * <p>
 * That resulting color <code>[R&rsquo;, G&rsquo;, B&rsquo;, A&rsquo;]</code>
 * then has each channel clamped to the <code>0</code> to <code>255</code>
 * range.
 * </p>
 *
 * <p>
 * The sample ColorMatrix below inverts incoming colors by scaling each
 * channel by <code>-1</code>, and then shifting the result up by
 * <code>255</code> to remain in the standard color space.
 * </p>
 *
 * <pre>
 *   [ -1, 0, 0, 0, 255,
 *     0, -1, 0, 0, 255,
 *     0, 0, -1, 0, 255,
 *     0, 0, 0, 1, 0 ]</pre>
 */
@SuppressWarnings({ "MismatchedReadAndWriteOfArray", "PointlessArithmeticExpression" })
public class ColorMatrix {
    private final float[] mArray = new float[20];

    /**
     * Create a new colormatrix initialized to identity (as if reset() had
     * been called).
     */
    public ColorMatrix() {
        reset();
    }

    /**
     * Create a new colormatrix initialized with the specified array of values.
     */
    public ColorMatrix(float[] src) {
        System.arraycopy(src, 0, mArray, 0, 20);
    }

    /**
     * Create a new colormatrix initialized with the specified colormatrix.
     */
    public ColorMatrix(ColorMatrix src) {
        System.arraycopy(src.mArray, 0, mArray, 0, 20);
    }

    /**
     * Return the array of floats representing this colormatrix.
     */
    public final float[] getArray() { return mArray; }

    /**
     * Set this colormatrix to identity:
     * <pre>
     * [ 1 0 0 0 0   - red vector
     *   0 1 0 0 0   - green vector
     *   0 0 1 0 0   - blue vector
     *   0 0 0 1 0 ] - alpha vector
     * </pre>
     */
    public void reset() {
        final float[] a = mArray;
        Arrays.fill(a, 0);
        a[0] = a[6] = a[12] = a[18] = 1;
    }

    /**
     * Assign the src colormatrix into this matrix, copying all of its values.
     */
    public void set(ColorMatrix src) {
        System.arraycopy(src.mArray, 0, mArray, 0, 20);
    }

    /**
     * Assign the array of floats into this matrix, copying all of its values.
     */
    public void set(float[] src) {
        System.arraycopy(src, 0, mArray, 0, 20);
    }

    /**
     * Set this colormatrix to scale by the specified values.
     */
    public void setScale(float rScale, float gScale, float bScale,
                         float aScale) {
        final float[] a = mArray;

        for (int i = 19; i > 0; --i) {
            a[i] = 0;
        }
        a[0] = rScale;
        a[6] = gScale;
        a[12] = bScale;
        a[18] = aScale;
    }

    /**
     * Set the rotation on a color axis by the specified values.
     * <p>
     * <code>axis=0</code> correspond to a rotation around the RED color
     * <code>axis=1</code> correspond to a rotation around the GREEN color
     * <code>axis=2</code> correspond to a rotation around the BLUE color
     * </p>
     */
    public void setRotate(int axis, float degrees) {
        reset();
        double radians = degrees * Math.PI / 180d;
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        switch (axis) {
        // Rotation around the red color
        case 0:
            mArray[6] = mArray[12] = cosine;
            mArray[7] = sine;
            mArray[11] = -sine;
            break;
        // Rotation around the green color
        case 1:
            mArray[0] = mArray[12] = cosine;
            mArray[2] = -sine;
            mArray[10] = sine;
            break;
        // Rotation around the blue color
        case 2:
            mArray[0] = mArray[6] = cosine;
            mArray[1] = sine;
            mArray[5] = -sine;
            break;
        default:
            throw new RuntimeException();
        }
    }

    /**
     * Set this colormatrix to the concatenation of the two specified
     * colormatrices, such that the resulting colormatrix has the same effect
     * as applying matB and then applying matA.
     * <p>
     * It is legal for either matA or matB to be the same colormatrix as this.
     * </p>
     */
    public void setConcat(ColorMatrix matA, ColorMatrix matB) {
        float[] tmp;
        if (matA == this || matB == this) {
            tmp = new float[20];
        } else {
            tmp = mArray;
        }

        final float[] a = matA.mArray;
        final float[] b = matB.mArray;
        int index = 0;
        for (int j = 0; j < 20; j += 5) {
            for (int i = 0; i < 4; i++) {
                tmp[index++] = a[j + 0] * b[i + 0] +  a[j + 1] * b[i + 5] +
                               a[j + 2] * b[i + 10] + a[j + 3] * b[i + 15];
            }
            tmp[index++] = a[j + 0] * b[4] +  a[j + 1] * b[9] +
                           a[j + 2] * b[14] + a[j + 3] * b[19] +
                           a[j + 4];
        }

        if (tmp != mArray) {
            System.arraycopy(tmp, 0, mArray, 0, 20);
        }
    }

    /**
     * Concat this colormatrix with the specified prematrix.
     * <p>
     * This is logically the same as calling setConcat(this, prematrix);
     * </p>
     */
    public void preConcat(ColorMatrix prematrix) {
        setConcat(this, prematrix);
    }

    /**
     * Concat this colormatrix with the specified postmatrix.
     * <p>
     * This is logically the same as calling setConcat(postmatrix, this);
     * </p>
     */
    public void postConcat(ColorMatrix postmatrix) {
        setConcat(postmatrix, this);
    }

    ///////////////////////////////////////////////////////////////////////////

    /**
     * Set the matrix to affect the saturation of colors.
     *
     * @param sat A value of 0 maps the color to gray-scale. 1 is identity.
     */
    public void setSaturation(float sat) {
        reset();
        float[] m = mArray;

        final float invSat = 1 - sat;
        final float R = 0.213f * invSat;
        final float G = 0.715f * invSat;
        final float B = 0.072f * invSat;

        m[0] = R + sat; m[1] = G;       m[2] = B;
        m[5] = R;       m[6] = G + sat; m[7] = B;
        m[10] = R;      m[11] = G;      m[12] = B + sat;
    }

    /**
     * Set the matrix to convert RGB to YUV
     */
    public void setRGB2YUV() {
        reset();
        float[] m = mArray;
        // these coefficients match those in libjpeg
        m[0]  = 0.299f;    m[1]  = 0.587f;    m[2]  = 0.114f;
        m[5]  = -0.16874f; m[6]  = -0.33126f; m[7]  = 0.5f;
        m[10] = 0.5f;      m[11] = -0.41869f; m[12] = -0.08131f;
    }

    /**
     * Set the matrix to convert from YUV to RGB
     */
    public void setYUV2RGB() {
        reset();
        float[] m = mArray;
        // these coefficients match those in libjpeg
                                        m[2] = 1.402f;
        m[5] = 1;   m[6] = -0.34414f;   m[7] = -0.71414f;
        m[10] = 1;  m[11] = 1.772f;     m[12] = 0;
    }

    @Override
    public boolean equals(Object obj) {
        // if (obj == this) return true; -- NaN value would mean matrix != itself
        if (!(obj instanceof ColorMatrix)) {
            return false;
        }

        // we don't use Arrays.equals(), since that considers NaN == NaN
        final float[] other = ((ColorMatrix) obj).mArray;
        for (int i = 0; i < 20; i++) {
            if (other[i] != mArray[i]) {
                return false;
            }
        }
        return true;
    }
}
