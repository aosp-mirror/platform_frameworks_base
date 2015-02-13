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
 * An animation that controls the position of an object. See the
 * {@link android.view.animation full package} description for details and
 * sample code.
 *
 */
public class TranslateAnimation extends Animation {
    private int mFromXType = ABSOLUTE;
    private int mToXType = ABSOLUTE;

    private int mFromYType = ABSOLUTE;
    private int mToYType = ABSOLUTE;

    /** @hide */
    protected float mFromXValue = 0.0f;
    /** @hide */
    protected float mToXValue = 0.0f;

    /** @hide */
    protected float mFromYValue = 0.0f;
    /** @hide */
    protected float mToYValue = 0.0f;

    /** @hide */
    protected float mFromXDelta;
    /** @hide */
    protected float mToXDelta;
    /** @hide */
    protected float mFromYDelta;
    /** @hide */
    protected float mToYDelta;

    /**
     * Constructor used when a TranslateAnimation is loaded from a resource.
     *
     * @param context Application context to use
     * @param attrs Attribute set from which to read values
     */
    public TranslateAnimation(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.TranslateAnimation);

        Description d = Description.parseValue(a.peekValue(
            com.android.internal.R.styleable.TranslateAnimation_fromXDelta));
        mFromXType = d.type;
        mFromXValue = d.value;

        d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.TranslateAnimation_toXDelta));
        mToXType = d.type;
        mToXValue = d.value;

        d = Description.parseValue(a.peekValue(
            com.android.internal.R.styleable.TranslateAnimation_fromYDelta));
        mFromYType = d.type;
        mFromYValue = d.value;

        d = Description.parseValue(a.peekValue(
            com.android.internal.R.styleable.TranslateAnimation_toYDelta));
        mToYType = d.type;
        mToYValue = d.value;

        a.recycle();
    }

    /**
     * Constructor to use when building a TranslateAnimation from code
     *
     * @param fromXDelta Change in X coordinate to apply at the start of the
     *        animation
     * @param toXDelta Change in X coordinate to apply at the end of the
     *        animation
     * @param fromYDelta Change in Y coordinate to apply at the start of the
     *        animation
     * @param toYDelta Change in Y coordinate to apply at the end of the
     *        animation
     */
    public TranslateAnimation(float fromXDelta, float toXDelta, float fromYDelta, float toYDelta) {
        mFromXValue = fromXDelta;
        mToXValue = toXDelta;
        mFromYValue = fromYDelta;
        mToYValue = toYDelta;

        mFromXType = ABSOLUTE;
        mToXType = ABSOLUTE;
        mFromYType = ABSOLUTE;
        mToYType = ABSOLUTE;
    }

    /**
     * Constructor to use when building a TranslateAnimation from code
     * 
     * @param fromXType Specifies how fromXValue should be interpreted. One of
     *        Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or
     *        Animation.RELATIVE_TO_PARENT.
     * @param fromXValue Change in X coordinate to apply at the start of the
     *        animation. This value can either be an absolute number if fromXType
     *        is ABSOLUTE, or a percentage (where 1.0 is 100%) otherwise.
     * @param toXType Specifies how toXValue should be interpreted. One of
     *        Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or
     *        Animation.RELATIVE_TO_PARENT.
     * @param toXValue Change in X coordinate to apply at the end of the
     *        animation. This value can either be an absolute number if toXType
     *        is ABSOLUTE, or a percentage (where 1.0 is 100%) otherwise.
     * @param fromYType Specifies how fromYValue should be interpreted. One of
     *        Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or
     *        Animation.RELATIVE_TO_PARENT.
     * @param fromYValue Change in Y coordinate to apply at the start of the
     *        animation. This value can either be an absolute number if fromYType
     *        is ABSOLUTE, or a percentage (where 1.0 is 100%) otherwise.
     * @param toYType Specifies how toYValue should be interpreted. One of
     *        Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or
     *        Animation.RELATIVE_TO_PARENT.
     * @param toYValue Change in Y coordinate to apply at the end of the
     *        animation. This value can either be an absolute number if toYType
     *        is ABSOLUTE, or a percentage (where 1.0 is 100%) otherwise.
     */
    public TranslateAnimation(int fromXType, float fromXValue, int toXType, float toXValue,
            int fromYType, float fromYValue, int toYType, float toYValue) {

        mFromXValue = fromXValue;
        mToXValue = toXValue;
        mFromYValue = fromYValue;
        mToYValue = toYValue;

        mFromXType = fromXType;
        mToXType = toXType;
        mFromYType = fromYType;
        mToYType = toYType;
    }


    @Override
    protected void applyTransformation(float interpolatedTime, Transformation t) {
        float dx = mFromXDelta;
        float dy = mFromYDelta;
        if (mFromXDelta != mToXDelta) {
            dx = mFromXDelta + ((mToXDelta - mFromXDelta) * interpolatedTime);
        }
        if (mFromYDelta != mToYDelta) {
            dy = mFromYDelta + ((mToYDelta - mFromYDelta) * interpolatedTime);
        }
        t.getMatrix().setTranslate(dx, dy);
    }

    @Override
    public void initialize(int width, int height, int parentWidth, int parentHeight) {
        super.initialize(width, height, parentWidth, parentHeight);
        mFromXDelta = resolveSize(mFromXType, mFromXValue, width, parentWidth);
        mToXDelta = resolveSize(mToXType, mToXValue, width, parentWidth);
        mFromYDelta = resolveSize(mFromYType, mFromYValue, height, parentHeight);
        mToYDelta = resolveSize(mToYType, mToYValue, height, parentHeight);
    }
}
