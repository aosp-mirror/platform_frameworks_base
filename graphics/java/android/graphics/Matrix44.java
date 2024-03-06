/*
 * Copyright 2023 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;

import com.android.graphics.hwui.flags.Flags;

import java.util.Arrays;

/**
 * The Matrix44 class holds a 4x4 matrix for transforming coordinates. It is similar to
 * {@link Matrix}, and should be used when you want to manipulate the canvas in 3D. Values are kept
 * in row-major order. The values and operations are treated as column vectors.
 */
@FlaggedApi(Flags.FLAG_MATRIX_44)
public class Matrix44 {
    final float[] mBackingArray;
    /**
     * The default Matrix44 constructor will instantiate an identity matrix.
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public Matrix44() {
        mBackingArray = new float[]{1.0f, 0.0f, 0.0f, 0.0f,
                                    0.0f, 1.0f, 0.0f, 0.0f,
                                    0.0f, 0.0f, 1.0f, 0.0f,
                                    0.0f, 0.0f, 0.0f, 1.0f};
    }

    /**
     * Creates and returns a Matrix44 by taking the 3x3 Matrix and placing it on the 0 of the z-axis
     * by setting row {@code 2} and column {@code 2} to the identity as seen in the following
     * operation:
     * <pre class="prettyprint">
     * [ a b c ]      [ a b 0 c ]
     * [ d e f ]  ->  [ d e 0 f ]
     * [ g h i ]      [ 0 0 1 0 ]
     *                [ g h 0 i ]
     * </pre>
     *
     * @param mat A 3x3 Matrix to be converted (original Matrix will not be changed)
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public Matrix44(@NonNull Matrix mat) {
        float[] m = new float[9];
        mat.getValues(m);
        mBackingArray = new float[]{m[0], m[1], 0.0f, m[2],
                                    m[3], m[4], 0.0f, m[5],
                                    0.0f, 0.0f, 1.0f, 0.0f,
                                    m[6], m[7], 0.0f, m[8]};
    }

    /**
     * Copies matrix values into the provided array in row-major order.
     *
     * @param dst The float array where values will be copied, must be of length 16
     * @throws IllegalArgumentException if the destination float array is not of length 16
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public void getValues(@NonNull float [] dst) {
        if (dst.length == 16) {
            System.arraycopy(mBackingArray, 0, dst, 0, mBackingArray.length);
        } else {
            throw new IllegalArgumentException("Dst array must be of length 16");
        }
    }

    /**
     * Replaces the Matrix's values with the values in the provided array.
     *
     * @param src A float array of length 16. Floats are treated in row-major order
     * @throws IllegalArgumentException if the destination float array is not of length 16
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public void setValues(@NonNull float[] src) {
        if (src.length == 16) {
            System.arraycopy(src, 0, mBackingArray, 0, mBackingArray.length);
        } else {
            throw new IllegalArgumentException("Src array must be of length 16");
        }
    }

    /**
     * Gets the value at the matrix's row and column.
     *
     * @param row An integer from 0 to 3 indicating the row of the value to get
     * @param col An integer from 0 to 3 indicating the column of the value to get
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public float get(@IntRange(from = 0, to = 3) int row, @IntRange(from = 0, to = 3) int col) {
        if (row >= 0 && row < 4 && col >= 0 && col < 4) {
            return mBackingArray[row * 4 + col];
        }
        throw new IllegalArgumentException("invalid row and column values");
    }

    /**
     * Sets the value at the matrix's row and column to the provided value.
     *
     * @param row An integer from 0 to 3 indicating the row of the value to change
     * @param col An integer from 0 to 3 indicating the column of the value to change
     * @param val The value the element at the specified index will be set to
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public void set(@IntRange(from = 0, to = 3) int row, @IntRange(from = 0, to = 3) int col,
            float val) {
        if (row >= 0 && row < 4 && col >= 0 && col < 4) {
            mBackingArray[row * 4 + col] = val;
        } else {
            throw new IllegalArgumentException("invalid row and column values");
        }
    }

    /**
     * Sets the Matrix44 to the identity matrix.
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public void reset() {
        for (int i = 0; i < mBackingArray.length; i++) {
            mBackingArray[i] = (i % 4 == i / 4) ? 1.0f : 0.0f;
        }
    }

    /**
     * Inverts the Matrix44, then return true if successful, false if unable to invert.
     *
     * @return {@code true} on success, {@code false} otherwise
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public boolean invert() {
        float a00 = mBackingArray[0];
        float a01 = mBackingArray[1];
        float a02 = mBackingArray[2];
        float a03 = mBackingArray[3];
        float a10 = mBackingArray[4];
        float a11 = mBackingArray[5];
        float a12 = mBackingArray[6];
        float a13 = mBackingArray[7];
        float a20 = mBackingArray[8];
        float a21 = mBackingArray[9];
        float a22 = mBackingArray[10];
        float a23 = mBackingArray[11];
        float a30 = mBackingArray[12];
        float a31 = mBackingArray[13];
        float a32 = mBackingArray[14];
        float a33 = mBackingArray[15];
        float b00 = a00 * a11 - a01 * a10;
        float b01 = a00 * a12 - a02 * a10;
        float b02 = a00 * a13 - a03 * a10;
        float b03 = a01 * a12 - a02 * a11;
        float b04 = a01 * a13 - a03 * a11;
        float b05 = a02 * a13 - a03 * a12;
        float b06 = a20 * a31 - a21 * a30;
        float b07 = a20 * a32 - a22 * a30;
        float b08 = a20 * a33 - a23 * a30;
        float b09 = a21 * a32 - a22 * a31;
        float b10 = a21 * a33 - a23 * a31;
        float b11 = a22 * a33 - a23 * a32;
        float det = (b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06);
        if (det == 0.0f) {
            return false;
        }
        float invDet = 1.0f / det;
        mBackingArray[0] = ((a11 * b11 - a12 * b10 + a13 * b09) * invDet);
        mBackingArray[1] = ((-a01 * b11 + a02 * b10 - a03 * b09) * invDet);
        mBackingArray[2] = ((a31 * b05 - a32 * b04 + a33 * b03) * invDet);
        mBackingArray[3] = ((-a21 * b05 + a22 * b04 - a23 * b03) * invDet);
        mBackingArray[4] = ((-a10 * b11 + a12 * b08 - a13 * b07) * invDet);
        mBackingArray[5] = ((a00 * b11 - a02 * b08 + a03 * b07) * invDet);
        mBackingArray[6] = ((-a30 * b05 + a32 * b02 - a33 * b01) * invDet);
        mBackingArray[7] = ((a20 * b05 - a22 * b02 + a23 * b01) * invDet);
        mBackingArray[8] = ((a10 * b10 - a11 * b08 + a13 * b06) * invDet);
        mBackingArray[9] = ((-a00 * b10 + a01 * b08 - a03 * b06) * invDet);
        mBackingArray[10] = ((a30 * b04 - a31 * b02 + a33 * b00) * invDet);
        mBackingArray[11] = ((-a20 * b04 + a21 * b02 - a23 * b00) * invDet);
        mBackingArray[12] = ((-a10 * b09 + a11 * b07 - a12 * b06) * invDet);
        mBackingArray[13] = ((a00 * b09 - a01 * b07 + a02 * b06) * invDet);
        mBackingArray[14] = ((-a30 * b03 + a31 * b01 - a32 * b00) * invDet);
        mBackingArray[15] = ((a20 * b03 - a21 * b01 + a22 * b00) * invDet);
        return true;
    }

    /**
     * Returns true if Matrix44 is equal to identity matrix.
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public boolean isIdentity() {
        for (int i = 0; i < mBackingArray.length; i++) {
            float expected = (i % 4 == i / 4) ? 1.0f : 0.0f;
            if (expected != mBackingArray[i]) return false;
        }
        return true;
    }

    @FlaggedApi(Flags.FLAG_MATRIX_44)
    private static float dot(Matrix44 a, Matrix44 b, int row, int col) {
        return (a.get(row, 0) * b.get(0, col))
                + (a.get(row, 1) * b.get(1, col))
                + (a.get(row, 2) * b.get(2, col))
                + (a.get(row, 3) * b.get(3, col));
    }

    @FlaggedApi(Flags.FLAG_MATRIX_44)
    private static float dot(float r0, float r1, float r2, float r3,
                             float c0, float c1, float c2, float c3) {
        return (r0 * c0) + (r1 * c1) + (r2 * c2) + (r3 * c3);
    }

    /**
     * Multiplies (x, y, z, w) vector by the Matrix44, then returns the new (x, y, z, w). Users
     * should set {@code w} to 1 to indicate the coordinates are normalized.
     *
     * @return An array of length 4 that represents the x, y, z, w (where w is perspective) value
     * after multiplying x, y, z, 1 by the matrix
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public @NonNull float[] map(float x, float y, float z, float w) {
        float[] dst = new float[4];
        this.map(x, y, z, w, dst);
        return dst;
    }

    /**
     * Multiplies (x, y, z, w) vector by the Matrix44, then returns the new (x, y, z, w). Users
     * should set {@code w} to 1 to indicate the coordinates are normalized.
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public void map(float x, float y, float z, float w, @NonNull float[] dst) {
        if (dst.length != 4) {
            throw new IllegalArgumentException("Dst array must be of length 4");
        }
        dst[0] = x * mBackingArray[0] + y * mBackingArray[1]
                + z * mBackingArray[2] + w * mBackingArray[3];
        dst[1] = x * mBackingArray[4] + y * mBackingArray[5]
                + z * mBackingArray[6] + w * mBackingArray[7];
        dst[2] = x * mBackingArray[8] + y * mBackingArray[9]
                + z * mBackingArray[10] + w * mBackingArray[11];
        dst[3] = x * mBackingArray[12] + y * mBackingArray[13]
                + z * mBackingArray[14] + w * mBackingArray[15];
    }

    /**
     * Multiplies `this` matrix (A) and provided Matrix (B) in the order of A * B.
     * The result is saved in `this` Matrix.
     *
     * @param b The second Matrix in the concatenation operation
     * @return A reference to this Matrix, which can be used to chain Matrix operations
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public @NonNull Matrix44 concat(@NonNull Matrix44 b) {
        float val00 = dot(this, b, 0, 0);
        float val01 = dot(this, b, 0, 1);
        float val02 = dot(this, b, 0, 2);
        float val03 = dot(this, b, 0, 3);
        float val10 = dot(this, b, 1, 0);
        float val11 = dot(this, b, 1, 1);
        float val12 = dot(this, b, 1, 2);
        float val13 = dot(this, b, 1, 3);
        float val20 = dot(this, b, 2, 0);
        float val21 = dot(this, b, 2, 1);
        float val22 = dot(this, b, 2, 2);
        float val23 = dot(this, b, 2, 3);
        float val30 = dot(this, b, 3, 0);
        float val31 = dot(this, b, 3, 1);
        float val32 = dot(this, b, 3, 2);
        float val33 = dot(this, b, 3, 3);

        mBackingArray[0] = val00;
        mBackingArray[1] = val01;
        mBackingArray[2] = val02;
        mBackingArray[3] = val03;
        mBackingArray[4] = val10;
        mBackingArray[5] = val11;
        mBackingArray[6] = val12;
        mBackingArray[7] = val13;
        mBackingArray[8] = val20;
        mBackingArray[9] = val21;
        mBackingArray[10] = val22;
        mBackingArray[11] = val23;
        mBackingArray[12] = val30;
        mBackingArray[13] = val31;
        mBackingArray[14] = val32;
        mBackingArray[15] = val33;

        return this;
    }

    /**
     * Applies a rotation around a given axis, then returns self.
     * {@code x}, {@code y}, {@code z} represent the axis by which to rotate around.
     * For example, pass in {@code 1, 0, 0} to rotate around the x-axis.
     * The axis provided will be normalized.
     *
     * @param deg Amount in degrees to rotate the matrix about the x-axis
     * @param xComp X component of the rotation axis
     * @param yComp Y component of the rotation axis
     * @param zComp Z component of the rotation axis
     * @return A reference to this Matrix, which can be used to chain Matrix operations
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public @NonNull Matrix44 rotate(float deg, float xComp, float yComp, float zComp) {
        float sum = xComp + yComp + zComp;
        float x = xComp / sum;
        float y = yComp / sum;
        float z = zComp / sum;

        float c = (float) Math.cos(deg * Math.PI / 180.0f);
        float s = (float) Math.sin(deg * Math.PI / 180.0f);
        float t = 1 - c;

        float rotVals00 = t * x * x + c;
        float rotVals01 = t * x * y - s * z;
        float rotVals02 = t * x * z + s * y;
        float rotVals10 = t * x * y + s * z;
        float rotVals11 = t * y * y + c;
        float rotVals12 = t * y * z - s * x;
        float rotVals20 = t * x * z - s * y;
        float rotVals21 = t * y * z + s * x;
        float rotVals22 = t * z * z + c;

        float v00 = dot(mBackingArray[0], mBackingArray[1], mBackingArray[2], mBackingArray[3],
                rotVals00, rotVals10, rotVals20, 0);
        float v01 = dot(mBackingArray[0], mBackingArray[1], mBackingArray[2], mBackingArray[3],
                rotVals01, rotVals11, rotVals21, 0);
        float v02 = dot(mBackingArray[0], mBackingArray[1], mBackingArray[2], mBackingArray[3],
                rotVals02, rotVals12, rotVals22, 0);
        float v03 = dot(mBackingArray[0], mBackingArray[1], mBackingArray[2], mBackingArray[3],
                0, 0, 0, 1);
        float v10 = dot(mBackingArray[4], mBackingArray[5], mBackingArray[6], mBackingArray[7],
                rotVals00, rotVals10, rotVals20, 0);
        float v11 = dot(mBackingArray[4], mBackingArray[5], mBackingArray[6], mBackingArray[7],
                rotVals01, rotVals11, rotVals21, 0);
        float v12 = dot(mBackingArray[4], mBackingArray[5], mBackingArray[6], mBackingArray[7],
                rotVals02, rotVals12, rotVals22, 0);
        float v13 = dot(mBackingArray[4], mBackingArray[5], mBackingArray[6], mBackingArray[7],
                0, 0, 0, 1);
        float v20 = dot(mBackingArray[8], mBackingArray[9], mBackingArray[10], mBackingArray[11],
                rotVals00, rotVals10, rotVals20, 0);
        float v21 = dot(mBackingArray[8], mBackingArray[9], mBackingArray[10], mBackingArray[11],
                rotVals01, rotVals11, rotVals21, 0);
        float v22 = dot(mBackingArray[8], mBackingArray[9], mBackingArray[10], mBackingArray[11],
                rotVals02, rotVals12, rotVals22, 0);
        float v23 = dot(mBackingArray[8], mBackingArray[9], mBackingArray[10], mBackingArray[11],
                0, 0, 0, 1);
        float v30 = dot(mBackingArray[12], mBackingArray[13], mBackingArray[14], mBackingArray[15],
                rotVals00, rotVals10, rotVals20, 0);
        float v31 = dot(mBackingArray[12], mBackingArray[13], mBackingArray[14], mBackingArray[15],
                rotVals01, rotVals11, rotVals21, 0);
        float v32 = dot(mBackingArray[12], mBackingArray[13], mBackingArray[14], mBackingArray[15],
                rotVals02, rotVals12, rotVals22, 0);
        float v33 = dot(mBackingArray[12], mBackingArray[13], mBackingArray[14], mBackingArray[15],
                0, 0, 0, 1);

        mBackingArray[0] = v00;
        mBackingArray[1] = v01;
        mBackingArray[2] = v02;
        mBackingArray[3] = v03;
        mBackingArray[4] = v10;
        mBackingArray[5] = v11;
        mBackingArray[6] = v12;
        mBackingArray[7] = v13;
        mBackingArray[8] = v20;
        mBackingArray[9] = v21;
        mBackingArray[10] = v22;
        mBackingArray[11] = v23;
        mBackingArray[12] = v30;
        mBackingArray[13] = v31;
        mBackingArray[14] = v32;
        mBackingArray[15] = v33;

        return this;
    }

    /**
     * Applies scaling factors to `this` Matrix44, then returns self. Pass 1s for no change.
     *
     * @param x Scaling factor for the x-axis
     * @param y Scaling factor for the y-axis
     * @param z Scaling factor for the z-axis
     * @return A reference to this Matrix, which can be used to chain Matrix operations
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public @NonNull Matrix44 scale(float x, float y, float z) {
        mBackingArray[0] *= x;
        mBackingArray[4] *= x;
        mBackingArray[8] *= x;
        mBackingArray[12] *= x;
        mBackingArray[1] *= y;
        mBackingArray[5] *= y;
        mBackingArray[9] *= y;
        mBackingArray[13] *= y;
        mBackingArray[2] *= z;
        mBackingArray[6] *= z;
        mBackingArray[10] *= z;
        mBackingArray[14] *= z;

        return this;
    }

    /**
     * Applies a translation to `this` Matrix44, then returns self.
     *
     * @param x Translation for the x-axis
     * @param y Translation for the y-axis
     * @param z Translation for the z-axis
     * @return A reference to this Matrix, which can be used to chain Matrix operations
     */
    @FlaggedApi(Flags.FLAG_MATRIX_44)
    public @NonNull Matrix44 translate(float x, float y, float z) {
        float newX = x * mBackingArray[0] + y * mBackingArray[1]
                + z * mBackingArray[2] + mBackingArray[3];
        float newY = x * mBackingArray[4] + y * mBackingArray[5]
                + z * mBackingArray[6] + mBackingArray[7];
        float newZ = x * mBackingArray[8] + y * mBackingArray[9]
                + z * mBackingArray[10] + mBackingArray[11];
        float newW = x * mBackingArray[12] + y * mBackingArray[13]
                + z * mBackingArray[14] + mBackingArray[15];

        mBackingArray[3] = newX;
        mBackingArray[7] = newY;
        mBackingArray[11] = newZ;
        mBackingArray[15] = newW;

        return this;
    }

    @Override
    public String toString() {
        return String.format("""
                        | %f %f %f %f |
                        | %f %f %f %f |
                        | %f %f %f %f |
                        | %f %f %f %f |
                        """, mBackingArray[0], mBackingArray[1], mBackingArray[2], mBackingArray[3],
                mBackingArray[4], mBackingArray[5], mBackingArray[6], mBackingArray[7],
                mBackingArray[8], mBackingArray[9], mBackingArray[10], mBackingArray[11],
                mBackingArray[12], mBackingArray[13], mBackingArray[14], mBackingArray[15]);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Matrix44) {
            return Arrays.equals(mBackingArray, ((Matrix44) obj).mBackingArray);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return (int) mBackingArray[0] + (int) mBackingArray[1] + (int) mBackingArray[2]
                + (int) mBackingArray[3] + (int) mBackingArray[4] + (int) mBackingArray[5]
                + (int) mBackingArray[6] + (int) mBackingArray[7] + (int) mBackingArray[8]
                + (int) mBackingArray[9] + (int) mBackingArray[10] + (int) mBackingArray[11]
                + (int) mBackingArray[12] + (int) mBackingArray[13] + (int) mBackingArray[14]
                + (int) mBackingArray[15];
    }

}
