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

package android.view.animation;

import android.annotation.FloatRange;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;

import java.io.PrintWriter;

/**
 * Defines the transformation to be applied at
 * one point in time of an Animation.
 *
 */
public class Transformation {
    /**
     * Indicates a transformation that has no effect (alpha = 1 and identity matrix.)
     */
    public static final int TYPE_IDENTITY = 0x0;
    /**
     * Indicates a transformation that applies an alpha only (uses an identity matrix.)
     */
    public static final int TYPE_ALPHA = 0x1;
    /**
     * Indicates a transformation that applies a matrix only (alpha = 1.)
     */
    public static final int TYPE_MATRIX = 0x2;
    /**
     * Indicates a transformation that applies an alpha and a matrix.
     */
    public static final int TYPE_BOTH = TYPE_ALPHA | TYPE_MATRIX;

    protected Matrix mMatrix;
    protected float mAlpha;
    protected int mTransformationType;

    private boolean mHasClipRect;
    private Rect mClipRect = new Rect();

    private Insets mInsets = Insets.NONE;

    /**
     * Creates a new transformation with alpha = 1 and the identity matrix.
     */
    public Transformation() {
        clear();
    }

    /**
     * Reset the transformation to a state that leaves the object
     * being animated in an unmodified state. The transformation type is
     * {@link #TYPE_BOTH} by default.
     */
    public void clear() {
        if (mMatrix == null) {
            mMatrix = new Matrix();
        } else {
            mMatrix.reset();
        }
        mClipRect.setEmpty();
        mHasClipRect = false;
        mAlpha = 1.0f;
        mTransformationType = TYPE_BOTH;
        mInsets = Insets.NONE;
    }

    /**
     * Indicates the nature of this transformation.
     *
     * @return {@link #TYPE_ALPHA}, {@link #TYPE_MATRIX},
     *         {@link #TYPE_BOTH} or {@link #TYPE_IDENTITY}.
     */
    public int getTransformationType() {
        return mTransformationType;
    }

    /**
     * Sets the transformation type.
     *
     * @param transformationType One of {@link #TYPE_ALPHA},
     *        {@link #TYPE_MATRIX}, {@link #TYPE_BOTH} or
     *        {@link #TYPE_IDENTITY}.
     */
    public void setTransformationType(int transformationType) {
        mTransformationType = transformationType;
    }

    /**
     * Clones the specified transformation.
     *
     * @param t The transformation to clone.
     */
    public void set(Transformation t) {
        mAlpha = t.getAlpha();
        mMatrix.set(t.getMatrix());
        if (t.mHasClipRect) {
            setClipRect(t.getClipRect());
        } else {
            mHasClipRect = false;
            mClipRect.setEmpty();
        }
        mTransformationType = t.getTransformationType();
    }

    /**
     * Apply this Transformation to an existing Transformation, e.g. apply
     * a scale effect to something that has already been rotated.
     * @param t
     */
    public void compose(Transformation t) {
        mAlpha *= t.getAlpha();
        mMatrix.preConcat(t.getMatrix());
        if (t.mHasClipRect) {
            Rect bounds = t.getClipRect();
            if (mHasClipRect) {
                setClipRect(mClipRect.left + bounds.left, mClipRect.top + bounds.top,
                        mClipRect.right + bounds.right, mClipRect.bottom + bounds.bottom);
            } else {
                setClipRect(bounds);
            }
        }
        setInsets(Insets.add(getInsets(), t.getInsets()));
    }

    /**
     * Like {@link #compose(Transformation)} but does this.postConcat(t) of
     * the transformation matrix.
     * @hide
     */
    public void postCompose(Transformation t) {
        mAlpha *= t.getAlpha();
        mMatrix.postConcat(t.getMatrix());
        if (t.mHasClipRect) {
            Rect bounds = t.getClipRect();
            if (mHasClipRect) {
                setClipRect(mClipRect.left + bounds.left, mClipRect.top + bounds.top,
                        mClipRect.right + bounds.right, mClipRect.bottom + bounds.bottom);
            } else {
                setClipRect(bounds);
            }
        }
    }

    /**
     * @return The 3x3 Matrix representing the transformation to apply to the
     * coordinates of the object being animated
     */
    public Matrix getMatrix() {
        return mMatrix;
    }

    /**
     * Sets the degree of transparency
     * @param alpha 1.0 means fully opaque and 0.0 means fully transparent
     */
    public void setAlpha(@FloatRange(from=0.0, to=1.0) float alpha) {
        mAlpha = alpha;
    }

    /**
     * @return The degree of transparency
     */
    public float getAlpha() {
        return mAlpha;
    }

    /**
     * Sets the current Transform's clip rect
     * @hide
     */
    public void setClipRect(Rect r) {
        setClipRect(r.left, r.top, r.right, r.bottom);
    }

    /**
     * Sets the current Transform's clip rect
     * @hide
     */
    public void setClipRect(int l, int t, int r, int b) {
        mClipRect.set(l, t, r, b);
        mHasClipRect = true;
    }

    /**
     * Returns the current Transform's clip rect
     * @hide
     */
    public Rect getClipRect() {
        return mClipRect;
    }

    /**
     * Returns whether the current Transform's clip rect is set
     * @hide
     */
    public boolean hasClipRect() {
        return mHasClipRect;
    }

    /**
     * Sets the current Transform's insets
     * @hide
     */
    public void setInsets(Insets insets) {
        mInsets = insets;
    }

    /**
     * Sets the current Transform's insets
     * @hide
     */
    public void setInsets(int left, int top, int right, int bottom) {
        mInsets = Insets.of(left, top, right, bottom);
    }

    /**
     * Returns the current Transform's outset rect
     * @hide
     */
    public Insets getInsets() {
        return mInsets;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("Transformation");
        toShortString(sb);
        return sb.toString();
    }

    /**
     * Return a string representation of the transformation in a compact form.
     */
    public String toShortString() {
        StringBuilder sb = new StringBuilder(64);
        toShortString(sb);
        return sb.toString();
    }

    /**
     * @hide
     */
    public void toShortString(StringBuilder sb) {
        sb.append("{alpha="); sb.append(mAlpha);
        sb.append(" matrix="); sb.append(mMatrix.toShortString());
        sb.append('}');
    }

    /**
     * Print short string, to optimize dumping.
     * @hide
     */
    @UnsupportedAppUsage
    public void printShortString(PrintWriter pw) {
        pw.print("{alpha="); pw.print(mAlpha);
        pw.print(" matrix=");
        mMatrix.dump(pw);
        pw.print('}');
    }
}
