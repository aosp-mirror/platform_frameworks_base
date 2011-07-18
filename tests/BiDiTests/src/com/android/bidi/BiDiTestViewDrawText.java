/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.bidi;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint.Align;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

public class BiDiTestViewDrawText extends View {
    private float mSize;
    private int mColor;
    private String mText;

    public BiDiTestViewDrawText(Context context) {
        this(context, null);
    }

    public BiDiTestViewDrawText(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BiDiTestViewDrawText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.DrawTextTestView, defStyle, 0);
        mSize = a.getDimension(R.styleable.DrawTextTestView_size, 40.0f);
        mColor = a.getColor(R.styleable.DrawTextTestView_color, Color.YELLOW);
        final CharSequence text = a.getText(R.styleable.DrawTextTestView_text);
        mText = (text != null) ? text.toString() : "(empty)";
        a.recycle();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int width = getWidth();
        final int height = getHeight();

        final TextPaint paint = new TextPaint();
        paint.setTextSize(mSize);
        paint.setColor(mColor);
        paint.setTextAlign(Align.CENTER);

        canvas.drawText(mText, width / 2, height * 2 / 3, paint);
    }
}