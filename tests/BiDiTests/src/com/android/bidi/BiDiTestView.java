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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

public class BiDiTestView extends View {

    private static final String TAG = "BiDiTestView";

    private static final int BORDER_PADDING = 4;
    private static final int TEXT_PADDING = 16;
    private static final int TEXT_SIZE = 32;
    private static final int ORIGIN = 48;
    private static final int DELTA_Y = TEXT_SIZE;

    private static final float DEFAULT_ITALIC_SKEW_X = -0.25f;

    private Paint paint = new Paint();
    private Rect rect = new Rect();

    private String NORMAL_TEXT;
    private String NORMAL_LONG_TEXT;
    private String ITALIC_TEXT;
    private String BOLD_TEXT;
    private String BOLD_ITALIC_TEXT;
    private String ARABIC_TEXT;
    private String CHINESE_TEXT;

    private Typeface typeface;

    public BiDiTestView(Context context) {
        super(context);
        init(context);
    }

    public BiDiTestView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public BiDiTestView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        NORMAL_TEXT = context.getString(R.string.normal_text);
        NORMAL_LONG_TEXT = context.getString(R.string.normal_long_text);
        ITALIC_TEXT = context.getString(R.string.italic_text);
        BOLD_TEXT = context.getString(R.string.bold_text);
        BOLD_ITALIC_TEXT = context.getString(R.string.bold_italic_text);
        ARABIC_TEXT = context.getString(R.string.arabic_text);
        CHINESE_TEXT = context.getString(R.string.chinese_text);

        typeface = paint.getTypeface();
        paint.setAntiAlias(true);
    }

    @Override
    public void onDraw(Canvas canvas) {
        drawInsideRect(canvas, Color.BLACK);

        int deltaX = testString(canvas, NORMAL_TEXT, ORIGIN, ORIGIN, paint, typeface,
                false, false,  Paint.DIRECTION_LTR);
        deltaX += testString(canvas, ITALIC_TEXT, ORIGIN + deltaX, ORIGIN, paint, typeface,
                true, false,  Paint.DIRECTION_LTR);
        deltaX += testString(canvas, BOLD_TEXT, ORIGIN + deltaX, ORIGIN, paint, typeface,
                false, true,  Paint.DIRECTION_LTR);
        deltaX += testString(canvas, BOLD_ITALIC_TEXT, ORIGIN + deltaX, ORIGIN, paint, typeface,
                true, true,  Paint.DIRECTION_LTR);

        // Test with a long string
        deltaX = testString(canvas, NORMAL_LONG_TEXT, ORIGIN, ORIGIN + 2 * DELTA_Y, paint, typeface,
                false, false,  Paint.DIRECTION_LTR);

        // Test Arabic ligature
        deltaX = testString(canvas, ARABIC_TEXT, ORIGIN, ORIGIN + 4 * DELTA_Y, paint, typeface,
                false, false,  Paint.DIRECTION_RTL);

        // Test Chinese
        deltaX = testString(canvas, CHINESE_TEXT, ORIGIN, ORIGIN + 6 * DELTA_Y, paint, typeface,
                false, false,  Paint.DIRECTION_LTR);
    }

    private int testString(Canvas canvas, String text, int x, int y, Paint paint, Typeface typeface,
            boolean isItalic, boolean isBold, int dir) {
        paint.setTypeface(typeface);

        // Set paint properties
        boolean oldFakeBold = paint.isFakeBoldText();
        paint.setFakeBoldText(isBold);

        float oldTextSkewX = paint.getTextSkewX();
        if (isItalic) {
            paint.setTextSkewX(DEFAULT_ITALIC_SKEW_X);
        }

        drawTextWithCanvasDrawText(text, canvas, x, y, TEXT_SIZE, Color.WHITE);

        int length = text.length();
        float[] advances = new float[length];
        float textWidth = paint.getTextRunAdvances(text, 0, length, 0, length, 0, advances, 0);

        logAdvances(text, textWidth, advances);
        drawBoxAroundText(canvas, x, y, textWidth, TEXT_SIZE, Color.RED);

        paint.setColor(Color.WHITE);
        char[] glyphs = new char[2*length];
        int count = getGlyphs(text, glyphs, dir);

        logGlypths(glyphs, count);
        drawTextWithDrawGlyph(canvas, glyphs, count, x, y + DELTA_Y);

        // Restore old paint properties
        paint.setFakeBoldText(oldFakeBold);
        paint.setTextSkewX(oldTextSkewX);

        return (int) Math.ceil(textWidth) + TEXT_PADDING;
    }

    private void drawTextWithDrawGlyph(Canvas canvas, char[] glyphs, int count, int x, int y) {
        canvas.drawGlyphs(glyphs, 0, count, x, y, paint);
    }

    private void logGlypths(char[] glyphs, int count) {
        Log.v(TAG, "GlyphIds - count=" + count);
        for (int n = 0; n < count; n++) {
            Log.v(TAG, "GlyphIds - Id[" + n + "]="+ (int)glyphs[n]);
        }
    }

    private int getGlyphs(String text, char[] glyphs, int dir) {
//        int dir = 1; // Paint.DIRECTION_LTR;
        return paint.getTextGlypths(text, 0, text.length(), 0, text.length(), dir, glyphs);
    }

    private void drawInsideRect(Canvas canvas, int color) {
        paint.setColor(color);
        int width = getWidth();
        int height = getHeight();
        rect.set(BORDER_PADDING, BORDER_PADDING, width - BORDER_PADDING, height - BORDER_PADDING);
        canvas.drawRect(rect, paint);
    }

    private void drawTextWithCanvasDrawText(String text, Canvas canvas,
            float x, float y, float textSize, int color) {
        paint.setColor(color);
        paint.setTextSize(textSize);
        canvas.drawText(text, x, y, paint);
    }

    private void drawBoxAroundText(Canvas canvas, int x, int y, float textWidth, int textSize,
            int color) {
        paint.setColor(color);
        canvas.drawLine(x, y - textSize, x, y + 8, paint);
        canvas.drawLine(x, y + 8, x + textWidth, y + 8, paint);
        canvas.drawLine(x + textWidth, y - textSize, x + textWidth, y + 8, paint);
    }

    private void logAdvances(String text, float textWidth, float[] advances) {
        Log.v(TAG, "Advances for text: " + text + " total=" + textWidth);
        int length = advances.length;
        for(int n=0; n<length; n++){
            Log.v(TAG, "adv[" + n + "]=" + advances[n]);
        }
    }
}
