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

import android.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Creates a rounded-corner rectangle. Optionally, an inset (rounded) rectangle
 * can be included (to make a sort of "O" shape).
 * <p>
 * The rounded rectangle can be drawn to a Canvas with its own draw() method,
 * but more graphical control is available if you instead pass
 * the RoundRectShape to a {@link android.graphics.drawable.ShapeDrawable}.
 */
public class RoundRectShape extends RectShape {
    private float[] mOuterRadii;
    private RectF mInset;
    private float[] mInnerRadii;
    
    private RectF mInnerRect;
    private Path mPath; // this is what we actually draw
    
    /**
     * RoundRectShape constructor.
     * <p>
     * Specifies an outer (round)rect and an optional inner (round)rect.
     *
     * @param outerRadii An array of 8 radius values, for the outer roundrect. 
     *                   The first two floats are for the top-left corner
     *                   (remaining pairs correspond clockwise). For no rounded
     *                   corners on the outer rectangle, pass {@code null}.
     * @param inset A RectF that specifies the distance from the inner
     *              rect to each side of the outer rect. For no inner, pass
     *              {@code null}.
     * @param innerRadii An array of 8 radius values, for the inner roundrect.
     *                   The first two floats are for the top-left corner
     *                   (remaining pairs correspond clockwise). For no rounded
     *                   corners on the inner rectangle, pass {@code null}. If
     *                   inset parameter is {@code null}, this parameter is
     *                   ignored.
     */
    public RoundRectShape(@Nullable float[] outerRadii, @Nullable RectF inset,
            @Nullable float[] innerRadii) {
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
    public void getOutline(Outline outline) {
        if (mInnerRect != null) return; // have a hole, can't produce valid outline

        float radius = 0;
        if (mOuterRadii != null) {
            radius = mOuterRadii[0];
            for (int i = 1; i < 8; i++) {
                if (mOuterRadii[i] != radius) {
                    // can't call simple constructors, use path
                    outline.setConvexPath(mPath);
                    return;
                }
            }
        }

        final RectF rect = rect();
        outline.setRoundRect((int) Math.ceil(rect.left), (int) Math.ceil(rect.top),
                (int) Math.floor(rect.right), (int) Math.floor(rect.bottom), radius);
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
        final RoundRectShape shape = (RoundRectShape) super.clone();
        shape.mOuterRadii = mOuterRadii != null ? mOuterRadii.clone() : null;
        shape.mInnerRadii = mInnerRadii != null ? mInnerRadii.clone() : null;
        shape.mInset = new RectF(mInset);
        shape.mInnerRect = new RectF(mInnerRect);
        shape.mPath = new Path(mPath);
        return shape;
    }
}
