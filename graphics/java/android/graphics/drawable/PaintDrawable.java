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

package android.graphics.drawable;

import android.graphics.drawable.shapes.RoundRectShape;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import org.xmlpull.v1.XmlPullParser;

/**
 * Drawable that draws its bounds in the given paint, with optional
 * rounded corners.
*/
public class PaintDrawable extends ShapeDrawable {

    public PaintDrawable() {
    }

    public PaintDrawable(int color) {
        getPaint().setColor(color);
    }
    
    /**
     * Specify radius for the corners of the rectangle. If this is > 0, then the
     * drawable is drawn in a round-rectangle, rather than a rectangle.
     * @param radius the radius for the corners of the rectangle
     */
    public void setCornerRadius(float radius) {
        float[] radii = null;
        if (radius > 0) {
            radii = new float[8];
            for (int i = 0; i < 8; i++) {
                radii[i] = radius;
            }
        }
        setCornerRadii(radii);
    }
    
    /**
     * Specify radii for each of the 4 corners. For each corner, the array
     * contains 2 values, [X_radius, Y_radius]. The corners are ordered
     * top-left, top-right, bottom-right, bottom-left
     * @param radii the x and y radii of the corners
     */
    public void setCornerRadii(float[] radii) {
        if (radii == null) {
            if (getShape() != null) {
                setShape(null);
            }
        } else {
            setShape(new RoundRectShape(radii, null, null));
        }
        invalidateSelf();
    }

    @Override
    protected boolean inflateTag(String name, Resources r, XmlPullParser parser,
                                 AttributeSet attrs) {
        if (name.equals("corners")) {
            TypedArray a = r.obtainAttributes(attrs,
                                        com.android.internal.R.styleable.DrawableCorners);
            int radius = a.getDimensionPixelSize(
                                com.android.internal.R.styleable.DrawableCorners_radius, 0);
            setCornerRadius(radius);
            
            // now check of they have any per-corner radii
            
            int topLeftRadius = a.getDimensionPixelSize(
                    com.android.internal.R.styleable.DrawableCorners_topLeftRadius, radius);
            int topRightRadius = a.getDimensionPixelSize(
                    com.android.internal.R.styleable.DrawableCorners_topRightRadius, radius);
            int bottomLeftRadius = a.getDimensionPixelSize(
                com.android.internal.R.styleable.DrawableCorners_bottomLeftRadius, radius);
            int bottomRightRadius = a.getDimensionPixelSize(
                com.android.internal.R.styleable.DrawableCorners_bottomRightRadius, radius);

            if (topLeftRadius != radius || topRightRadius != radius ||
                    bottomLeftRadius != radius || bottomRightRadius != radius) {
                setCornerRadii(new float[] {
                               topLeftRadius, topLeftRadius,
                               topRightRadius, topRightRadius,
                               bottomLeftRadius, bottomLeftRadius,
                               bottomRightRadius, bottomRightRadius
                               });
            }
            a.recycle();
            return true;
        }
        return super.inflateTag(name, r, parser, attrs);
    }
}

