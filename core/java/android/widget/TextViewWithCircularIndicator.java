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

package android.widget;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;

import com.android.internal.R;

class TextViewWithCircularIndicator extends TextView {

    private static final int SELECTED_CIRCLE_ALPHA = 60;

    private final Paint mCirclePaint = new Paint();

    private final String mItemIsSelectedText;
    private int mCircleColor;
    private boolean mDrawIndicator;

    public TextViewWithCircularIndicator(Context context) {
        this(context, null);
    }

    public TextViewWithCircularIndicator(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TextViewWithCircularIndicator(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TextViewWithCircularIndicator(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs);


        // Use Theme attributes if possible
        final TypedArray a = mContext.obtainStyledAttributes(attrs,
                R.styleable.DatePicker, defStyleAttr, defStyleRes);
        final int resId = a.getResourceId(R.styleable.DatePicker_yearListItemTextAppearance, -1);
        if (resId != -1) {
            setTextAppearance(context, resId);
        }

        final Resources res = context.getResources();
        mItemIsSelectedText = res.getString(R.string.item_is_selected);

        a.recycle();

        init();
    }

    private void init() {
        mCirclePaint.setTypeface(Typeface.create(mCirclePaint.getTypeface(), Typeface.BOLD));
        mCirclePaint.setAntiAlias(true);
        mCirclePaint.setTextAlign(Paint.Align.CENTER);
        mCirclePaint.setStyle(Paint.Style.FILL);
    }

    public void setCircleColor(int color) {
        if (color != mCircleColor) {
            mCircleColor = color;
            mCirclePaint.setColor(mCircleColor);
            mCirclePaint.setAlpha(SELECTED_CIRCLE_ALPHA);
            requestLayout();
        }
    }

    public void setDrawIndicator(boolean drawIndicator) {
        mDrawIndicator = drawIndicator;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mDrawIndicator) {
            final int width = getWidth();
            final int height = getHeight();
            int radius = Math.min(width, height) / 2;
            canvas.drawCircle(width / 2, height / 2, radius, mCirclePaint);
        }
    }

    @Override
    public CharSequence getContentDescription() {
        CharSequence itemText = getText();
        if (mDrawIndicator) {
            return String.format(mItemIsSelectedText, itemText);
        } else {
            return itemText;
        }
    }
}