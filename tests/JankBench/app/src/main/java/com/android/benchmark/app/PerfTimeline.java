/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.benchmark.app;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.*;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import com.android.benchmark.R;


/**
 * TODO: document your custom view class.
 */
public class PerfTimeline extends View {
    private String mExampleString; // TODO: use a default from R.string...
    private int mExampleColor = Color.RED; // TODO: use a default from R.color...
    private float mExampleDimension = 300; // TODO: use a default from R.dimen...

    private TextPaint mTextPaint;
    private float mTextWidth;
    private float mTextHeight;

    private Paint mPaintBaseLow;
    private Paint mPaintBaseHigh;
    private Paint mPaintValue;


    public float[] mLinesLow;
    public float[] mLinesHigh;
    public float[] mLinesValue;

    public PerfTimeline(Context context) {
        super(context);
        init(null, 0);
    }

    public PerfTimeline(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs, 0);
    }

    public PerfTimeline(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(attrs, defStyle);
    }

    private void init(AttributeSet attrs, int defStyle) {
        // Load attributes
        final TypedArray a = getContext().obtainStyledAttributes(
                attrs, R.styleable.PerfTimeline, defStyle, 0);

        mExampleString = "xx";//a.getString(R.styleable.PerfTimeline_exampleString, "xx");
        mExampleColor = a.getColor(R.styleable.PerfTimeline_exampleColor, mExampleColor);
        // Use getDimensionPixelSize or getDimensionPixelOffset when dealing with
        // values that should fall on pixel boundaries.
        mExampleDimension = a.getDimension(
                R.styleable.PerfTimeline_exampleDimension,
                mExampleDimension);

        a.recycle();

        // Set up a default TextPaint object
        mTextPaint = new TextPaint();
        mTextPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTextAlign(Paint.Align.LEFT);

        // Update TextPaint and text measurements from attributes
        invalidateTextPaintAndMeasurements();

        mPaintBaseLow = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintBaseLow.setStyle(Paint.Style.FILL);
        mPaintBaseLow.setColor(0xff000000);

        mPaintBaseHigh = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintBaseHigh.setStyle(Paint.Style.FILL);
        mPaintBaseHigh.setColor(0x7f7f7f7f);

        mPaintValue = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintValue.setStyle(Paint.Style.FILL);
        mPaintValue.setColor(0x7fff0000);

    }

    private void invalidateTextPaintAndMeasurements() {
        mTextPaint.setTextSize(mExampleDimension);
        mTextPaint.setColor(mExampleColor);
        mTextWidth = mTextPaint.measureText(mExampleString);

        Paint.FontMetrics fontMetrics = mTextPaint.getFontMetrics();
        mTextHeight = fontMetrics.bottom;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // TODO: consider storing these as member variables to reduce
        // allocations per draw cycle.
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int paddingRight = getPaddingRight();
        int paddingBottom = getPaddingBottom();

        int contentWidth = getWidth() - paddingLeft - paddingRight;
        int contentHeight = getHeight() - paddingTop - paddingBottom;

        // Draw the text.
        //canvas.drawText(mExampleString,
        //        paddingLeft + (contentWidth - mTextWidth) / 2,
        //        paddingTop + (contentHeight + mTextHeight) / 2,
        //        mTextPaint);




        // Draw the shadow
        //RectF rf = new RectF(10.f, 10.f, 100.f, 100.f);
        //canvas.drawOval(rf, mShadowPaint);

        if (mLinesLow != null) {
            canvas.drawLines(mLinesLow, mPaintBaseLow);
        }
        if (mLinesHigh != null) {
            canvas.drawLines(mLinesHigh, mPaintBaseHigh);
        }
        if (mLinesValue != null) {
            canvas.drawLines(mLinesValue, mPaintValue);
        }


/*
        // Draw the pie slices
        for (int i = 0; i < mData.size(); ++i) {
            Item it = mData.get(i);
            mPiePaint.setShader(it.mShader);
            canvas.drawArc(mBounds,
                    360 - it.mEndAngle,
                    it.mEndAngle - it.mStartAngle,
                    true, mPiePaint);
        }
*/
        // Draw the pointer
        //canvas.drawLine(mTextX, mPointerY, mPointerX, mPointerY, mTextPaint);
        //canvas.drawCircle(mPointerX, mPointerY, mPointerSize, mTextPaint);
    }

    /**
     * Gets the example string attribute value.
     *
     * @return The example string attribute value.
     */
    public String getExampleString() {
        return mExampleString;
    }

    /**
     * Sets the view's example string attribute value. In the example view, this string
     * is the text to draw.
     *
     * @param exampleString The example string attribute value to use.
     */
    public void setExampleString(String exampleString) {
        mExampleString = exampleString;
        invalidateTextPaintAndMeasurements();
    }

    /**
     * Gets the example color attribute value.
     *
     * @return The example color attribute value.
     */
    public int getExampleColor() {
        return mExampleColor;
    }

    /**
     * Sets the view's example color attribute value. In the example view, this color
     * is the font color.
     *
     * @param exampleColor The example color attribute value to use.
     */
    public void setExampleColor(int exampleColor) {
        mExampleColor = exampleColor;
        invalidateTextPaintAndMeasurements();
    }

    /**
     * Gets the example dimension attribute value.
     *
     * @return The example dimension attribute value.
     */
    public float getExampleDimension() {
        return mExampleDimension;
    }

    /**
     * Sets the view's example dimension attribute value. In the example view, this dimension
     * is the font size.
     *
     * @param exampleDimension The example dimension attribute value to use.
     */
    public void setExampleDimension(float exampleDimension) {
        mExampleDimension = exampleDimension;
        invalidateTextPaintAndMeasurements();
    }
}
