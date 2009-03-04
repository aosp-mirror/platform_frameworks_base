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

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

/**
 * An animation that controls the scale of an object. You can specify the point
 * to use for the center of scaling.
 * 
 */
public class ScaleAnimation extends Animation {
    private float mFromX;
    private float mToX;
    private float mFromY;
    private float mToY;

    private int mPivotXType = ABSOLUTE;
    private int mPivotYType = ABSOLUTE;
    private float mPivotXValue = 0.0f;
    private float mPivotYValue = 0.0f;

    private float mPivotX;
    private float mPivotY;

    /**
     * Constructor used when a ScaleAnimation is loaded from a resource.
     * 
     * @param context Application context to use
     * @param attrs Attribute set from which to read values
     */
    public ScaleAnimation(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.ScaleAnimation);

        mFromX = a.getFloat(com.android.internal.R.styleable.ScaleAnimation_fromXScale, 0.0f);
        mToX = a.getFloat(com.android.internal.R.styleable.ScaleAnimation_toXScale, 0.0f);

        mFromY = a.getFloat(com.android.internal.R.styleable.ScaleAnimation_fromYScale, 0.0f);
        mToY = a.getFloat(com.android.internal.R.styleable.ScaleAnimation_toYScale, 0.0f);

        Description d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.ScaleAnimation_pivotX));
        mPivotXType = d.type;
        mPivotXValue = d.value;

        d = Description.parseValue(a.peekValue(
            com.android.internal.R.styleable.ScaleAnimation_pivotY));
        mPivotYType = d.type;
        mPivotYValue = d.value;

        a.recycle();
    }

    /**
     * Constructor to use when building a ScaleAnimation from code
     * 
     * @param fromX Horizontal scaling factor to apply at the start of the
     *        animation
     * @param toX Horizontal scaling factor to apply at the end of the animation
     * @param fromY Vertical scaling factor to apply at the start of the
     *        animation
     * @param toY Vertical scaling factor to apply at the end of the animation
     */
    public ScaleAnimation(float fromX, float toX, float fromY, float toY) {
        mFromX = fromX;
        mToX = toX;
        mFromY = fromY;
        mToY = toY;
        mPivotX = 0;
        mPivotY = 0;
    }

    /**
     * Constructor to use when building a ScaleAnimation from code
     * 
     * @param fromX Horizontal scaling factor to apply at the start of the
     *        animation
     * @param toX Horizontal scaling factor to apply at the end of the animation
     * @param fromY Vertical scaling factor to apply at the start of the
     *        animation
     * @param toY Vertical scaling factor to apply at the end of the animation
     * @param pivotX The X coordinate of the point about which the object is
     *        being scaled, specified as an absolute number where 0 is the left
     *        edge. (This point remains fixed while the object changes size.)
     * @param pivotY The Y coordinate of the point about which the object is
     *        being scaled, specified as an absolute number where 0 is the top
     *        edge. (This point remains fixed while the object changes size.)
     */
    public ScaleAnimation(float fromX, float toX, float fromY, float toY,
            float pivotX, float pivotY) {
        mFromX = fromX;
        mToX = toX;
        mFromY = fromY;
        mToY = toY;

        mPivotXType = ABSOLUTE;
        mPivotYType = ABSOLUTE;
        mPivotXValue = pivotX;
        mPivotYValue = pivotY;
    }

    /**
     * Constructor to use when building a ScaleAnimation from code
     * 
     * @param fromX Horizontal scaling factor to apply at the start of the
     *        animation
     * @param toX Horizontal scaling factor to apply at the end of the animation
     * @param fromY Vertical scaling factor to apply at the start of the
     *        animation
     * @param toY Vertical scaling factor to apply at the end of the animation
     * @param pivotXType Specifies how pivotXValue should be interpreted. One of
     *        Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or
     *        Animation.RELATIVE_TO_PARENT.
     * @param pivotXValue The X coordinate of the point about which the object
     *        is being scaled, specified as an absolute number where 0 is the
     *        left edge. (This point remains fixed while the object changes
     *        size.) This value can either be an absolute number if pivotXType
     *        is ABSOLUTE, or a percentage (where 1.0 is 100%) otherwise.
     * @param pivotYType Specifies how pivotYValue should be interpreted. One of
     *        Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or
     *        Animation.RELATIVE_TO_PARENT.
     * @param pivotYValue The Y coordinate of the point about which the object
     *        is being scaled, specified as an absolute number where 0 is the
     *        top edge. (This point remains fixed while the object changes
     *        size.) This value can either be an absolute number if pivotYType
     *        is ABSOLUTE, or a percentage (where 1.0 is 100%) otherwise.
     */
    public ScaleAnimation(float fromX, float toX, float fromY, float toY,
            int pivotXType, float pivotXValue, int pivotYType, float pivotYValue) {
        mFromX = fromX;
        mToX = toX;
        mFromY = fromY;
        mToY = toY;

        mPivotXValue = pivotXValue;
        mPivotXType = pivotXType;
        mPivotYValue = pivotYValue;
        mPivotYType = pivotYType;
    }

    @Override
    protected void applyTransformation(float interpolatedTime, Transformation t) {
        float sx = 1.0f;
        float sy = 1.0f;

        if (mFromX != 1.0f || mToX != 1.0f) {
            sx = mFromX + ((mToX - mFromX) * interpolatedTime);
        }
        if (mFromY != 1.0f || mToY != 1.0f) {
            sy = mFromY + ((mToY - mFromY) * interpolatedTime);
        }

        if (mPivotX == 0 && mPivotY == 0) {
            t.getMatrix().setScale(sx, sy);
        } else {
            t.getMatrix().setScale(sx, sy, mPivotX, mPivotY);
        }
    }

    @Override
    public void initialize(int width, int height, int parentWidth, int parentHeight) {
        super.initialize(width, height, parentWidth, parentHeight);

        mPivotX = resolveSize(mPivotXType, mPivotXValue, width, parentWidth);
        mPivotY = resolveSize(mPivotYType, mPivotYValue, height, parentHeight);
    }
}
