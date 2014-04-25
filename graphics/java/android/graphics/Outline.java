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

import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Defines an area of content.
 *
 * Can be used with a View, or computed by a Drawable, to drive the shape of shadows cast by a
 * View, and allowing Views to clip inner content.
 *
 * @see View#setOutline(Outline)
 * @see Drawable#getOutline(Outline)
 */
public final class Outline {
    /** @hide */
    public Rect mRect;

    /** @hide */
    public float mRadius;

    /** @hide */
    public Path mPath;

    /**
     * Constructs an invalid Outline. Call one of the setter methods to make
     * the outline valid for use with a View.
     */
    public Outline() {}

    /**
     * Constructs an Outline with a copy of the data in src.
     */
    public Outline(Outline src) {
        set(src);
    }

    /** @hide */
    public void markInvalid() {
        mRadius = 0;
        mRect = null;
        mPath = null;
    }

    /**
     * Returns whether the Outline is valid for use with a View.
     * <p>
     * Outlines are invalid when constructed until a setter method is called.
     */
    public boolean isValid() {
        return mRect != null || mPath != null;
    }

    /**
     * Replace the contents of this Outline with the contents of src.
     */
    public void set(Outline src) {
        if (src.mPath != null) {
            if (mPath == null) {
                mPath = new Path();
            }
            mPath.set(src.mPath);
            mRect = null;
        }
        if (src.mRect != null) {
            if (mRect == null) {
                mRect = new Rect();
            }
            mRect.set(src.mRect);
        }
        mRadius = src.mRadius;
    }

    /**
     * Sets the Outline to the rounded rect defined by the input rect, and corner radius.
     */
    public void setRect(int left, int top, int right, int bottom) {
        setRoundRect(left, top, right, bottom, 0.0f);
    }

    /**
     * Convenience for {@link #setRect(int, int, int, int)}
     */
    public void setRect(Rect rect) {
        setRect(rect.left, rect.top, rect.right, rect.bottom);
    }

    /**
     * Sets the Outline to the rounded rect defined by the input rect, and corner radius.
     */
    public void setRoundRect(int left, int top, int right, int bottom, float radius) {
        if (mRect == null) mRect = new Rect();
        mRect.set(left, top, right, bottom);
        mRadius = radius;
        mPath = null;
    }

    /**
     * Convenience for {@link #setRoundRect(int, int, int, int, float)}
     * @param rect
     * @param radius
     */
    public void setRoundRect(Rect rect, float radius) {
        setRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius);
    }

    /**
     * Sets the Constructs an Outline from a {@link android.graphics.Path#isConvex() convex path}.
     */
    public void setConvexPath(Path convexPath) {
        if (!convexPath.isConvex()) {
            throw new IllegalArgumentException("path must be convex");
        }
        if (mPath == null) mPath = new Path();

        mRect = null;
        mRadius = -1.0f;
        mPath.set(convexPath);
    }
}
