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

import android.annotation.NonNull;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.io.PrintWriter;

/**
 * The Matrix class holds a 3x3 matrix for transforming coordinates.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
@android.ravenwood.annotation.RavenwoodClassLoadHook(
        android.ravenwood.annotation.RavenwoodClassLoadHook.LIBANDROID_LOADING_HOOK)
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

    /**
     * The identity matrix. Multiplying by another matrix {@code M} returns {@code M}. This matrix
     * is immutable, and attempting to modify it will throw an {@link IllegalStateException}.
     */
    @NonNull
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

    private static class NoImagePreloadHolder {
        public static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                Matrix.class.getClassLoader(), ExtraNatives.nGetNativeFinalizer());
    }

    private final long native_instance;

    /**
     * Create an identity matrix
     */
    public Matrix() {
        native_instance = ExtraNatives.nCreate(0);
        NoImagePreloadHolder.sRegistry.registerNativeAllocation(this, native_instance);
    }

    /**
     * Create a matrix that is a (deep) copy of src
     *
     * @param src The matrix to copy into this matrix
     */
    public Matrix(Matrix src) {
        native_instance = ExtraNatives.nCreate(src != null ? src.native_instance : 0);
        NoImagePreloadHolder.sRegistry.registerNativeAllocation(this, native_instance);
    }

    /**
     * Returns true if the matrix is identity. This maybe faster than testing if (getType() == 0)
     */
    public boolean isIdentity() {
        return nIsIdentity(native_instance);
    }

    /**
     * Gets whether this matrix is affine. An affine matrix preserves straight lines and has no
     * perspective.
     *
     * @return Whether the matrix is affine.
     */
    public boolean isAffine() {
        return nIsAffine(native_instance);
    }

    /**
     * Returns true if will map a rectangle to another rectangle. This can be true if the matrix is
     * identity, scale-only, or rotates a multiple of 90 degrees.
     */
    public boolean rectStaysRect() {
        return nRectStaysRect(native_instance);
    }

    /**
     * (deep) copy the src matrix into this matrix. If src is null, reset this matrix to the
     * identity matrix.
     */
    public void set(Matrix src) {
        if (src == null) {
            reset();
        } else {
            nSet(native_instance, src.native_instance);
        }
    }

    /**
     * Returns true iff obj is a Matrix and its values equal our values.
     */
    @Override
    public boolean equals(Object obj) {
        // if (obj == this) return true; -- NaN value would mean matrix != itself
        if (!(obj instanceof Matrix)) {
            return false;
        }
        return nEquals(native_instance, ((Matrix) obj).native_instance);
    }

    @Override
    public int hashCode() {
        // This should generate the hash code by performing some arithmetic operation on all
        // the matrix elements -- our equals() does an element-by-element comparison, and we
        // need to ensure that the hash code for two equal objects is the same. We're not
        // really using this at the moment, so we take the easy way out.
        return 44;
    }

    /** Set the matrix to identity */
    public void reset() {
        nReset(native_instance);
    }

    /** Set the matrix to translate by (dx, dy). */
    public void setTranslate(float dx, float dy) {
        nSetTranslate(native_instance, dx, dy);
    }

    /**
     * Set the matrix to scale by sx and sy, with a pivot point at (px, py). The pivot point is the
     * coordinate that should remain unchanged by the specified transformation.
     */
    public void setScale(float sx, float sy, float px, float py) {
        nSetScale(native_instance, sx, sy, px, py);
    }

    /** Set the matrix to scale by sx and sy. */
    public void setScale(float sx, float sy) {
        nSetScale(native_instance, sx, sy);
    }

    /**
     * Set the matrix to rotate by the specified number of degrees, with a pivot point at (px, py).
     * The pivot point is the coordinate that should remain unchanged by the specified
     * transformation.
     */
    public void setRotate(float degrees, float px, float py) {
        nSetRotate(native_instance, degrees, px, py);
    }

    /**
     * Set the matrix to rotate about (0,0) by the specified number of degrees.
     */
    public void setRotate(float degrees) {
        nSetRotate(native_instance, degrees);
    }

    /**
     * Set the matrix to rotate by the specified sine and cosine values, with a pivot point at (px,
     * py). The pivot point is the coordinate that should remain unchanged by the specified
     * transformation.
     */
    public void setSinCos(float sinValue, float cosValue, float px, float py) {
        nSetSinCos(native_instance, sinValue, cosValue, px, py);
    }

    /** Set the matrix to rotate by the specified sine and cosine values. */
    public void setSinCos(float sinValue, float cosValue) {
        nSetSinCos(native_instance, sinValue, cosValue);
    }

    /**
     * Set the matrix to skew by sx and sy, with a pivot point at (px, py). The pivot point is the
     * coordinate that should remain unchanged by the specified transformation.
     */
    public void setSkew(float kx, float ky, float px, float py) {
        nSetSkew(native_instance, kx, ky, px, py);
    }

    /** Set the matrix to skew by sx and sy. */
    public void setSkew(float kx, float ky) {
        nSetSkew(native_instance, kx, ky);
    }

    /**
     * Set the matrix to the concatenation of the two specified matrices and return true.
     * <p>
     * Either of the two matrices may also be the target matrix, that is
     * <code>matrixA.setConcat(matrixA, matrixB);</code> is valid.
     * </p>
     * <p class="note">
     * In {@link android.os.Build.VERSION_CODES#GINGERBREAD_MR1} and below, this function returns
     * true only if the result can be represented. In
     * {@link android.os.Build.VERSION_CODES#HONEYCOMB} and above, it always returns true.
     * </p>
     */
    public boolean setConcat(Matrix a, Matrix b) {
        nSetConcat(native_instance, a.native_instance, b.native_instance);
        return true;
    }

    /**
     * Preconcats the matrix with the specified translation. M' = M * T(dx, dy)
     */
    public boolean preTranslate(float dx, float dy) {
        nPreTranslate(native_instance, dx, dy);
        return true;
    }

    /**
     * Preconcats the matrix with the specified scale. M' = M * S(sx, sy, px, py)
     */
    public boolean preScale(float sx, float sy, float px, float py) {
        nPreScale(native_instance, sx, sy, px, py);
        return true;
    }

    /**
     * Preconcats the matrix with the specified scale. M' = M * S(sx, sy)
     */
    public boolean preScale(float sx, float sy) {
        nPreScale(native_instance, sx, sy);
        return true;
    }

    /**
     * Preconcats the matrix with the specified rotation. M' = M * R(degrees, px, py)
     */
    public boolean preRotate(float degrees, float px, float py) {
        nPreRotate(native_instance, degrees, px, py);
        return true;
    }

    /**
     * Preconcats the matrix with the specified rotation. M' = M * R(degrees)
     */
    public boolean preRotate(float degrees) {
        nPreRotate(native_instance, degrees);
        return true;
    }

    /**
     * Preconcats the matrix with the specified skew. M' = M * K(kx, ky, px, py)
     */
    public boolean preSkew(float kx, float ky, float px, float py) {
        nPreSkew(native_instance, kx, ky, px, py);
        return true;
    }

    /**
     * Preconcats the matrix with the specified skew. M' = M * K(kx, ky)
     */
    public boolean preSkew(float kx, float ky) {
        nPreSkew(native_instance, kx, ky);
        return true;
    }

    /**
     * Preconcats the matrix with the specified matrix. M' = M * other
     */
    public boolean preConcat(Matrix other) {
        nPreConcat(native_instance, other.native_instance);
        return true;
    }

    /**
     * Postconcats the matrix with the specified translation. M' = T(dx, dy) * M
     */
    public boolean postTranslate(float dx, float dy) {
        nPostTranslate(native_instance, dx, dy);
        return true;
    }

    /**
     * Postconcats the matrix with the specified scale. M' = S(sx, sy, px, py) * M
     */
    public boolean postScale(float sx, float sy, float px, float py) {
        nPostScale(native_instance, sx, sy, px, py);
        return true;
    }

    /**
     * Postconcats the matrix with the specified scale. M' = S(sx, sy) * M
     */
    public boolean postScale(float sx, float sy) {
        nPostScale(native_instance, sx, sy);
        return true;
    }

    /**
     * Postconcats the matrix with the specified rotation. M' = R(degrees, px, py) * M
     */
    public boolean postRotate(float degrees, float px, float py) {
        nPostRotate(native_instance, degrees, px, py);
        return true;
    }

    /**
     * Postconcats the matrix with the specified rotation. M' = R(degrees) * M
     */
    public boolean postRotate(float degrees) {
        nPostRotate(native_instance, degrees);
        return true;
    }

    /**
     * Postconcats the matrix with the specified skew. M' = K(kx, ky, px, py) * M
     */
    public boolean postSkew(float kx, float ky, float px, float py) {
        nPostSkew(native_instance, kx, ky, px, py);
        return true;
    }

    /**
     * Postconcats the matrix with the specified skew. M' = K(kx, ky) * M
     */
    public boolean postSkew(float kx, float ky) {
        nPostSkew(native_instance, kx, ky);
        return true;
    }

    /**
     * Postconcats the matrix with the specified matrix. M' = other * M
     */
    public boolean postConcat(Matrix other) {
        nPostConcat(native_instance, other.native_instance);
        return true;
    }

    /**
     * Controls how the src rect should align into the dst rect for setRectToRect().
     */
    public enum ScaleToFit {
        /**
         * Scale in X and Y independently, so that src matches dst exactly. This may change the
         * aspect ratio of the src.
         */
        FILL(0),
        /**
         * Compute a scale that will maintain the original src aspect ratio, but will also ensure
         * that src fits entirely inside dst. At least one axis (X or Y) will fit exactly. START
         * aligns the result to the left and top edges of dst.
         */
        START(1),
        /**
         * Compute a scale that will maintain the original src aspect ratio, but will also ensure
         * that src fits entirely inside dst. At least one axis (X or Y) will fit exactly. The
         * result is centered inside dst.
         */
        CENTER(2),
        /**
         * Compute a scale that will maintain the original src aspect ratio, but will also ensure
         * that src fits entirely inside dst. At least one axis (X or Y) will fit exactly. END
         * aligns the result to the right and bottom edges of dst.
         */
        END(3);

        // the native values must match those in SkMatrix.h
        ScaleToFit(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        final int nativeInt;
    }

    /**
     * Set the matrix to the scale and translate values that map the source rectangle to the
     * destination rectangle, returning true if the the result can be represented.
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
        return nSetRectToRect(native_instance, src, dst, stf.nativeInt);
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
     * Set the matrix such that the specified src points would map to the specified dst points. The
     * "points" are represented as an array of floats, order [x0, y0, x1, y1, ...], where each
     * "point" is 2 float values.
     *
     * @param src The array of src [x,y] pairs (points)
     * @param srcIndex Index of the first pair of src values
     * @param dst The array of dst [x,y] pairs (points)
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
        return nSetPolyToPoly(native_instance, src, srcIndex,
                dst, dstIndex, pointCount);
    }

    /**
     * If this matrix can be inverted, return true and if inverse is not null, set inverse to be the
     * inverse of this matrix. If this matrix cannot be inverted, ignore inverse and return false.
     */
    public boolean invert(Matrix inverse) {
        return nInvert(native_instance, inverse.native_instance);
    }

    /**
     * Apply this matrix to the array of 2D points specified by src, and write the transformed
     * points into the array of points specified by dst. The two arrays represent their "points" as
     * pairs of floats [x, y].
     *
     * @param dst The array of dst points (x,y pairs)
     * @param dstIndex The index of the first [x,y] pair of dst floats
     * @param src The array of src points (x,y pairs)
     * @param srcIndex The index of the first [x,y] pair of src floats
     * @param pointCount The number of points (x,y pairs) to transform
     */
    public void mapPoints(float[] dst, int dstIndex, float[] src, int srcIndex,
            int pointCount) {
        checkPointArrays(src, srcIndex, dst, dstIndex, pointCount);
        nMapPoints(native_instance, dst, dstIndex, src, srcIndex,
                pointCount, true);
    }

    /**
     * Apply this matrix to the array of 2D vectors specified by src, and write the transformed
     * vectors into the array of vectors specified by dst. The two arrays represent their "vectors"
     * as pairs of floats [x, y]. Note: this method does not apply the translation associated with
     * the matrix. Use {@link Matrix#mapPoints(float[], int, float[], int, int)} if you want the
     * translation to be applied.
     *
     * @param dst The array of dst vectors (x,y pairs)
     * @param dstIndex The index of the first [x,y] pair of dst floats
     * @param src The array of src vectors (x,y pairs)
     * @param srcIndex The index of the first [x,y] pair of src floats
     * @param vectorCount The number of vectors (x,y pairs) to transform
     */
    public void mapVectors(float[] dst, int dstIndex, float[] src, int srcIndex,
            int vectorCount) {
        checkPointArrays(src, srcIndex, dst, dstIndex, vectorCount);
        nMapPoints(native_instance, dst, dstIndex, src, srcIndex,
                vectorCount, false);
    }

    /**
     * Apply this matrix to the array of 2D points specified by src, and write the transformed
     * points into the array of points specified by dst. The two arrays represent their "points" as
     * pairs of floats [x, y].
     *
     * @param dst The array of dst points (x,y pairs)
     * @param src The array of src points (x,y pairs)
     */
    public void mapPoints(float[] dst, float[] src) {
        if (dst.length != src.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        mapPoints(dst, 0, src, 0, dst.length >> 1);
    }

    /**
     * Apply this matrix to the array of 2D vectors specified by src, and write the transformed
     * vectors into the array of vectors specified by dst. The two arrays represent their "vectors"
     * as pairs of floats [x, y]. Note: this method does not apply the translation associated with
     * the matrix. Use {@link Matrix#mapPoints(float[], float[])} if you want the translation to be
     * applied.
     *
     * @param dst The array of dst vectors (x,y pairs)
     * @param src The array of src vectors (x,y pairs)
     */
    public void mapVectors(float[] dst, float[] src) {
        if (dst.length != src.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        mapVectors(dst, 0, src, 0, dst.length >> 1);
    }

    /**
     * Apply this matrix to the array of 2D points, and write the transformed points back into the
     * array
     *
     * @param pts The array [x0, y0, x1, y1, ...] of points to transform.
     */
    public void mapPoints(float[] pts) {
        mapPoints(pts, 0, pts, 0, pts.length >> 1);
    }

    /**
     * Apply this matrix to the array of 2D vectors, and write the transformed vectors back into the
     * array. Note: this method does not apply the translation associated with the matrix. Use
     * {@link Matrix#mapPoints(float[])} if you want the translation to be applied.
     *
     * @param vecs The array [x0, y0, x1, y1, ...] of vectors to transform.
     */
    public void mapVectors(float[] vecs) {
        mapVectors(vecs, 0, vecs, 0, vecs.length >> 1);
    }

    /**
     * Apply this matrix to the src rectangle, and write the transformed rectangle into dst. This is
     * accomplished by transforming the 4 corners of src, and then setting dst to the bounds of
     * those points.
     *
     * @param dst Where the transformed rectangle is written.
     * @param src The original rectangle to be transformed.
     * @return the result of calling rectStaysRect()
     */
    public boolean mapRect(RectF dst, RectF src) {
        if (dst == null || src == null) {
            throw new NullPointerException();
        }
        return nMapRect(native_instance, dst, src);
    }

    /**
     * Apply this matrix to the rectangle, and write the transformed rectangle back into it. This is
     * accomplished by transforming the 4 corners of rect, and then setting it to the bounds of
     * those points
     *
     * @param rect The rectangle to transform.
     * @return the result of calling rectStaysRect()
     */
    public boolean mapRect(RectF rect) {
        return mapRect(rect, rect);
    }

    /**
     * Return the mean radius of a circle after it has been mapped by this matrix. NOTE: in
     * perspective this value assumes the circle has its center at the origin.
     */
    public float mapRadius(float radius) {
        return nMapRadius(native_instance, radius);
    }

    /**
     * Copy 9 values from the matrix into the array.
     */
    public void getValues(float[] values) {
        if (values.length < 9) {
            throw new ArrayIndexOutOfBoundsException();
        }
        nGetValues(native_instance, values);
    }

    /**
     * Copy 9 values from the array into the matrix. Depending on the implementation of Matrix,
     * these may be transformed into 16.16 integers in the Matrix, such that a subsequent call to
     * getValues() will not yield exactly the same values.
     */
    public void setValues(float[] values) {
        if (values.length < 9) {
            throw new ArrayIndexOutOfBoundsException();
        }
        nSetValues(native_instance, values);
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

    private void toShortString(StringBuilder sb) {
        float[] values = new float[9];
        getValues(values);
        sb.append('[');
        sb.append(values[0]);
        sb.append(", ");
        sb.append(values[1]);
        sb.append(", ");
        sb.append(values[2]);
        sb.append("][");
        sb.append(values[3]);
        sb.append(", ");
        sb.append(values[4]);
        sb.append(", ");
        sb.append(values[5]);
        sb.append("][");
        sb.append(values[6]);
        sb.append(", ");
        sb.append(values[7]);
        sb.append(", ");
        sb.append(values[8]);
        sb.append(']');
    }

    /**
     * Dumps a human-readable shortened string of the matrix into the given
     * stream
     *
     * @param pw The {@link PrintWriter} into which the string representation of
     *           the matrix will be written.
     */
    public final void dump(@NonNull PrintWriter pw) {
        float[] values = new float[9];
        getValues(values);
        pw.print('[');
        pw.print(values[0]);
        pw.print(", ");
        pw.print(values[1]);
        pw.print(", ");
        pw.print(values[2]);
        pw.print("][");
        pw.print(values[3]);
        pw.print(", ");
        pw.print(values[4]);
        pw.print(", ");
        pw.print(values[5]);
        pw.print("][");
        pw.print(values[6]);
        pw.print(", ");
        pw.print(values[7]);
        pw.print(", ");
        pw.print(values[8]);
        pw.print(']');

    }

    /**
     *  @hide For access by android.graphics.pdf but must not be accessed outside the module.
     *  FIXME: PdfRenderer accesses it, but the plan is to leave it out of the module.
     */
    public final long ni() {
        return native_instance;
    }

    // ------------------ Fast JNI ------------------------

    @FastNative
    private static native boolean nSetRectToRect(long nObject,
            RectF src, RectF dst, int stf);
    @FastNative
    private static native boolean nSetPolyToPoly(long nObject,
            float[] src, int srcIndex, float[] dst, int dstIndex, int pointCount);
    @FastNative
    private static native void nMapPoints(long nObject,
            float[] dst, int dstIndex, float[] src, int srcIndex,
            int ptCount, boolean isPts);
    @FastNative
    private static native boolean nMapRect(long nObject, RectF dst, RectF src);
    @FastNative
    private static native void nGetValues(long nObject, float[] values);
    @FastNative
    private static native void nSetValues(long nObject, float[] values);


    // ------------------ Critical JNI ------------------------

    @CriticalNative
    private static native boolean nIsIdentity(long nObject);
    @CriticalNative
    private static native boolean nIsAffine(long nObject);
    @CriticalNative
    private static native boolean nRectStaysRect(long nObject);
    @CriticalNative
    private static native void nReset(long nObject);
    @CriticalNative
    private static native void nSet(long nObject, long nOther);
    @CriticalNative
    private static native void nSetTranslate(long nObject, float dx, float dy);
    @CriticalNative
    private static native void nSetScale(long nObject, float sx, float sy, float px, float py);
    @CriticalNative
    private static native void nSetScale(long nObject, float sx, float sy);
    @CriticalNative
    private static native void nSetRotate(long nObject, float degrees, float px, float py);
    @CriticalNative
    private static native void nSetRotate(long nObject, float degrees);
    @CriticalNative
    private static native void nSetSinCos(long nObject, float sinValue, float cosValue,
            float px, float py);
    @CriticalNative
    private static native void nSetSinCos(long nObject, float sinValue, float cosValue);
    @CriticalNative
    private static native void nSetSkew(long nObject, float kx, float ky, float px, float py);
    @CriticalNative
    private static native void nSetSkew(long nObject, float kx, float ky);
    @CriticalNative
    private static native void nSetConcat(long nObject, long nA, long nB);
    @CriticalNative
    private static native void nPreTranslate(long nObject, float dx, float dy);
    @CriticalNative
    private static native void nPreScale(long nObject, float sx, float sy, float px, float py);
    @CriticalNative
    private static native void nPreScale(long nObject, float sx, float sy);
    @CriticalNative
    private static native void nPreRotate(long nObject, float degrees, float px, float py);
    @CriticalNative
    private static native void nPreRotate(long nObject, float degrees);
    @CriticalNative
    private static native void nPreSkew(long nObject, float kx, float ky, float px, float py);
    @CriticalNative
    private static native void nPreSkew(long nObject, float kx, float ky);
    @CriticalNative
    private static native void nPreConcat(long nObject, long nOther_matrix);
    @CriticalNative
    private static native void nPostTranslate(long nObject, float dx, float dy);
    @CriticalNative
    private static native void nPostScale(long nObject, float sx, float sy, float px, float py);
    @CriticalNative
    private static native void nPostScale(long nObject, float sx, float sy);
    @CriticalNative
    private static native void nPostRotate(long nObject, float degrees, float px, float py);
    @CriticalNative
    private static native void nPostRotate(long nObject, float degrees);
    @CriticalNative
    private static native void nPostSkew(long nObject, float kx, float ky, float px, float py);
    @CriticalNative
    private static native void nPostSkew(long nObject, float kx, float ky);
    @CriticalNative
    private static native void nPostConcat(long nObject, long nOther_matrix);
    @CriticalNative
    private static native boolean nInvert(long nObject, long nInverse);
    @CriticalNative
    private static native float nMapRadius(long nObject, float radius);
    @CriticalNative
    private static native boolean nEquals(long nA, long nB);

    /**
     * Due to b/337329128, native methods that are called by the static initializers cannot be
     * in the same class when running on a host side JVM (such as on Ravenwood and Android Studio).
     *
     * There are two methods that are called by the static initializers (either directly or
     * indirectly) in this class, namely nCreate() and nGetNativeFinalizer(). On Ravenwood
     * these methods can't be on the Matrix class itself, so we use a nested class to host them.
     */
    private static class ExtraNatives {
        static native long nCreate(long nSrc_or_zero);
        static native long nGetNativeFinalizer();
    }
}
