/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.awt.geom.AffineTransform;


/**
 * A matrix implementation overridden by the LayoutLib bridge.
 */
public class Matrix extends _Original_Matrix {

    float mValues[] = new float[9];

    /**
     * Create an identity matrix
     */
    public Matrix() {
        reset();
    }

    /**
     * Create a matrix that is a (deep) copy of src
     * @param src The matrix to copy into this matrix
     */
    public Matrix(Matrix src) {
        set(src);
    }

    /**
     * Creates a Matrix object from the float array. The array becomes the internal storage
     * of the object.
     * @param data
     */
    private Matrix(float[] data) {
        assert data.length != 9;
        mValues = data;
    }

    @Override
    public void finalize() throws Throwable {
        // pass
    }

    //---------- Custom Methods

    /**
     * Adds the given transformation to the current Matrix
     * <p/>This in effect does this = this*matrix
     * @param matrix
     */
    private void addTransform(float[] matrix) {
        float[] tmp = new float[9];

        // first row
        tmp[0] = matrix[0] * mValues[0] + matrix[1] * mValues[3] + matrix[2] * mValues[6];
        tmp[1] = matrix[0] * mValues[1] + matrix[1] * mValues[4] + matrix[2] * mValues[7];
        tmp[2] = matrix[0] * mValues[2] + matrix[1] * mValues[5] + matrix[2] * mValues[8];

        // 2nd row
        tmp[3] = matrix[3] * mValues[0] + matrix[4] * mValues[3] + matrix[5] * mValues[6];
        tmp[4] = matrix[3] * mValues[1] + matrix[4] * mValues[4] + matrix[5] * mValues[7];
        tmp[5] = matrix[3] * mValues[2] + matrix[4] * mValues[5] + matrix[5] * mValues[8];

        // 3rd row
        tmp[6] = matrix[6] * mValues[0] + matrix[7] * mValues[3] + matrix[8] * mValues[6];
        tmp[7] = matrix[6] * mValues[1] + matrix[7] * mValues[4] + matrix[8] * mValues[7];
        tmp[8] = matrix[6] * mValues[2] + matrix[7] * mValues[5] + matrix[8] * mValues[8];

        // copy the result over to mValues
        mValues = tmp;
    }

    public AffineTransform getTransform() {
        return new AffineTransform(mValues[0], mValues[1], mValues[2],
                mValues[3], mValues[4], mValues[5]);
    }

    public boolean hasPerspective() {
        return (mValues[6] != 0 || mValues[7] != 0 || mValues[8] != 1);
    }

    //----------

    /**
     * Returns true if the matrix is identity.
     * This maybe faster than testing if (getType() == 0)
     */
    @Override
    public boolean isIdentity() {
        for (int i = 0, k = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++, k++) {
                if (mValues[k] != ((i==j) ? 1 : 0)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Returns true if will map a rectangle to another rectangle. This can be
     * true if the matrix is identity, scale-only, or rotates a multiple of 90
     * degrees.
     */
    @Override
    public boolean rectStaysRect() {
        return (computeTypeMask() & kRectStaysRect_Mask) != 0;
    }

    /**
     * (deep) copy the src matrix into this matrix. If src is null, reset this
     * matrix to the identity matrix.
     */
    public void set(Matrix src) {
        if (src == null) {
            reset();
        } else {
            System.arraycopy(src.mValues, 0, mValues, 0, mValues.length);
        }
    }

    @Override
    public void set(_Original_Matrix src) {
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    /** Returns true if obj is a Matrix and its values equal our values.
    */
    @Override
    public boolean equals(Object obj) {
        if (obj != null && obj instanceof Matrix) {
            Matrix matrix = (Matrix)obj;
            for (int i = 0 ; i < 9 ; i++) {
                if (mValues[i] != matrix.mValues[i]) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    /** Set the matrix to identity */
    @Override
    public void reset() {
        for (int i = 0, k = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++, k++) {
                mValues[k] = ((i==j) ? 1 : 0);
            }
        }
    }

    /** Set the matrix to translate by (dx, dy). */
    @Override
    public void setTranslate(float dx, float dy) {
        mValues[0] = 1;
        mValues[1] = 0;
        mValues[2] = dx;
        mValues[3] = 0;
        mValues[4] = 1;
        mValues[5] = dy;
        mValues[6] = 0;
        mValues[7] = 0;
        mValues[8] = 1;
    }

    /**
     * Set the matrix to scale by sx and sy, with a pivot point at (px, py).
     * The pivot point is the coordinate that should remain unchanged by the
     * specified transformation.
     */
    @Override
    public void setScale(float sx, float sy, float px, float py) {
        // TODO: do it in one pass

        // translate so that the pivot is in 0,0
        mValues[0] = 1;
        mValues[1] = 0;
        mValues[2] = -px;
        mValues[3] = 0;
        mValues[4] = 1;
        mValues[5] = -py;
        mValues[6] = 0;
        mValues[7] = 0;
        mValues[8] = 1;

        // scale
        addTransform(new float[] { sx, 0, 0, 0, sy, 0, 0, 0, 1 });
        // translate back the pivot
        addTransform(new float[] { 1, 0, px, 0, 1, py, 0, 0, 1 });
    }

    /** Set the matrix to scale by sx and sy. */
    @Override
    public void setScale(float sx, float sy) {
        mValues[0] = sx;
        mValues[1] = 0;
        mValues[2] = 0;
        mValues[3] = 0;
        mValues[4] = sy;
        mValues[5] = 0;
        mValues[6] = 0;
        mValues[7] = 0;
        mValues[8] = 1;
    }

    /**
     * Set the matrix to rotate by the specified number of degrees, with a pivot
     * point at (px, py). The pivot point is the coordinate that should remain
     * unchanged by the specified transformation.
     */
    @Override
    public void setRotate(float degrees, float px, float py) {
        // TODO: do it in one pass

        // translate so that the pivot is in 0,0
        mValues[0] = 1;
        mValues[1] = 0;
        mValues[2] = -px;
        mValues[3] = 0;
        mValues[4] = 1;
        mValues[5] = -py;
        mValues[6] = 0;
        mValues[7] = 0;
        mValues[8] = 1;

        // scale
        double rad = Math.toRadians(degrees);
        float cos = (float)Math.cos(rad);
        float sin = (float)Math.sin(rad);
        addTransform(new float[] { cos, -sin, 0, sin, cos, 0, 0, 0, 1 });
        // translate back the pivot
        addTransform(new float[] { 1, 0, px, 0, 1, py, 0, 0, 1 });
    }

    /**
     * Set the matrix to rotate about (0,0) by the specified number of degrees.
     */
    @Override
    public void setRotate(float degrees) {
        double rad = Math.toRadians(degrees);
        float cos = (float)Math.cos(rad);
        float sin = (float)Math.sin(rad);

        mValues[0] = cos;
        mValues[1] = -sin;
        mValues[2] = 0;
        mValues[3] = sin;
        mValues[4] = cos;
        mValues[5] = 0;
        mValues[6] = 0;
        mValues[7] = 0;
        mValues[8] = 1;
    }

    /**
     * Set the matrix to rotate by the specified sine and cosine values, with a
     * pivot point at (px, py). The pivot point is the coordinate that should
     * remain unchanged by the specified transformation.
     */
    @Override
    public void setSinCos(float sinValue, float cosValue, float px, float py) {
        // TODO: do it in one pass

        // translate so that the pivot is in 0,0
        mValues[0] = 1;
        mValues[1] = 0;
        mValues[2] = -px;
        mValues[3] = 0;
        mValues[4] = 1;
        mValues[5] = -py;
        mValues[6] = 0;
        mValues[7] = 0;
        mValues[8] = 1;

        // scale
        addTransform(new float[] { cosValue, -sinValue, 0, sinValue, cosValue, 0, 0, 0, 1 });
        // translate back the pivot
        addTransform(new float[] { 1, 0, px, 0, 1, py, 0, 0, 1 });
    }

    /** Set the matrix to rotate by the specified sine and cosine values. */
    @Override
    public void setSinCos(float sinValue, float cosValue) {
        mValues[0] = cosValue;
        mValues[1] = -sinValue;
        mValues[2] = 0;
        mValues[3] = sinValue;
        mValues[4] = cosValue;
        mValues[5] = 0;
        mValues[6] = 0;
        mValues[7] = 0;
        mValues[8] = 1;
    }

    /**
     * Set the matrix to skew by sx and sy, with a pivot point at (px, py).
     * The pivot point is the coordinate that should remain unchanged by the
     * specified transformation.
     */
    @Override
    public void setSkew(float kx, float ky, float px, float py) {
        // TODO: do it in one pass

        // translate so that the pivot is in 0,0
        mValues[0] = 1;
        mValues[1] = 0;
        mValues[2] = -px;
        mValues[3] = 0;
        mValues[4] = 1;
        mValues[5] = -py;
        mValues[6] = 0;
        mValues[7] = 0;
        mValues[8] = 1;

        // scale
        addTransform(new float[] { 1, kx, 0, ky, 1, 0, 0, 0, 1 });
        // translate back the pivot
        addTransform(new float[] { 1, 0, px, 0, 1, py, 0, 0, 1 });
    }

    /** Set the matrix to skew by sx and sy. */
    @Override
    public void setSkew(float kx, float ky) {
        mValues[0] = 1;
        mValues[1] = kx;
        mValues[2] = -0;
        mValues[3] = ky;
        mValues[4] = 1;
        mValues[5] = 0;
        mValues[6] = 0;
        mValues[7] = 0;
        mValues[8] = 1;
    }

    /**
     * Set the matrix to the concatenation of the two specified matrices,
     * returning true if the the result can be represented. Either of the two
     * matrices may also be the target matrix. this = a * b
     */
    public boolean setConcat(Matrix a, Matrix b) {
        if (a == this) {
            preConcat(b);
        } else if (b == this) {
            postConcat(b);
        } else {
            Matrix tmp = new Matrix(b);
            tmp.addTransform(a.mValues);
            set(tmp);
        }

        return true;
    }

    @Override
    public boolean setConcat(_Original_Matrix a, _Original_Matrix b) {
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    /**
     * Preconcats the matrix with the specified translation.
     * M' = M * T(dx, dy)
     */
    @Override
    public boolean preTranslate(float dx, float dy) {
        // create a matrix that will be multiply by this
        Matrix m = new Matrix(new float[] { 1, 0, dx, 0, 1, dy, 0, 0, 1 });
        m.addTransform(this.mValues);

        System.arraycopy(m.mValues, 0, mValues, 0, 9);
        return true;
    }

    /**
     * Preconcats the matrix with the specified scale.
     * M' = M * S(sx, sy, px, py)
     */
    @Override
    public boolean preScale(float sx, float sy, float px, float py) {
        Matrix m = new Matrix();
        m.setScale(sx, sy, px, py);
        m.addTransform(mValues);
        set(m);

        return true;
    }

    /**
     * Preconcats the matrix with the specified scale.
     * M' = M * S(sx, sy)
     */
    @Override
    public boolean preScale(float sx, float sy) {
        Matrix m = new Matrix();
        m.setScale(sx, sy);
        m.addTransform(mValues);
        set(m);

        return true;
    }

    /**
     * Preconcats the matrix with the specified rotation.
     * M' = M * R(degrees, px, py)
     */
    @Override
    public boolean preRotate(float degrees, float px, float py) {
        Matrix m = new Matrix();
        m.setRotate(degrees, px, py);
        m.addTransform(mValues);
        set(m);

        return true;
    }

    /**
     * Preconcats the matrix with the specified rotation.
     * M' = M * R(degrees)
     */
    @Override
    public boolean preRotate(float degrees) {
        Matrix m = new Matrix();
        m.setRotate(degrees);
        m.addTransform(mValues);
        set(m);

        return true;
    }

    /**
     * Preconcats the matrix with the specified skew.
     * M' = M * K(kx, ky, px, py)
     */
    @Override
    public boolean preSkew(float kx, float ky, float px, float py) {
        Matrix m = new Matrix();
        m.setSkew(kx, ky, px, py);
        m.addTransform(mValues);
        set(m);

        return true;
    }

    /**
     * Preconcats the matrix with the specified skew.
     * M' = M * K(kx, ky)
     */
    @Override
    public boolean preSkew(float kx, float ky) {
        Matrix m = new Matrix();
        m.setSkew(kx, ky);
        m.addTransform(mValues);
        set(m);

        return true;
    }

    /**
     * Preconcats the matrix with the specified matrix.
     * M' = M * other
     */
    public boolean preConcat(Matrix other) {
        Matrix m = new Matrix(other);
        other.addTransform(mValues);
        set(m);

        return true;
    }

    @Override
    public boolean preConcat(_Original_Matrix other) {
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    /**
     * Postconcats the matrix with the specified translation.
     * M' = T(dx, dy) * M
     */
    @Override
    public boolean postTranslate(float dx, float dy) {
        addTransform(new float[] { 1, 0, dx, 0, 1, dy, 0, 0, 1 });
        return true;
    }

    /**
     * Postconcats the matrix with the specified scale.
     * M' = S(sx, sy, px, py) * M
     */
    @Override
    public boolean postScale(float sx, float sy, float px, float py) {
        // TODO: do it in one pass
        // translate so that the pivot is in 0,0
        addTransform(new float[] { 1, 0, -px, 0, 1, py, 0, 0, 1 });
        // scale
        addTransform(new float[] { sx, 0, 0, 0, sy, 0, 0, 0, 1 });
        // translate back the pivot
        addTransform(new float[] { 1, 0, px, 0, 1, py, 0, 0, 1 });

        return true;
    }

    /**
     * Postconcats the matrix with the specified scale.
     * M' = S(sx, sy) * M
     */
    @Override
    public boolean postScale(float sx, float sy) {
        addTransform(new float[] { sx, 0, 0, 0, sy, 0, 0, 0, 1 });
        return true;
    }

    /**
     * Postconcats the matrix with the specified rotation.
     * M' = R(degrees, px, py) * M
     */
    @Override
    public boolean postRotate(float degrees, float px, float py) {
        // TODO: do it in one pass
        // translate so that the pivot is in 0,0
        addTransform(new float[] { 1, 0, -px, 0, 1, py, 0, 0, 1 });
        // scale
        double rad = Math.toRadians(degrees);
        float cos = (float)Math.cos(rad);
        float sin = (float)Math.sin(rad);
        addTransform(new float[] { cos, -sin, 0, sin, cos, 0, 0, 0, 1 });
        // translate back the pivot
        addTransform(new float[] { 1, 0, px, 0, 1, py, 0, 0, 1 });

        return true;
    }

    /**
     * Postconcats the matrix with the specified rotation.
     * M' = R(degrees) * M
     */
    @Override
    public boolean postRotate(float degrees) {
        double rad = Math.toRadians(degrees);
        float cos = (float)Math.cos(rad);
        float sin = (float)Math.sin(rad);
        addTransform(new float[] { cos, -sin, 0, sin, cos, 0, 0, 0, 1 });

        return true;
    }

    /**
     * Postconcats the matrix with the specified skew.
     * M' = K(kx, ky, px, py) * M
     */
    @Override
    public boolean postSkew(float kx, float ky, float px, float py) {
        // TODO: do it in one pass
        // translate so that the pivot is in 0,0
        addTransform(new float[] { 1, 0, -px, 0, 1, py, 0, 0, 1 });
        // scale
        addTransform(new float[] { 1, kx, 0, ky, 1, 0, 0, 0, 1 });
        // translate back the pivot
        addTransform(new float[] { 1, 0, px, 0, 1, py, 0, 0, 1 });

        return true;
    }

    /**
     * Postconcats the matrix with the specified skew.
     * M' = K(kx, ky) * M
     */
    @Override
    public boolean postSkew(float kx, float ky) {
        addTransform(new float[] { 1, kx, 0, ky, 1, 0, 0, 0, 1 });

        return true;
    }

    /**
     * Postconcats the matrix with the specified matrix.
     * M' = other * M
     */
    public boolean postConcat(Matrix other) {
        addTransform(other.mValues);

        return true;
    }

    @Override
    public boolean postConcat(_Original_Matrix other) {
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    /** Controlls how the src rect should align into the dst rect for
        setRectToRect().
    */
    public enum ScaleToFit {
        /**
         * Scale in X and Y independently, so that src matches dst exactly.
         * This may change the aspect ratio of the src.
         */
        FILL    (0),
        /**
         * Compute a scale that will maintain the original src aspect ratio,
         * but will also ensure that src fits entirely inside dst. At least one
         * axis (X or Y) will fit exactly. START aligns the result to the
         * left and top edges of dst.
         */
        START   (1),
        /**
         * Compute a scale that will maintain the original src aspect ratio,
         * but will also ensure that src fits entirely inside dst. At least one
         * axis (X or Y) will fit exactly. The result is centered inside dst.
         */
        CENTER  (2),
        /**
         * Compute a scale that will maintain the original src aspect ratio,
         * but will also ensure that src fits entirely inside dst. At least one
         * axis (X or Y) will fit exactly. END aligns the result to the
         * right and bottom edges of dst.
         */
        END     (3);

        // the native values must match those in SkMatrix.h
        ScaleToFit(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    /**
     * Set the matrix to the scale and translate values that map the source
     * rectangle to the destination rectangle, returning true if the result
     * can be represented.
     *
     * @param src the source rectangle to map from.
     * @param dst the destination rectangle to map to.
     * @param stf the ScaleToFit option
     * @return true if the matrix can be represented by the rectangle mapping.
     */
    public boolean setRectToRect(RectF src, RectF dst, ScaleToFit stf) {
        if (dst == null || src == null) {
            throw new NullPointerException();
        }

        if (src.isEmpty()) {
            reset();
            return false;
        }

        if (dst.isEmpty()) {
            mValues[0] = mValues[1] = mValues[2] = mValues[3] = mValues[4] = mValues[5]
               = mValues[6] = mValues[7] = 0;
            mValues[8] = 1;
        } else {
            float    tx, sx = dst.width() / src.width();
            float    ty, sy = dst.height() / src.height();
            boolean  xLarger = false;

            if (stf != ScaleToFit.FILL) {
                if (sx > sy) {
                    xLarger = true;
                    sx = sy;
                } else {
                    sy = sx;
                }
            }

            tx = dst.left - src.left * sx;
            ty = dst.top - src.top * sy;
            if (stf == ScaleToFit.CENTER || stf == ScaleToFit.END) {
                float diff;

                if (xLarger) {
                    diff = dst.width() - src.width() * sy;
                } else {
                    diff = dst.height() - src.height() * sy;
                }

                if (stf == ScaleToFit.CENTER) {
                    diff = diff / 2;
                }

                if (xLarger) {
                    tx += diff;
                } else {
                    ty += diff;
                }
            }

            mValues[0] = sx;
            mValues[4] = sy;
            mValues[2] = tx;
            mValues[5] = ty;
            mValues[1]  = mValues[3] = mValues[6] = mValues[7] = 0;

        }
        // shared cleanup
        mValues[8] = 1;
        return true;
    }

    @Override
    public boolean setRectToRect(RectF src, RectF dst, _Original_Matrix.ScaleToFit stf) {
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    /**
     * Set the matrix such that the specified src points would map to the
     * specified dst points. The "points" are represented as an array of floats,
     * order [x0, y0, x1, y1, ...], where each "point" is 2 float values.
     *
     * @param src   The array of src [x,y] pairs (points)
     * @param srcIndex Index of the first pair of src values
     * @param dst   The array of dst [x,y] pairs (points)
     * @param dstIndex Index of the first pair of dst values
     * @param pointCount The number of pairs/points to be used. Must be [0..4]
     * @return true if the matrix was set to the specified transformation
     */
    @Override
    public boolean setPolyToPoly(float[] src, int srcIndex,
                                 float[] dst, int dstIndex,
                                 int pointCount) {
        if (pointCount > 4) {
            throw new IllegalArgumentException();
        }
        checkPointArrays(src, srcIndex, dst, dstIndex, pointCount);
        throw new UnsupportedOperationException("STUB NEEDED");
    }

    /**
     * If this matrix can be inverted, return true and if inverse is not null,
     * set inverse to be the inverse of this matrix. If this matrix cannot be
     * inverted, ignore inverse and return false.
     */
    public boolean invert(Matrix inverse) {
        throw new UnsupportedOperationException("STUB NEEDED");
    }

    @Override
    public boolean invert(_Original_Matrix inverse) {
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    /**
    * Apply this matrix to the array of 2D points specified by src, and write
     * the transformed points into the array of points specified by dst. The
     * two arrays represent their "points" as pairs of floats [x, y].
     *
     * @param dst   The array of dst points (x,y pairs)
     * @param dstIndex The index of the first [x,y] pair of dst floats
     * @param src   The array of src points (x,y pairs)
     * @param srcIndex The index of the first [x,y] pair of src floats
     * @param pointCount The number of points (x,y pairs) to transform
     */
    @Override
    public void mapPoints(float[] dst, int dstIndex, float[] src, int srcIndex,
                          int pointCount) {
        checkPointArrays(src, srcIndex, dst, dstIndex, pointCount);
        throw new UnsupportedOperationException("STUB NEEDED");
    }

    /**
    * Apply this matrix to the array of 2D vectors specified by src, and write
     * the transformed vectors into the array of vectors specified by dst. The
     * two arrays represent their "vectors" as pairs of floats [x, y].
     *
     * @param dst   The array of dst vectors (x,y pairs)
     * @param dstIndex The index of the first [x,y] pair of dst floats
     * @param src   The array of src vectors (x,y pairs)
     * @param srcIndex The index of the first [x,y] pair of src floats
     * @param vectorCount The number of vectors (x,y pairs) to transform
     */
    @Override
    public void mapVectors(float[] dst, int dstIndex, float[] src, int srcIndex,
                          int vectorCount) {
        checkPointArrays(src, srcIndex, dst, dstIndex, vectorCount);
        throw new UnsupportedOperationException("STUB NEEDED");
    }

    /**
     * Apply this matrix to the array of 2D points specified by src, and write
     * the transformed points into the array of points specified by dst. The
     * two arrays represent their "points" as pairs of floats [x, y].
     *
     * @param dst   The array of dst points (x,y pairs)
     * @param src   The array of src points (x,y pairs)
     */
    @Override
    public void mapPoints(float[] dst, float[] src) {
        if (dst.length != src.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        mapPoints(dst, 0, src, 0, dst.length >> 1);
    }

    /**
     * Apply this matrix to the array of 2D vectors specified by src, and write
     * the transformed vectors into the array of vectors specified by dst. The
     * two arrays represent their "vectors" as pairs of floats [x, y].
     *
     * @param dst   The array of dst vectors (x,y pairs)
     * @param src   The array of src vectors (x,y pairs)
     */
    @Override
    public void mapVectors(float[] dst, float[] src) {
        if (dst.length != src.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        mapVectors(dst, 0, src, 0, dst.length >> 1);
    }

    /**
     * Apply this matrix to the array of 2D points, and write the transformed
     * points back into the array
     *
     * @param pts The array [x0, y0, x1, y1, ...] of points to transform.
     */
    @Override
    public void mapPoints(float[] pts) {
        mapPoints(pts, 0, pts, 0, pts.length >> 1);
    }

    /**
     * Apply this matrix to the array of 2D vectors, and write the transformed
     * vectors back into the array.
     * @param vecs The array [x0, y0, x1, y1, ...] of vectors to transform.
     */
    @Override
    public void mapVectors(float[] vecs) {
        mapVectors(vecs, 0, vecs, 0, vecs.length >> 1);
    }

    /**
     * Apply this matrix to the src rectangle, and write the transformed
     * rectangle into dst. This is accomplished by transforming the 4 corners of
     * src, and then setting dst to the bounds of those points.
     *
     * @param dst Where the transformed rectangle is written.
     * @param src The original rectangle to be transformed.
     * @return the result of calling rectStaysRect()
     */
    @Override
    public boolean mapRect(RectF dst, RectF src) {
        if (dst == null || src == null) {
            throw new NullPointerException();
        }
        throw new UnsupportedOperationException("STUB NEEDED");
    }

    /**
     * Apply this matrix to the rectangle, and write the transformed rectangle
     * back into it. This is accomplished by transforming the 4 corners of rect,
     * and then setting it to the bounds of those points
     *
     * @param rect The rectangle to transform.
     * @return the result of calling rectStaysRect()
     */
    @Override
    public boolean mapRect(RectF rect) {
        return mapRect(rect, rect);
    }

    /**
     * Return the mean radius of a circle after it has been mapped by
     * this matrix. NOTE: in perspective this value assumes the circle
     * has its center at the origin.
     */
    @Override
    public float mapRadius(float radius) {
        throw new UnsupportedOperationException("STUB NEEDED");
    }

    /** Copy 9 values from the matrix into the array.
    */
    @Override
    public void getValues(float[] values) {
        if (values.length < 9) {
            throw new ArrayIndexOutOfBoundsException();
        }
        System.arraycopy(mValues, 0, values, 0, mValues.length);
    }

    /** Copy 9 values from the array into the matrix.
        Depending on the implementation of Matrix, these may be
        transformed into 16.16 integers in the Matrix, such that
        a subsequent call to getValues() will not yield exactly
        the same values.
    */
    @Override
    public void setValues(float[] values) {
        if (values.length < 9) {
            throw new ArrayIndexOutOfBoundsException();
        }
        System.arraycopy(values, 0, mValues, 0, mValues.length);
    }

    @SuppressWarnings("unused")
    private final static int kIdentity_Mask      = 0;
    private final static int kTranslate_Mask     = 0x01;  //!< set if the matrix has translation
    private final static int kScale_Mask         = 0x02;  //!< set if the matrix has X or Y scale
    private final static int kAffine_Mask        = 0x04;  //!< set if the matrix skews or rotates
    private final static int kPerspective_Mask   = 0x08;  //!< set if the matrix is in perspective
    private final static int kRectStaysRect_Mask = 0x10;
    @SuppressWarnings("unused")
    private final static int kUnknown_Mask       = 0x80;

    @SuppressWarnings("unused")
    private final static int kAllMasks           = kTranslate_Mask |
                                                     kScale_Mask |
                                                     kAffine_Mask |
                                                     kPerspective_Mask |
                                                     kRectStaysRect_Mask;

    // these guys align with the masks, so we can compute a mask from a variable 0/1
    @SuppressWarnings("unused")
    private final static int kTranslate_Shift = 0;
    @SuppressWarnings("unused")
    private final static int kScale_Shift = 1;
    @SuppressWarnings("unused")
    private final static int kAffine_Shift = 2;
    @SuppressWarnings("unused")
    private final static int kPerspective_Shift = 3;
    private final static int kRectStaysRect_Shift = 4;

    private int computeTypeMask() {
        int mask = 0;

        if (mValues[6] != 0. || mValues[7] != 0. || mValues[8] != 1.) {
            mask |= kPerspective_Mask;
        }

        if (mValues[2] != 0. || mValues[5] != 0.) {
            mask |= kTranslate_Mask;
        }

        float m00 = mValues[0];
        float m01 = mValues[1];
        float m10 = mValues[3];
        float m11 = mValues[4];

        if (m01 != 0. || m10 != 0.) {
            mask |= kAffine_Mask;
        }

        if (m00 != 1. || m11 != 1.) {
            mask |= kScale_Mask;
        }

        if ((mask & kPerspective_Mask) == 0) {
            // map non-zero to 1
            int im00 = m00 != 0 ? 1 : 0;
            int im01 = m01 != 0 ? 1 : 0;
            int im10 = m10 != 0 ? 1 : 0;
            int im11 = m11 != 0 ? 1 : 0;

            // record if the (p)rimary and (s)econdary diagonals are all 0 or
            // all non-zero (answer is 0 or 1)
            int dp0 = (im00 | im11) ^ 1;  // true if both are 0
            int dp1 = im00 & im11;        // true if both are 1
            int ds0 = (im01 | im10) ^ 1;  // true if both are 0
            int ds1 = im01 & im10;        // true if both are 1

            // return 1 if primary is 1 and secondary is 0 or
            // primary is 0 and secondary is 1
            mask |= ((dp0 & ds1) | (dp1 & ds0)) << kRectStaysRect_Shift;
        }

        return mask;
    }
}
