/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.drawable.Drawable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines a simple shape, used for bounding graphical regions.
 * <p>
 * Can be computed for a View, or computed by a Drawable, to drive the shape of
 * shadows cast by a View, or to clip the contents of the View.
 *
 * @see android.view.ViewOutlineProvider
 * @see android.view.View#setOutlineProvider(android.view.ViewOutlineProvider)
 * @see Drawable#getOutline(Outline)
 */
public final class Outline {
    private static final float RADIUS_UNDEFINED = Float.NEGATIVE_INFINITY;

    /** @hide */
    public static final int MODE_EMPTY = 0;
    /** @hide */
    public static final int MODE_ROUND_RECT = 1;
    /** @hide */
    public static final int MODE_CONVEX_PATH = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false,
            value = {
                    MODE_EMPTY,
                    MODE_ROUND_RECT,
                    MODE_CONVEX_PATH,
            })
    public @interface Mode {}

    /** @hide */
    @Mode
    public int mMode = MODE_EMPTY;

    /** @hide */
    public final Path mPath = new Path();

    /** @hide */
    public final Rect mRect = new Rect();
    /** @hide */
    public float mRadius = RADIUS_UNDEFINED;
    /** @hide */
    public float mAlpha;

    /**
     * Constructs an empty Outline. Call one of the setter methods to make
     * the outline valid for use with a View.
     */
    public Outline() {}

    /**
     * Constructs an Outline with a copy of the data in src.
     */
    public Outline(@NonNull Outline src) {
        set(src);
    }

    /**
     * Sets the outline to be empty.
     *
     * @see #isEmpty()
     */
    public void setEmpty() {
        mMode = MODE_EMPTY;
        mPath.rewind();
        mRect.setEmpty();
        mRadius = RADIUS_UNDEFINED;
    }

    /**
     * Returns whether the Outline is empty.
     * <p>
     * Outlines are empty when constructed, or if {@link #setEmpty()} is called,
     * until a setter method is called
     *
     * @see #setEmpty()
     */
    public boolean isEmpty() {
        return mMode == MODE_EMPTY;
    }


    /**
     * Returns whether the outline can be used to clip a View.
     * <p>
     * Currently, only Outlines that can be represented as a rectangle, circle,
     * or round rect support clipping.
     *
     * @see {@link android.view.View#setClipToOutline(boolean)}
     */
    public boolean canClip() {
        return mMode != MODE_CONVEX_PATH;
    }

    /**
     * Sets the alpha represented by the Outline - the degree to which the
     * producer is guaranteed to be opaque over the Outline's shape.
     * <p>
     * An alpha value of <code>0.0f</code> either represents completely
     * transparent content, or content that isn't guaranteed to fill the shape
     * it publishes.
     * <p>
     * Content producing a fully opaque (alpha = <code>1.0f</code>) outline is
     * assumed by the drawing system to fully cover content beneath it,
     * meaning content beneath may be optimized away.
     */
    public void setAlpha(@FloatRange(from=0.0, to=1.0) float alpha) {
        mAlpha = alpha;
    }

    /**
     * Returns the alpha represented by the Outline.
     */
    public float getAlpha() {
        return mAlpha;
    }

    /**
     * Replace the contents of this Outline with the contents of src.
     *
     * @param src Source outline to copy from.
     */
    public void set(@NonNull Outline src) {
        mMode = src.mMode;
        mPath.set(src.mPath);
        mRect.set(src.mRect);
        mRadius = src.mRadius;
        mAlpha = src.mAlpha;
    }

    /**
     * Sets the Outline to the rounded rect defined by the input rect, and
     * corner radius.
     */
    public void setRect(int left, int top, int right, int bottom) {
        setRoundRect(left, top, right, bottom, 0.0f);
    }

    /**
     * Convenience for {@link #setRect(int, int, int, int)}
     */
    public void setRect(@NonNull Rect rect) {
        setRect(rect.left, rect.top, rect.right, rect.bottom);
    }

    /**
     * Sets the Outline to the rounded rect defined by the input rect, and corner radius.
     * <p>
     * Passing a zero radius is equivalent to calling {@link #setRect(int, int, int, int)}
     */
    public void setRoundRect(int left, int top, int right, int bottom, float radius) {
        if (left >= right || top >= bottom) {
            setEmpty();
            return;
        }

        mMode = MODE_ROUND_RECT;
        mRect.set(left, top, right, bottom);
        mRadius = radius;
        mPath.rewind();
    }

    /**
     * Convenience for {@link #setRoundRect(int, int, int, int, float)}
     */
    public void setRoundRect(@NonNull Rect rect, float radius) {
        setRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius);
    }

    /**
     * Populates {@code outBounds} with the outline bounds, if set, and returns
     * {@code true}. If no outline bounds are set, or if a path has been set
     * via {@link #setConvexPath(Path)}, returns {@code false}.
     *
     * @param outRect the rect to populate with the outline bounds, if set
     * @return {@code true} if {@code outBounds} was populated with outline
     *         bounds, or {@code false} if no outline bounds are set
     */
    public boolean getRect(@NonNull Rect outRect) {
        if (mMode != MODE_ROUND_RECT) {
            return false;
        }
        outRect.set(mRect);
        return true;
    }

    /**
     * Returns the rounded rect radius, if set, or a value less than 0 if a path has
     * been set via {@link #setConvexPath(Path)}. A return value of {@code 0}
     * indicates a non-rounded rect.
     *
     * @return the rounded rect radius, or value < 0
     */
    public float getRadius() {
        return mRadius;
    }

    /**
     * Sets the outline to the oval defined by input rect.
     */
    public void setOval(int left, int top, int right, int bottom) {
        if (left >= right || top >= bottom) {
            setEmpty();
            return;
        }

        if ((bottom - top) == (right - left)) {
            // represent circle as round rect, for efficiency, and to enable clipping
            setRoundRect(left, top, right, bottom, (bottom - top) / 2.0f);
            return;
        }

        mMode = MODE_CONVEX_PATH;
        mPath.rewind();
        mPath.addOval(left, top, right, bottom, Path.Direction.CW);
        mRect.setEmpty();
        mRadius = RADIUS_UNDEFINED;
    }

    /**
     * Convenience for {@link #setOval(int, int, int, int)}
     */
    public void setOval(@NonNull Rect rect) {
        setOval(rect.left, rect.top, rect.right, rect.bottom);
    }

    /**
     * Sets the Constructs an Outline from a
     * {@link android.graphics.Path#isConvex() convex path}.
     */
    public void setConvexPath(@NonNull Path convexPath) {
        if (convexPath.isEmpty()) {
            setEmpty();
            return;
        }

        if (!convexPath.isConvex()) {
            throw new IllegalArgumentException("path must be convex");
        }

        mMode = MODE_CONVEX_PATH;
        mPath.set(convexPath);
        mRect.setEmpty();
        mRadius = RADIUS_UNDEFINED;
    }

    /**
     * Offsets the Outline by (dx,dy)
     */
    public void offset(int dx, int dy) {
        if (mMode == MODE_ROUND_RECT) {
            mRect.offset(dx, dy);
        } else if (mMode == MODE_CONVEX_PATH) {
            mPath.offset(dx, dy);
        }
    }
}
