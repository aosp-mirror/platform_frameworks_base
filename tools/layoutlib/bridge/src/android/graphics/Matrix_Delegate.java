/*
 * Copyright (C) 2010 The Android Open Source Project
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


import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.graphics.Matrix.ScaleToFit;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;

/**
 * Delegate implementing the native methods of android.graphics.Matrix
 *
 * Through the layoutlib_create tool, the original native methods of Matrix have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original Matrix class.
 *
 * @see DelegateManager
 *
 */
public final class Matrix_Delegate {

    private final static int MATRIX_SIZE = 9;

    // ---- delegate manager ----
    private static final DelegateManager<Matrix_Delegate> sManager =
            new DelegateManager<Matrix_Delegate>(Matrix_Delegate.class);

    // ---- delegate data ----
    private float mValues[] = new float[MATRIX_SIZE];

    // ---- Public Helper methods ----

    public static Matrix_Delegate getDelegate(int native_instance) {
        return sManager.getDelegate(native_instance);
    }

    /**
     * Returns an {@link AffineTransform} matching the given Matrix.
     */
    public static AffineTransform getAffineTransform(Matrix m) {
        Matrix_Delegate delegate = sManager.getDelegate(m.native_instance);
        if (delegate == null) {
            return null;
        }

        return delegate.getAffineTransform();
    }

    public static boolean hasPerspective(Matrix m) {
        Matrix_Delegate delegate = sManager.getDelegate(m.native_instance);
        if (delegate == null) {
            return false;
        }

        return delegate.hasPerspective();
    }

    /**
     * Sets the content of the matrix with the content of another matrix.
     */
    public void set(Matrix_Delegate matrix) {
        System.arraycopy(matrix.mValues, 0, mValues, 0, MATRIX_SIZE);
    }

    /**
     * Sets the content of the matrix with the content of another matrix represented as an array
     * of values.
     */
    public void set(float[] values) {
        System.arraycopy(values, 0, mValues, 0, MATRIX_SIZE);
    }

    /**
     * Resets the matrix to be the identity matrix.
     */
    public void reset() {
        reset(mValues);
    }

    /**
     * Returns whether or not the matrix is identity.
     */
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

    public static float[] makeValues(AffineTransform matrix) {
        float[] values = new float[MATRIX_SIZE];
        values[0] = (float) matrix.getScaleX();
        values[1] = (float) matrix.getShearX();
        values[2] = (float) matrix.getTranslateX();
        values[3] = (float) matrix.getShearY();
        values[4] = (float) matrix.getScaleY();
        values[5] = (float) matrix.getTranslateY();
        values[6] = 0.f;
        values[7] = 0.f;
        values[8] = 1.f;

        return values;
    }

    public static Matrix_Delegate make(AffineTransform matrix) {
        return new Matrix_Delegate(makeValues(matrix));
    }

    public boolean mapRect(RectF dst, RectF src) {
        // array with 4 corners
        float[] corners = new float[] {
                src.left, src.top,
                src.right, src.top,
                src.right, src.bottom,
                src.left, src.bottom,
        };

        // apply the transform to them.
        mapPoints(corners);

        // now put the result in the rect. We take the min/max of Xs and min/max of Ys
        dst.left = Math.min(Math.min(corners[0], corners[2]), Math.min(corners[4], corners[6]));
        dst.right = Math.max(Math.max(corners[0], corners[2]), Math.max(corners[4], corners[6]));

        dst.top = Math.min(Math.min(corners[1], corners[3]), Math.min(corners[5], corners[7]));
        dst.bottom = Math.max(Math.max(corners[1], corners[3]), Math.max(corners[5], corners[7]));


        return (computeTypeMask() & kRectStaysRect_Mask) != 0;
    }


    /**
     * Returns an {@link AffineTransform} matching the matrix.
     */
    public AffineTransform getAffineTransform() {
        return getAffineTransform(mValues);
    }

    public boolean hasPerspective() {
        return (mValues[6] != 0 || mValues[7] != 0 || mValues[8] != 1);
    }



    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static int native_create(int native_src_or_zero) {
        // create the delegate
        Matrix_Delegate newDelegate = new Matrix_Delegate();

        // copy from values if needed.
        if (native_src_or_zero > 0) {
            Matrix_Delegate oldDelegate = sManager.getDelegate(native_src_or_zero);
            if (oldDelegate != null) {
                System.arraycopy(
                        oldDelegate.mValues, 0,
                        newDelegate.mValues, 0,
                        MATRIX_SIZE);
            }
        }

        return sManager.addNewDelegate(newDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_isIdentity(int native_object) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        return d.isIdentity();
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_rectStaysRect(int native_object) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return true;
        }

        return (d.computeTypeMask() & kRectStaysRect_Mask) != 0;
    }

    @LayoutlibDelegate
    /*package*/ static void native_reset(int native_object) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        reset(d.mValues);
    }

    @LayoutlibDelegate
    /*package*/ static void native_set(int native_object, int other) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        Matrix_Delegate src = sManager.getDelegate(other);
        if (src == null) {
            return;
        }

        System.arraycopy(src.mValues, 0, d.mValues, 0, MATRIX_SIZE);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setTranslate(int native_object, float dx, float dy) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        setTranslate(d.mValues, dx, dy);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setScale(int native_object, float sx, float sy,
            float px, float py) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        d.mValues = getScale(sx, sy, px, py);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setScale(int native_object, float sx, float sy) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        d.mValues[0] = sx;
        d.mValues[1] = 0;
        d.mValues[2] = 0;
        d.mValues[3] = 0;
        d.mValues[4] = sy;
        d.mValues[5] = 0;
        d.mValues[6] = 0;
        d.mValues[7] = 0;
        d.mValues[8] = 1;
    }

    @LayoutlibDelegate
    /*package*/ static void native_setRotate(int native_object, float degrees, float px, float py) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        d.mValues = getRotate(degrees, px, py);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setRotate(int native_object, float degrees) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        setRotate(d.mValues, degrees);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setSinCos(int native_object, float sinValue, float cosValue,
            float px, float py) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        // TODO: do it in one pass

        // translate so that the pivot is in 0,0
        setTranslate(d.mValues, -px, -py);

        // scale
        d.postTransform(getRotate(sinValue, cosValue));
        // translate back the pivot
        d.postTransform(getTranslate(px, py));
    }

    @LayoutlibDelegate
    /*package*/ static void native_setSinCos(int native_object, float sinValue, float cosValue) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        setRotate(d.mValues, sinValue, cosValue);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setSkew(int native_object, float kx, float ky,
            float px, float py) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        d.mValues = getSkew(kx, ky, px, py);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setSkew(int native_object, float kx, float ky) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        d.mValues[0] = 1;
        d.mValues[1] = kx;
        d.mValues[2] = -0;
        d.mValues[3] = ky;
        d.mValues[4] = 1;
        d.mValues[5] = 0;
        d.mValues[6] = 0;
        d.mValues[7] = 0;
        d.mValues[8] = 1;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_setConcat(int native_object, int a, int b) {
        if (a == native_object) {
            return native_preConcat(native_object, b);
        } else if (b == native_object) {
            return native_postConcat(native_object, a);
        }

        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        Matrix_Delegate a_mtx = sManager.getDelegate(a);
        if (a_mtx == null) {
            return false;
        }

        Matrix_Delegate b_mtx = sManager.getDelegate(b);
        if (b_mtx == null) {
            return false;
        }

        multiply(d.mValues, a_mtx.mValues, b_mtx.mValues);

        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_preTranslate(int native_object, float dx, float dy) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.preTransform(getTranslate(dx, dy));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_preScale(int native_object, float sx, float sy,
            float px, float py) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.preTransform(getScale(sx, sy, px, py));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_preScale(int native_object, float sx, float sy) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.preTransform(getScale(sx, sy));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_preRotate(int native_object, float degrees,
            float px, float py) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.preTransform(getRotate(degrees, px, py));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_preRotate(int native_object, float degrees) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        double rad = Math.toRadians(degrees);
        float sin = (float)Math.sin(rad);
        float cos = (float)Math.cos(rad);

        d.preTransform(getRotate(sin, cos));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_preSkew(int native_object, float kx, float ky,
            float px, float py) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.preTransform(getSkew(kx, ky, px, py));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_preSkew(int native_object, float kx, float ky) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.preTransform(getSkew(kx, ky));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_preConcat(int native_object, int other_matrix) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        Matrix_Delegate other = sManager.getDelegate(other_matrix);
        if (other == null) {
            return false;
        }

        d.preTransform(other.mValues);
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_postTranslate(int native_object, float dx, float dy) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.postTransform(getTranslate(dx, dy));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_postScale(int native_object, float sx, float sy,
            float px, float py) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.postTransform(getScale(sx, sy, px, py));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_postScale(int native_object, float sx, float sy) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.postTransform(getScale(sx, sy));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_postRotate(int native_object, float degrees,
            float px, float py) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.postTransform(getRotate(degrees, px, py));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_postRotate(int native_object, float degrees) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.postTransform(getRotate(degrees));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_postSkew(int native_object, float kx, float ky,
            float px, float py) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.postTransform(getSkew(kx, ky, px, py));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_postSkew(int native_object, float kx, float ky) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        d.postTransform(getSkew(kx, ky));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_postConcat(int native_object, int other_matrix) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        Matrix_Delegate other = sManager.getDelegate(other_matrix);
        if (other == null) {
            return false;
        }

        d.postTransform(other.mValues);
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_setRectToRect(int native_object, RectF src,
            RectF dst, int stf) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        if (src.isEmpty()) {
            reset(d.mValues);
            return false;
        }

        if (dst.isEmpty()) {
            d.mValues[0] = d.mValues[1] = d.mValues[2] = d.mValues[3] = d.mValues[4] = d.mValues[5]
               = d.mValues[6] = d.mValues[7] = 0;
            d.mValues[8] = 1;
        } else {
            float    tx, sx = dst.width() / src.width();
            float    ty, sy = dst.height() / src.height();
            boolean  xLarger = false;

            if (stf != ScaleToFit.FILL.nativeInt) {
                if (sx > sy) {
                    xLarger = true;
                    sx = sy;
                } else {
                    sy = sx;
                }
            }

            tx = dst.left - src.left * sx;
            ty = dst.top - src.top * sy;
            if (stf == ScaleToFit.CENTER.nativeInt || stf == ScaleToFit.END.nativeInt) {
                float diff;

                if (xLarger) {
                    diff = dst.width() - src.width() * sy;
                } else {
                    diff = dst.height() - src.height() * sy;
                }

                if (stf == ScaleToFit.CENTER.nativeInt) {
                    diff = diff / 2;
                }

                if (xLarger) {
                    tx += diff;
                } else {
                    ty += diff;
                }
            }

            d.mValues[0] = sx;
            d.mValues[4] = sy;
            d.mValues[2] = tx;
            d.mValues[5] = ty;
            d.mValues[1]  = d.mValues[3] = d.mValues[6] = d.mValues[7] = 0;

        }
        // shared cleanup
        d.mValues[8] = 1;
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_setPolyToPoly(int native_object, float[] src, int srcIndex,
            float[] dst, int dstIndex, int pointCount) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Matrix.setPolyToPoly is not supported.",
                null, null /*data*/);
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_invert(int native_object, int inverse) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        Matrix_Delegate inv_mtx = sManager.getDelegate(inverse);
        if (inv_mtx == null) {
            return false;
        }

        try {
            AffineTransform affineTransform = d.getAffineTransform();
            AffineTransform inverseTransform = affineTransform.createInverse();
            inv_mtx.mValues[0] = (float)inverseTransform.getScaleX();
            inv_mtx.mValues[1] = (float)inverseTransform.getShearX();
            inv_mtx.mValues[2] = (float)inverseTransform.getTranslateX();
            inv_mtx.mValues[3] = (float)inverseTransform.getScaleX();
            inv_mtx.mValues[4] = (float)inverseTransform.getShearY();
            inv_mtx.mValues[5] = (float)inverseTransform.getTranslateY();

            return true;
        } catch (NoninvertibleTransformException e) {
            return false;
        }
    }

    @LayoutlibDelegate
    /*package*/ static void native_mapPoints(int native_object, float[] dst, int dstIndex,
            float[] src, int srcIndex, int ptCount, boolean isPts) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        if (isPts) {
            d.mapPoints(dst, dstIndex, src, srcIndex, ptCount);
        } else {
            d.mapVectors(dst, dstIndex, src, srcIndex, ptCount);
        }
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_mapRect(int native_object, RectF dst, RectF src) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return false;
        }

        return d.mapRect(dst, src);
    }

    @LayoutlibDelegate
    /*package*/ static float native_mapRadius(int native_object, float radius) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return 0.f;
        }

        float[] src = new float[] { radius, 0.f, 0.f, radius };
        d.mapVectors(src, 0, src, 0, 2);

        float l1 = getPointLength(src, 0);
        float l2 = getPointLength(src, 2);

        return (float) Math.sqrt(l1 * l2);
    }

    @LayoutlibDelegate
    /*package*/ static void native_getValues(int native_object, float[] values) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        System.arraycopy(d.mValues, 0, d.mValues, 0, MATRIX_SIZE);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setValues(int native_object, float[] values) {
        Matrix_Delegate d = sManager.getDelegate(native_object);
        if (d == null) {
            return;
        }

        System.arraycopy(values, 0, d.mValues, 0, MATRIX_SIZE);
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_equals(int native_a, int native_b) {
        Matrix_Delegate a = sManager.getDelegate(native_a);
        if (a == null) {
            return false;
        }

        Matrix_Delegate b = sManager.getDelegate(native_b);
        if (b == null) {
            return false;
        }

        for (int i = 0 ; i < MATRIX_SIZE ; i++) {
            if (a.mValues[i] != b.mValues[i]) {
                return false;
            }
        }

        return true;
    }

    @LayoutlibDelegate
    /*package*/ static void finalizer(int native_instance) {
        sManager.removeJavaReferenceFor(native_instance);
    }

    // ---- Private helper methods ----

    /*package*/ static AffineTransform getAffineTransform(float[] matrix) {
        // the AffineTransform constructor takes the value in a different order
        // for a matrix [ 0 1 2 ]
        //              [ 3 4 5 ]
        // the order is 0, 3, 1, 4, 2, 5...
        return new AffineTransform(
                matrix[0], matrix[3], matrix[1],
                matrix[4], matrix[2], matrix[5]);
    }

    /**
     * Reset a matrix to the identity
     */
    private static void reset(float[] mtx) {
        for (int i = 0, k = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++, k++) {
                mtx[k] = ((i==j) ? 1 : 0);
            }
        }
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

    private Matrix_Delegate() {
        reset();
    }

    private Matrix_Delegate(float[] values) {
        System.arraycopy(values, 0, mValues, 0, MATRIX_SIZE);
    }

    /**
     * Adds the given transformation to the current Matrix
     * <p/>This in effect does this = this*matrix
     * @param matrix
     */
    private void postTransform(float[] matrix) {
        float[] tmp = new float[9];
        multiply(tmp, mValues, matrix);
        mValues = tmp;
    }

    /**
     * Adds the given transformation to the current Matrix
     * <p/>This in effect does this = matrix*this
     * @param matrix
     */
    private void preTransform(float[] matrix) {
        float[] tmp = new float[9];
        multiply(tmp, matrix, mValues);
        mValues = tmp;
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

     private void mapPoints(float[] dst, int dstIndex, float[] src, int srcIndex,
                           int pointCount) {
         final int count = pointCount * 2;

         float[] tmpDest = dst;
         boolean inPlace = dst == src;
         if (inPlace) {
             tmpDest = new float[dstIndex + count];
         }

         for (int i = 0 ; i < count ; i += 2) {
             // just in case we are doing in place, we better put this in temp vars
             float x = mValues[0] * src[i + srcIndex] +
                       mValues[1] * src[i + srcIndex + 1] +
                       mValues[2];
             float y = mValues[3] * src[i + srcIndex] +
                       mValues[4] * src[i + srcIndex + 1] +
                       mValues[5];

             tmpDest[i + dstIndex]     = x;
             tmpDest[i + dstIndex + 1] = y;
         }

         if (inPlace) {
             System.arraycopy(tmpDest, dstIndex, dst, dstIndex, count);
         }
     }

     /**
      * Apply this matrix to the array of 2D points, and write the transformed
      * points back into the array
      *
      * @param pts The array [x0, y0, x1, y1, ...] of points to transform.
      */

     private void mapPoints(float[] pts) {
         mapPoints(pts, 0, pts, 0, pts.length >> 1);
     }

     private void mapVectors(float[] dst, int dstIndex, float[] src, int srcIndex, int ptCount) {
         if (hasPerspective()) {
             // transform the (0,0) point
             float[] origin = new float[] { 0.f, 0.f};
             mapPoints(origin);

             // translate the vector data as points
             mapPoints(dst, dstIndex, src, srcIndex, ptCount);

             // then substract the transformed origin.
             final int count = ptCount * 2;
             for (int i = 0 ; i < count ; i += 2) {
                 dst[dstIndex + i] = dst[dstIndex + i] - origin[0];
                 dst[dstIndex + i + 1] = dst[dstIndex + i + 1] - origin[1];
             }
         } else {
             // make a copy of the matrix
             Matrix_Delegate copy = new Matrix_Delegate(mValues);

             // remove the translation
             setTranslate(copy.mValues, 0, 0);

             // map the content as points.
             copy.mapPoints(dst, dstIndex, src, srcIndex, ptCount);
         }
     }

     private static float getPointLength(float[] src, int index) {
         return (float) Math.sqrt(src[index] * src[index] + src[index + 1] * src[index + 1]);
     }

    /**
     * multiply two matrices and store them in a 3rd.
     * <p/>This in effect does dest = a*b
     * dest cannot be the same as a or b.
     */
     /*package*/ static void multiply(float dest[], float[] a, float[] b) {
        // first row
        dest[0] = b[0] * a[0] + b[1] * a[3] + b[2] * a[6];
        dest[1] = b[0] * a[1] + b[1] * a[4] + b[2] * a[7];
        dest[2] = b[0] * a[2] + b[1] * a[5] + b[2] * a[8];

        // 2nd row
        dest[3] = b[3] * a[0] + b[4] * a[3] + b[5] * a[6];
        dest[4] = b[3] * a[1] + b[4] * a[4] + b[5] * a[7];
        dest[5] = b[3] * a[2] + b[4] * a[5] + b[5] * a[8];

        // 3rd row
        dest[6] = b[6] * a[0] + b[7] * a[3] + b[8] * a[6];
        dest[7] = b[6] * a[1] + b[7] * a[4] + b[8] * a[7];
        dest[8] = b[6] * a[2] + b[7] * a[5] + b[8] * a[8];
    }

    /**
     * Returns a matrix that represents a given translate
     * @param dx
     * @param dy
     * @return
     */
    /*package*/ static float[] getTranslate(float dx, float dy) {
        return setTranslate(new float[9], dx, dy);
    }

    /*package*/ static float[] setTranslate(float[] dest, float dx, float dy) {
        dest[0] = 1;
        dest[1] = 0;
        dest[2] = dx;
        dest[3] = 0;
        dest[4] = 1;
        dest[5] = dy;
        dest[6] = 0;
        dest[7] = 0;
        dest[8] = 1;
        return dest;
    }

    /*package*/ static float[] getScale(float sx, float sy) {
        return new float[] { sx, 0, 0, 0, sy, 0, 0, 0, 1 };
    }

    /**
     * Returns a matrix that represents the given scale info.
     * @param sx
     * @param sy
     * @param px
     * @param py
     */
    /*package*/ static float[] getScale(float sx, float sy, float px, float py) {
        float[] tmp = new float[9];
        float[] tmp2 = new float[9];

        // TODO: do it in one pass

        // translate tmp so that the pivot is in 0,0
        setTranslate(tmp, -px, -py);

        // scale into tmp2
        multiply(tmp2, tmp, getScale(sx, sy));

        // translate back the pivot back into tmp
        multiply(tmp, tmp2, getTranslate(px, py));

        return tmp;
    }


    /*package*/ static float[] getRotate(float degrees) {
        double rad = Math.toRadians(degrees);
        float sin = (float)Math.sin(rad);
        float cos = (float)Math.cos(rad);

        return getRotate(sin, cos);
    }

    /*package*/ static float[] getRotate(float sin, float cos) {
        return setRotate(new float[9], sin, cos);
    }

    /*package*/ static float[] setRotate(float[] dest, float degrees) {
        double rad = Math.toRadians(degrees);
        float sin = (float)Math.sin(rad);
        float cos = (float)Math.cos(rad);

        return setRotate(dest, sin, cos);
    }

    /*package*/ static float[] setRotate(float[] dest, float sin, float cos) {
        dest[0] = cos;
        dest[1] = -sin;
        dest[2] = 0;
        dest[3] = sin;
        dest[4] = cos;
        dest[5] = 0;
        dest[6] = 0;
        dest[7] = 0;
        dest[8] = 1;
        return dest;
    }

    /*package*/ static float[] getRotate(float degrees, float px, float py) {
        float[] tmp = new float[9];
        float[] tmp2 = new float[9];

        // TODO: do it in one pass

        // translate so that the pivot is in 0,0
        setTranslate(tmp, -px, -py);

        // rotate into tmp2
        double rad = Math.toRadians(degrees);
        float cos = (float)Math.cos(rad);
        float sin = (float)Math.sin(rad);
        multiply(tmp2, tmp, getRotate(sin, cos));

        // translate back the pivot back into tmp
        multiply(tmp, tmp2, getTranslate(px, py));

        return tmp;
    }

    /*package*/ static float[] getSkew(float kx, float ky) {
        return new float[] { 1, kx, 0, ky, 1, 0, 0, 0, 1 };
    }

    /*package*/ static float[] getSkew(float kx, float ky, float px, float py) {
        float[] tmp = new float[9];
        float[] tmp2 = new float[9];

        // TODO: do it in one pass

        // translate so that the pivot is in 0,0
        setTranslate(tmp, -px, -py);

        // skew into tmp2
        multiply(tmp2, tmp, new float[] { 1, kx, 0, ky, 1, 0, 0, 0, 1 });
        // translate back the pivot back into tmp
        multiply(tmp, tmp2, getTranslate(px, py));

        return tmp;
    }
}
