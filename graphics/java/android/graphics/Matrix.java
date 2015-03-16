/*
 * Copyright (C) 2006 The Android Open Source Project
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

import java.io.PrintWriter;


/**
 * The Matrix class holds a 3x3 matrix for transforming coordinates.
 */
public class Matrix {

    public static final int MSCALE_X = 0;   //!< use with getValues/setValues
    public static final int MSKEW_X  = 1;   //!< use with getValues/setValues
    public static final int MTRANS_X = 2;   //!< use with getValues/setValues
    public static final int MSKEW_Y  = 3;   //!< use with getValues/setValues
    public static final int MSCALE_Y = 4;   //!< use with getValues/setValues
    public static final int MTRANS_Y = 5;   //!< use with getValues/setValues
    public static final int MPERSP_0 = 6;   //!< use with getValues/setValues
    public static final int MPERSP_1 = 7;   //!< use with getValues/setValues
    public static final int MPERSP_2 = 8;   //!< use with getValues/setValues

    /** @hide */
    public final static Matrix IDENTITY_MATRIX = new Matrix() {
        void oops() {
            throw new IllegalStateException("Matrix can not be modified");
        }

        @Override
        public void set(Matrix src) {
            oops();
        }

        @Override
        public void reset() {
            oops();
        }

        @Override
        public void setTranslate(float dx, float dy) {
            oops();
        }

        @Override
        public void setScale(float sx, float sy, float px, float py) {
            oops();
        }

        @Override
        public void setScale(float sx, float sy) {
            oops();
        }

        @Override
        public void setRotate(float degrees, float px, float py) {
            oops();
        }

        @Override
        public void setRotate(float degrees) {
            oops();
        }

        @Override
        public void setSinCos(float sinValue, float cosValue, float px, float py) {
            oops();
        }

        @Override
        public void setSinCos(float sinValue, float cosValue) {
            oops();
        }

        @Override
        public void setSkew(float kx, float ky, float px, float py) {
            oops();
        }

        @Override
        public void setSkew(float kx, float ky) {
            oops();
        }

        @Override
        public boolean setConcat(Matrix a, Matrix b) {
            oops();
            return false;
        }

        @Override
        public boolean preTranslate(float dx, float dy) {
            oops();
            return false;
        }

        @Override
        public boolean preScale(float sx, float sy, float px, float py) {
            oops();
            return false;
        }

        @Override
        public boolean preScale(float sx, float sy) {
            oops();
            return false;
        }

        @Override
        public boolean preRotate(float degrees, float px, float py) {
            oops();
            return false;
        }

        @Override
        public boolean preRotate(float degrees) {
            oops();
            return false;
        }

        @Override
        public boolean preSkew(float kx, float ky, float px, float py) {
            oops();
            return false;
        }

        @Override
        public boolean preSkew(float kx, float ky) {
            oops();
            return false;
        }

        @Override
        public boolean preConcat(Matrix other) {
            oops();
            return false;
        }

        @Override
        public boolean postTranslate(float dx, float dy) {
            oops();
            return false;
        }

        @Override
        public boolean postScale(float sx, float sy, float px, float py) {
            oops();
            return false;
        }

        @Override
        public boolean postScale(float sx, float sy) {
            oops();
            return false;
        }

        @Override
        public boolean postRotate(float degrees, float px, float py) {
            oops();
            return false;
        }

        @Override
        public boolean postRotate(float degrees) {
            oops();
            return false;
        }

        @Override
        public boolean postSkew(float kx, float ky, float px, float py) {
            oops();
            return false;
        }

        @Override
        public boolean postSkew(float kx, float ky) {
            oops();
            return false;
        }

        @Override
        public boolean postConcat(Matrix other) {
            oops();
            return false;
        }

        @Override
        public boolean setRectToRect(RectF src, RectF dst, ScaleToFit stf) {
            oops();
            return false;
        }

        @Override
        public boolean setPolyToPoly(float[] src, int srcIndex, float[] dst, int dstIndex,
                int pointCount) {
            oops();
            return false;
        }

        @Override
        public void setValues(float[] values) {
            oops();
        }
    };

    /**
     * @hide
     */
    public long native_instance;

    /**
     * Create an identity matrix
     */
    public Matrix() {
        native_instance = native_create(0);
    }

    /**
     * Create a matrix that is a (deep) copy of src
     * @param src The matrix to copy into this matrix
     */
    public Matrix(Matrix src) {
        native_instance = native_create(src != null ? src.native_instance : 0);
    }

    /**
     * Returns true if the matrix is identity.
     * This maybe faster than testing if (getType() == 0)
     */
    public boolean isIdentity() {
        return native_isIdentity(native_instance);
    }

    /**
     * Gets whether this matrix is affine. An affine matrix preserves
     * straight lines and has no perspective.
     *
     * @return Whether the matrix is affine.
     */
    public boolean isAffine() {
        return native_isAffine(native_instance);
    }

    /**
     * Returns true if will map a rectangle to another rectangle. This can be
     * true if the matrix is identity, scale-only, or rotates a multiple of 90
     * degrees.
     */
    public boolean rectStaysRect() {
        return native_rectStaysRect(native_instance);
    }

    /**
     * (deep) copy the src matrix into this matrix. If src is null, reset this
     * matrix to the identity matrix.
     */
    public void set(Matrix src) {
        if (src == null) {
            reset();
        } else {
            native_set(native_instance, src.native_instance);
        }
    }

    /** Returns true iff obj is a Matrix and its values equal our values.
    */
    @Override
    public boolean equals(Object obj) {
        //if (obj == this) return true;     -- NaN value would mean matrix != itself
        if (!(obj instanceof Matrix)) return false;
        return native_equals(native_instance, ((Matrix)obj).native_instance);
    }

    @Override
    public int hashCode() {
        // This should generate the hash code by performing some arithmetic operation on all
        // the matrix elements -- our equals() does an element-by-element comparison, and we
        // need to ensure that the hash code for two equal objects is the same.  We're not
        // really using this at the moment, so we take the easy way out.
        return 44;
    }

    /** Set the matrix to identity */
    public void reset() {
        native_reset(native_instance);
    }

    /** Set the matrix to translate by (dx, dy). */
    public void setTranslate(float dx, float dy) {
        native_setTranslate(native_instance, dx, dy);
    }

    /**
     * Set the matrix to scale by sx and sy, with a pivot point at (px, py).
     * The pivot point is the coordinate that should remain unchanged by the
     * specified transformation.
     */
    public void setScale(float sx, float sy, float px, float py) {
        native_setScale(native_instance, sx, sy, px, py);
    }

    /** Set the matrix to scale by sx and sy. */
    public void setScale(float sx, float sy) {
        native_setScale(native_instance, sx, sy);
    }

    /**
     * Set the matrix to rotate by the specified number of degrees, with a pivot
     * point at (px, py). The pivot point is the coordinate that should remain
     * unchanged by the specified transformation.
     */
    public void setRotate(float degrees, float px, float py) {
        native_setRotate(native_instance, degrees, px, py);
    }

    /**
     * Set the matrix to rotate about (0,0) by the specified number of degrees.
     */
    public void setRotate(float degrees) {
        native_setRotate(native_instance, degrees);
    }

    /**
     * Set the matrix to rotate by the specified sine and cosine values, with a
     * pivot point at (px, py). The pivot point is the coordinate that should
     * remain unchanged by the specified transformation.
     */
    public void setSinCos(float sinValue, float cosValue, float px, float py) {
        native_setSinCos(native_instance, sinValue, cosValue, px, py);
    }

    /** Set the matrix to rotate by the specified sine and cosine values. */
    public void setSinCos(float sinValue, float cosValue) {
        native_setSinCos(native_instance, sinValue, cosValue);
    }

    /**
     * Set the matrix to skew by sx and sy, with a pivot point at (px, py).
     * The pivot point is the coordinate that should remain unchanged by the
     * specified transformation.
     */
    public void setSkew(float kx, float ky, float px, float py) {
        native_setSkew(native_instance, kx, ky, px, py);
    }

    /** Set the matrix to skew by sx and sy. */
    public void setSkew(float kx, float ky) {
        native_setSkew(native_instance, kx, ky);
    }

    /**
     * Set the matrix to the concatenation of the two specified matrices and
     * return true.
     *
     * <p>Either of the two matrices may also be the target matrix, that is
     * <code>matrixA.setConcat(matrixA, matrixB);</code> is valid.</p>
     *
     * <p class="note">In {@link android.os.Build.VERSION_CODES#GINGERBREAD_MR1} and below, this
     * function returns true only if the result can be represented. In
     * {@link android.os.Build.VERSION_CODES#HONEYCOMB} and above, it always returns true.</p>
     */
    public boolean setConcat(Matrix a, Matrix b) {
        native_setConcat(native_instance, a.native_instance, b.native_instance);
        return true;
    }

    /**
     * Preconcats the matrix with the specified translation.
     * M' = M * T(dx, dy)
     */
    public boolean preTranslate(float dx, float dy) {
        native_preTranslate(native_instance, dx, dy);
        return true;
    }

    /**
     * Preconcats the matrix with the specified scale.
     * M' = M * S(sx, sy, px, py)
     */
    public boolean preScale(float sx, float sy, float px, float py) {
        native_preScale(native_instance, sx, sy, px, py);
        return true;
    }

    /**
     * Preconcats the matrix with the specified scale.
     * M' = M * S(sx, sy)
     */
    public boolean preScale(float sx, float sy) {
        native_preScale(native_instance, sx, sy);
        return true;
    }

    /**
     * Preconcats the matrix with the specified rotation.
     * M' = M * R(degrees, px, py)
     */
    public boolean preRotate(float degrees, float px, float py) {
        native_preRotate(native_instance, degrees, px, py);
        return true;
    }

    /**
     * Preconcats the matrix with the specified rotation.
     * M' = M * R(degrees)
     */
    public boolean preRotate(float degrees) {
        native_preRotate(native_instance, degrees);
        return true;
    }

    /**
     * Preconcats the matrix with the specified skew.
     * M' = M * K(kx, ky, px, py)
     */
    public boolean preSkew(float kx, float ky, float px, float py) {
        native_preSkew(native_instance, kx, ky, px, py);
        return true;
    }

    /**
     * Preconcats the matrix with the specified skew.
     * M' = M * K(kx, ky)
     */
    public boolean preSkew(float kx, float ky) {
        native_preSkew(native_instance, kx, ky);
        return true;
    }

    /**
     * Preconcats the matrix with the specified matrix.
     * M' = M * other
     */
    public boolean preConcat(Matrix other) {
        native_preConcat(native_instance, other.native_instance);
        return true;
    }

    /**
     * Postconcats the matrix with the specified translation.
     * M' = T(dx, dy) * M
     */
    public boolean postTranslate(float dx, float dy) {
        native_postTranslate(native_instance, dx, dy);
        return true;
    }

    /**
     * Postconcats the matrix with the specified scale.
     * M' = S(sx, sy, px, py) * M
     */
    public boolean postScale(float sx, float sy, float px, float py) {
        native_postScale(native_instance, sx, sy, px, py);
        return true;
    }

    /**
     * Postconcats the matrix with the specified scale.
     * M' = S(sx, sy) * M
     */
    public boolean postScale(float sx, float sy) {
        native_postScale(native_instance, sx, sy);
        return true;
    }

    /**
     * Postconcats the matrix with the specified rotation.
     * M' = R(degrees, px, py) * M
     */
    public boolean postRotate(float degrees, float px, float py) {
        native_postRotate(native_instance, degrees, px, py);
        return true;
    }

    /**
     * Postconcats the matrix with the specified rotation.
     * M' = R(degrees) * M
     */
    public boolean postRotate(float degrees) {
        native_postRotate(native_instance, degrees);
        return true;
    }

    /**
     * Postconcats the matrix with the specified skew.
     * M' = K(kx, ky, px, py) * M
     */
    public boolean postSkew(float kx, float ky, float px, float py) {
        native_postSkew(native_instance, kx, ky, px, py);
        return true;
    }

    /**
     * Postconcats the matrix with the specified skew.
     * M' = K(kx, ky) * M
     */
    public boolean postSkew(float kx, float ky) {
        native_postSkew(native_instance, kx, ky);
        return true;
    }

    /**
     * Postconcats the matrix with the specified matrix.
     * M' = other * M
     */
    public boolean postConcat(Matrix other) {
        native_postConcat(native_instance, other.native_instance);
        return true;
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
     * rectangle to the destination rectangle, returning true if the the result
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
        return native_setRectToRect(native_instance, src, dst, stf.nativeInt);
    }

    // private helper to perform range checks on arrays of "points"
    private static void checkPointArrays(float[] src, int srcIndex,
                                         float[] dst, int dstIndex,
                                         int pointCount) {
        // check for too-small and too-big indices
        int srcStop = srcIndex + (pointCount << 1);
        int dstStop = dstIndex + (pointCount << 1);
        if ((pointCount | srcIndex | dstIndex | srcStop | dstStop) < 0 ||
                srcStop > src.length || dstStop > dst.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
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
    public boolean setPolyToPoly(float[] src, int srcIndex,
                                 float[] dst, int dstIndex,
                                 int pointCount) {
        if (pointCount > 4) {
            throw new IllegalArgumentException();
        }
        checkPointArrays(src, srcIndex, dst, dstIndex, pointCount);
        return native_setPolyToPoly(native_instance, src, srcIndex,
                                    dst, dstIndex, pointCount);
    }

    /**
     * If this matrix can be inverted, return true and if inverse is not null,
     * set inverse to be the inverse of this matrix. If this matrix cannot be
     * inverted, ignore inverse and return false.
     */
    public boolean invert(Matrix inverse) {
        return native_invert(native_instance, inverse.native_instance);
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
    public void mapPoints(float[] dst, int dstIndex, float[] src, int srcIndex,
                          int pointCount) {
        checkPointArrays(src, srcIndex, dst, dstIndex, pointCount);
        native_mapPoints(native_instance, dst, dstIndex, src, srcIndex,
                         pointCount, true);
    }

    /**
    * Apply this matrix to the array of 2D vectors specified by src, and write
     * the transformed vectors into the array of vectors specified by dst. The
     * two arrays represent their "vectors" as pairs of floats [x, y].
     *
     * Note: this method does not apply the translation associated with the matrix. Use
     * {@link Matrix#mapPoints(float[], int, float[], int, int)} if you want the translation
     * to be applied.
     *
     * @param dst   The array of dst vectors (x,y pairs)
     * @param dstIndex The index of the first [x,y] pair of dst floats
     * @param src   The array of src vectors (x,y pairs)
     * @param srcIndex The index of the first [x,y] pair of src floats
     * @param vectorCount The number of vectors (x,y pairs) to transform
     */
    public void mapVectors(float[] dst, int dstIndex, float[] src, int srcIndex,
                          int vectorCount) {
        checkPointArrays(src, srcIndex, dst, dstIndex, vectorCount);
        native_mapPoints(native_instance, dst, dstIndex, src, srcIndex,
                         vectorCount, false);
    }

    /**
     * Apply this matrix to the array of 2D points specified by src, and write
     * the transformed points into the array of points specified by dst. The
     * two arrays represent their "points" as pairs of floats [x, y].
     *
     * @param dst   The array of dst points (x,y pairs)
     * @param src   The array of src points (x,y pairs)
     */
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
     * Note: this method does not apply the translation associated with the matrix. Use
     * {@link Matrix#mapPoints(float[], float[])} if you want the translation to be applied.
     *
     * @param dst   The array of dst vectors (x,y pairs)
     * @param src   The array of src vectors (x,y pairs)
     */
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
    public void mapPoints(float[] pts) {
        mapPoints(pts, 0, pts, 0, pts.length >> 1);
    }

    /**
     * Apply this matrix to the array of 2D vectors, and write the transformed
     * vectors back into the array.
     *
     * Note: this method does not apply the translation associated with the matrix. Use
     * {@link Matrix#mapPoints(float[])} if you want the translation to be applied.
     *
     * @param vecs The array [x0, y0, x1, y1, ...] of vectors to transform.
     */
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
    public boolean mapRect(RectF dst, RectF src) {
        if (dst == null || src == null) {
            throw new NullPointerException();
        }
        return native_mapRect(native_instance, dst, src);
    }

    /**
     * Apply this matrix to the rectangle, and write the transformed rectangle
     * back into it. This is accomplished by transforming the 4 corners of rect,
     * and then setting it to the bounds of those points
     *
     * @param rect The rectangle to transform.
     * @return the result of calling rectStaysRect()
     */
    public boolean mapRect(RectF rect) {
        return mapRect(rect, rect);
    }

    /**
     * Return the mean radius of a circle after it has been mapped by
     * this matrix. NOTE: in perspective this value assumes the circle
     * has its center at the origin.
     */
    public float mapRadius(float radius) {
        return native_mapRadius(native_instance, radius);
    }

    /** Copy 9 values from the matrix into the array.
    */
    public void getValues(float[] values) {
        if (values.length < 9) {
            throw new ArrayIndexOutOfBoundsException();
        }
        native_getValues(native_instance, values);
    }

    /** Copy 9 values from the array into the matrix.
        Depending on the implementation of Matrix, these may be
        transformed into 16.16 integers in the Matrix, such that
        a subsequent call to getValues() will not yield exactly
        the same values.
    */
    public void setValues(float[] values) {
        if (values.length < 9) {
            throw new ArrayIndexOutOfBoundsException();
        }
        native_setValues(native_instance, values);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("Matrix{");
        toShortString(sb);
        sb.append('}');
        return sb.toString();

    }

    public String toShortString() {
        StringBuilder sb = new StringBuilder(64);
        toShortString(sb);
        return sb.toString();
    }

    /**
     * @hide
     */
    public void toShortString(StringBuilder sb) {
        float[] values = new float[9];
        getValues(values);
        sb.append('[');
        sb.append(values[0]); sb.append(", "); sb.append(values[1]); sb.append(", ");
        sb.append(values[2]); sb.append("][");
        sb.append(values[3]); sb.append(", "); sb.append(values[4]); sb.append(", ");
        sb.append(values[5]); sb.append("][");
        sb.append(values[6]); sb.append(", "); sb.append(values[7]); sb.append(", ");
        sb.append(values[8]); sb.append(']');
    }

    /**
     * Print short string, to optimize dumping.
     * @hide
     */
    public void printShortString(PrintWriter pw) {
        float[] values = new float[9];
        getValues(values);
        pw.print('[');
        pw.print(values[0]); pw.print(", "); pw.print(values[1]); pw.print(", ");
                pw.print(values[2]); pw.print("][");
        pw.print(values[3]); pw.print(", "); pw.print(values[4]); pw.print(", ");
                pw.print(values[5]); pw.print("][");
        pw.print(values[6]); pw.print(", "); pw.print(values[7]); pw.print(", ");
                pw.print(values[8]); pw.print(']');

    }

    @Override
    protected void finalize() throws Throwable {
        try {
            finalizer(native_instance);
        } finally {
            super.finalize();
        }
    }

    /*package*/ final long ni() {
        return native_instance;
    }

    private static native long native_create(long native_src_or_zero);
    private static native boolean native_isIdentity(long native_object);
    private static native boolean native_isAffine(long native_object);
    private static native boolean native_rectStaysRect(long native_object);
    private static native void native_reset(long native_object);
    private static native void native_set(long native_object,
                                          long native_other);
    private static native void native_setTranslate(long native_object,
                                                   float dx, float dy);
    private static native void native_setScale(long native_object,
                                        float sx, float sy, float px, float py);
    private static native void native_setScale(long native_object,
                                               float sx, float sy);
    private static native void native_setRotate(long native_object,
                                            float degrees, float px, float py);
    private static native void native_setRotate(long native_object,
                                                float degrees);
    private static native void native_setSinCos(long native_object,
                            float sinValue, float cosValue, float px, float py);
    private static native void native_setSinCos(long native_object,
                                                float sinValue, float cosValue);
    private static native void native_setSkew(long native_object,
                                        float kx, float ky, float px, float py);
    private static native void native_setSkew(long native_object,
                                              float kx, float ky);
    private static native void native_setConcat(long native_object,
                                                long native_a,
                                                long native_b);
    private static native void native_preTranslate(long native_object,
                                                   float dx, float dy);
    private static native void native_preScale(long native_object,
                                               float sx, float sy, float px, float py);
    private static native void native_preScale(long native_object,
                                               float sx, float sy);
    private static native void native_preRotate(long native_object,
                                                float degrees, float px, float py);
    private static native void native_preRotate(long native_object,
                                                float degrees);
    private static native void native_preSkew(long native_object,
                                              float kx, float ky, float px, float py);
    private static native void native_preSkew(long native_object,
                                              float kx, float ky);
    private static native void native_preConcat(long native_object,
                                                long native_other_matrix);
    private static native void native_postTranslate(long native_object,
                                                    float dx, float dy);
    private static native void native_postScale(long native_object,
                                                float sx, float sy, float px, float py);
    private static native void native_postScale(long native_object,
                                                float sx, float sy);
    private static native void native_postRotate(long native_object,
                                                 float degrees, float px, float py);
    private static native void native_postRotate(long native_object,
                                                 float degrees);
    private static native void native_postSkew(long native_object,
                                               float kx, float ky, float px, float py);
    private static native void native_postSkew(long native_object,
                                               float kx, float ky);
    private static native void native_postConcat(long native_object,
                                                 long native_other_matrix);
    private static native boolean native_setRectToRect(long native_object,
                                                RectF src, RectF dst, int stf);
    private static native boolean native_setPolyToPoly(long native_object,
        float[] src, int srcIndex, float[] dst, int dstIndex, int pointCount);
    private static native boolean native_invert(long native_object,
                                                long native_inverse);
    private static native void native_mapPoints(long native_object,
                        float[] dst, int dstIndex, float[] src, int srcIndex,
                        int ptCount, boolean isPts);
    private static native boolean native_mapRect(long native_object,
                                                 RectF dst, RectF src);
    private static native float native_mapRadius(long native_object,
                                                 float radius);
    private static native void native_getValues(long native_object,
                                                float[] values);
    private static native void native_setValues(long native_object,
                                                float[] values);
    private static native boolean native_equals(long native_a, long native_b);
    private static native void finalizer(long native_instance);
}
