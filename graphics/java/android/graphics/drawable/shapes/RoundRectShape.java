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

package android.graphics.drawable.shapes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Creates a rounded-corner rectangle. Optionally, an inset (rounded) rectangle
 * can be included (to make a sort of "O" shape). 
 * The rounded rectangle can be drawn to a Canvas with its own draw() method,
 * but more graphical control is available if you instead pass
 * the RoundRectShape to a {@link android.graphics.drawable.ShapeDrawable}.
 */
public class RoundRectShape extends RectShape {
    private float[] mOuterRadii;
    private RectF   mInset;
    private float[] mInnerRadii;
    
    private RectF mInnerRect;
    private Path  mPath;    // this is what we actually draw
    
    /**
     * RoundRectShape constructor.
     * Specifies an outer (round)rect and an optional inner (round)rect.
     *
     * @param outerRadii An array of 8 radius values, for the outer roundrect. 
     *                   The first two floats are for the 
     *                   top-left corner (remaining pairs correspond clockwise). 
     *                   For no rounded corners on the outer rectangle, 
     *                   pass null.
     * @param inset      A RectF that specifies the distance from the inner 
     *                   rect to each side of the outer rect. 
     *                   For no inner, pass null.
     * @param innerRadii An array of 8 radius values, for the inner roundrect.
     *                   The first two floats are for the 
     *                   top-left corner (remaining pairs correspond clockwise). 
     *                   For no rounded corners on the inner rectangle, 
     *                   pass null.
     *                   If inset parameter is null, this parameter is ignored. 
     */
    public RoundRectShape(float[] outerRadii, RectF inset,
                          float[] innerRadii) {
        if (outerRadii != null && outerRadii.length < 8) {
            throw new ArrayIndexOutOfBoundsException("outer radii must have >= 8 values");
        }
        if (innerRadii != null && innerRadii.length < 8) {
            throw new ArrayIndexOutOfBoundsException("inner radii must have >= 8 values");
        }
        mOuterRadii = outerRadii;
        mInset = inset;
        mInnerRadii = innerRadii;
        
        if (inset != null) {
            mInnerRect = new RectF();
        }
        mPath = new Path();
    }
    
    @Override
    public void draw(Canvas canvas, Paint paint) {
        canvas.drawPath(mPath, paint);
    }
    
    @Override
    protected void onResize(float w, float h) {
        super.onResize(w, h);
        
        RectF r = rect();
        mPath.reset();

        if (mOuterRadii != null) {
            mPath.addRoundRect(r, mOuterRadii, Path.Direction.CW);
        } else {
            mPath.addRect(r, Path.Direction.CW);
        }
        if (mInnerRect != null) {
            mInnerRect.set(r.left + mInset.left, r.top + mInset.top,
                           r.right - mInset.right, r.bottom - mInset.bottom);
            if (mInnerRect.width() < w && mInnerRect.height() < h) {
                if (mInnerRadii != null) {
                    mPath.addRoundRect(mInnerRect, mInnerRadii, Path.Direction.CCW);
                } else {
                    mPath.addRect(mInnerRect, Path.Direction.CCW);
                }
            }
        }
    }

    @Override
    public RoundRectShape clone() throws CloneNotSupportedException {
        RoundRectShape shape = (RoundRectShape) super.clone();
        shape.mOuterRadii = mOuterRadii != null ? mOuterRadii.clone() : null;
        shape.mInnerRadii = mInnerRadii != null ? mInnerRadii.clone() : null;
        shape.mInset = new RectF(mInset);
        shape.mInnerRect = new RectF(mInnerRect);
        shape.mPath = new Path(mPath);
        return shape;
    }
}
