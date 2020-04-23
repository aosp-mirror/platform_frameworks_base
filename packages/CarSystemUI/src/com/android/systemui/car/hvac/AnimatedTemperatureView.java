/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.hvac;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;
import android.util.Property;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextSwitcher;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * Simple text display of HVAC properties, It is designed to show mTemperature and is configured in
 * the XML.
 * XML properties:
 * hvacPropertyId - Example: CarHvacManager.ID_ZONED_TEMP_SETPOINT (16385)
 * hvacAreaId - Example: VehicleSeat.SEAT_ROW_1_LEFT (1)
 * hvacTempFormat - Example: "%.1f\u00B0" (1 decimal and the degree symbol)
 * hvacOrientaion = Example: left
 */
public class AnimatedTemperatureView extends FrameLayout implements TemperatureView {

    private static final float TEMPERATURE_EQUIVALENT_DELTA = .01f;
    private static final Property<ColorDrawable, Integer> COLOR_PROPERTY =
            new Property<ColorDrawable, Integer>(Integer.class, "color") {

                @Override
                public Integer get(ColorDrawable object) {
                    return object.getColor();
                }

                @Override
                public void set(ColorDrawable object, Integer value) {
                    object.setColor(value);
                }
            };

    static boolean isHorizontal(int gravity) {
        return Gravity.isHorizontal(gravity)
                && (gravity & Gravity.HORIZONTAL_GRAVITY_MASK) != Gravity.CENTER_HORIZONTAL;
    }

    @SuppressLint("RtlHardcoded")
    static boolean isLeft(int gravity, int layoutDirection) {
        return Gravity
                .getAbsoluteGravity(gravity & Gravity.HORIZONTAL_GRAVITY_MASK, layoutDirection)
                == Gravity.LEFT;
    }

    static boolean isVertical(int gravity) {
        return Gravity.isVertical(gravity)
                && (gravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.CENTER_VERTICAL;
    }

    static boolean isTop(int gravity) {
        return (gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP;
    }

    private final int mAreaId;
    private final int mPropertyId;
    private final int mPivotOffset;
    private final int mGravity;
    private final int mTextAppearanceRes;
    private final int mMinEms;
    private final Rect mPaddingRect;
    private final float mMinValue;
    private final float mMaxValue;

    private final ColorDrawable mBackgroundColor;

    private final TemperatureColorStore mColorStore = new TemperatureColorStore();
    private final TemperatureBackgroundAnimator mBackgroundAnimator;
    private final TemperatureTextAnimator mTextAnimator;
    boolean mDisplayInFahrenheit = false;

    public AnimatedTemperatureView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray typedArray = context.obtainStyledAttributes(attrs,
                R.styleable.AnimatedTemperatureView);
        mAreaId = typedArray.getInt(R.styleable.AnimatedTemperatureView_hvacAreaId, -1);
        mPropertyId = typedArray.getInt(R.styleable.AnimatedTemperatureView_hvacPropertyId, -1);
        mPivotOffset =
                typedArray.getDimensionPixelOffset(
                        R.styleable.AnimatedTemperatureView_hvacPivotOffset, 0);
        mGravity = typedArray.getInt(R.styleable.AnimatedTemperatureView_android_gravity,
                Gravity.START);
        mTextAppearanceRes =
                typedArray.getResourceId(R.styleable.AnimatedTemperatureView_android_textAppearance,
                        0);
        mMinEms = typedArray.getInteger(R.styleable.AnimatedTemperatureView_android_minEms, 0);
        mMinValue = typedArray.getFloat(R.styleable.AnimatedTemperatureView_hvacMinValue,
                Float.NaN);
        mMaxValue = typedArray.getFloat(R.styleable.AnimatedTemperatureView_hvacMaxValue,
                Float.NaN);


        mPaddingRect =
                new Rect(getPaddingLeft(), getPaddingTop(), getPaddingRight(), getPaddingBottom());
        setPadding(0, 0, 0, 0);

        setClipChildren(false);
        setClipToPadding(false);

        // init Views
        TextSwitcher textSwitcher = new TextSwitcher(context);
        textSwitcher.setFactory(this::generateTextView);
        ImageView background = new ImageView(context);
        mBackgroundColor = new ColorDrawable(Color.TRANSPARENT);
        background.setImageDrawable(mBackgroundColor);
        background.setVisibility(View.GONE);

        mBackgroundAnimator = new TemperatureBackgroundAnimator(this, background);


        String format = typedArray.getString(R.styleable.AnimatedTemperatureView_hvacTempFormat);
        format = (format == null) ? "%.1f\u00B0" : format;
        CharSequence minText = typedArray.getString(
                R.styleable.AnimatedTemperatureView_hvacMinText);
        CharSequence maxText = typedArray.getString(
                R.styleable.AnimatedTemperatureView_hvacMaxText);
        mTextAnimator = new TemperatureTextAnimator(this, textSwitcher, format, mPivotOffset,
                minText, maxText);

        addView(background, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        addView(textSwitcher, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        typedArray.recycle();
    }


    private TextView generateTextView() {
        TextView textView = new TextView(getContext());
        textView.setTextAppearance(mTextAppearanceRes);
        textView.setAllCaps(true);
        textView.setMinEms(mMinEms);
        textView.setGravity(mGravity);
        textView.setPadding(mPaddingRect.left, mPaddingRect.top, mPaddingRect.right,
                mPaddingRect.bottom);
        textView.getViewTreeObserver()
                .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (isHorizontal(mGravity)) {
                            if (isLeft(mGravity, getLayoutDirection())) {
                                textView.setPivotX(-mPivotOffset);
                            } else {
                                textView.setPivotX(textView.getWidth() + mPivotOffset);
                            }
                        }
                        textView.getViewTreeObserver().removeOnPreDrawListener(this);
                        return true;
                    }
                });
        textView.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        return textView;
    }

    /**
     * Formats the float for display
     *
     * @param temp - The current temp or NaN
     */
    @Override
    public void setTemp(float temp) {
        if (mDisplayInFahrenheit) {
            temp = convertToFahrenheit(temp);
        }
        mTextAnimator.setTemp(temp);
        if (Float.isNaN(temp)) {
            mBackgroundAnimator.hideCircle();
            return;
        }
        int color;
        if (isMinValue(temp)) {
            color = mColorStore.getMinColor();
        } else if (isMaxValue(temp)) {
            color = mColorStore.getMaxColor();
        } else {
            color = mColorStore.getColorForTemperature(temp);
        }
        if (mBackgroundAnimator.isOpen()) {
            ObjectAnimator colorAnimator =
                    ObjectAnimator.ofInt(mBackgroundColor, COLOR_PROPERTY, color);
            colorAnimator.setEvaluator((fraction, startValue, endValue) -> mColorStore
                    .lerpColor(fraction, (int) startValue, (int) endValue));
            colorAnimator.start();
        } else {
            mBackgroundColor.setColor(color);
        }

        mBackgroundAnimator.animateOpen();
    }

    @Override
    public void setDisplayInFahrenheit(boolean displayInFahrenheit) {
        mDisplayInFahrenheit = displayInFahrenheit;
    }

    boolean isMinValue(float temp) {
        return !Float.isNaN(mMinValue) && isApproxEqual(temp, mMinValue);
    }

    boolean isMaxValue(float temp) {
        return !Float.isNaN(mMaxValue) && isApproxEqual(temp, mMaxValue);
    }

    private boolean isApproxEqual(float left, float right) {
        return Math.abs(left - right) <= TEMPERATURE_EQUIVALENT_DELTA;
    }

    int getGravity() {
        return mGravity;
    }

    int getPivotOffset() {
        return mPivotOffset;
    }

    Rect getPaddingRect() {
        return mPaddingRect;
    }

    /**
     * @return propertiyId  Example: CarHvacManager.ID_ZONED_TEMP_SETPOINT (358614275)
     */
    @Override
    public int getPropertyId() {
        return mPropertyId;
    }

    /**
     * @return hvac AreaId - Example: VehicleSeat.SEAT_ROW_1_LEFT (1)
     */
    @Override
    public int getAreaId() {
        return mAreaId;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mBackgroundAnimator.stopAnimations();
    }

}

