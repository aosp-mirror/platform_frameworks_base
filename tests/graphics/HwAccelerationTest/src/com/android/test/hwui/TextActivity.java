/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class TextActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new CustomTextView(this));
    }

    static class CustomTextView extends View {
        private final Paint mMediumPaint;
        private final Paint mLargePaint;
        private final Paint mStrikePaint;
        private final Paint mScaledPaint;
        private final Paint mSkewPaint;
        private final Paint mHugePaint;
        private final TextPaint mEventPaint;

        CustomTextView(Context c) {
            super(c);

            mMediumPaint = new Paint();
            mMediumPaint.setAntiAlias(true);
            mMediumPaint.setColor(0xffff0000);

            mLargePaint = new Paint();
            mLargePaint.setAntiAlias(true);
            mLargePaint.setTextSize(36.0f);

            mStrikePaint = new Paint();
            mStrikePaint.setAntiAlias(true);
            mStrikePaint.setTextSize(16.0f);
            mStrikePaint.setUnderlineText(true);

            mScaledPaint = new Paint();
            mScaledPaint.setAntiAlias(true);
            mScaledPaint.setTextSize(16.0f);
            mScaledPaint.setShadowLayer(3.0f, 3.0f, 3.0f, 0xff00ff00);

            mSkewPaint = new Paint();
            mSkewPaint.setAntiAlias(true);
            mSkewPaint.setTextSize(16.0f);
            mSkewPaint.setShadowLayer(3.0f, 3.0f, 3.0f, 0xff000000);

            mHugePaint = new Paint();
            mHugePaint.setAntiAlias(true);
            mHugePaint.setColor(0xff000000);
            mHugePaint.setTextSize(300f);

            mEventPaint = new TextPaint();
            mEventPaint.setFakeBoldText(true);
            mEventPaint.setAntiAlias(true);
            mEventPaint.setTextSize(14);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(255, 255, 255);

            canvas.drawText("Hello OpenGL renderer!", 300, 20, mEventPaint);
            
            mMediumPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            mMediumPaint.setStrokeWidth(2.0f);
            canvas.drawText("Hello OpenGL renderer!", 100, 20, mMediumPaint);

            mMediumPaint.setStyle(Paint.Style.FILL);
            mMediumPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Hello OpenGL renderer!", 100, 40, mMediumPaint);

            mMediumPaint.setStyle(Paint.Style.STROKE);
            mMediumPaint.setStrokeWidth(2.0f);
            mMediumPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("Hello OpenGL renderer!", 100, 60, mMediumPaint);

            mMediumPaint.setStyle(Paint.Style.FILL);
            mMediumPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Hello OpenGL renderer!", 100, 100, mMediumPaint);

            mMediumPaint.setShadowLayer(2.5f, 0.0f, 0.0f, 0xff000000);
            canvas.drawText("Hello OpenGL renderer!", 100, 150, mMediumPaint);
            mMediumPaint.clearShadowLayer();
            canvas.drawText("Hello OpenGL renderer!", 100, 200, mLargePaint);

            mLargePaint.setShadowLayer(2.5f, 3.0f, 3.0f, 0xff000000);
            canvas.drawText("Hello OpenGL renderer!", 100, 400, mLargePaint);
            mLargePaint.setShadowLayer(3.0f, 3.0f, 3.0f, 0xff00ff00);
            mLargePaint.setAlpha(100);
            canvas.drawText("Hello OpenGL renderer!", 100, 500, mLargePaint);
            mLargePaint.setAlpha(255);
            mLargePaint.setShadowLayer(3.0f, 3.0f, 3.0f, 0xd000ff00);
            mLargePaint.setColor(0x00ffff00);
            canvas.drawText("Hello OpenGL renderer!", 100, 600, mLargePaint);
            mLargePaint.setShadowLayer(3.0f, 3.0f, 3.0f, 0x80000000);
            mLargePaint.setColor(0x4dffffff);
            canvas.drawText("Hello OpenGL renderer!", 100, 650, mLargePaint);
            mLargePaint.setAlpha(255);
            mLargePaint.setColor(0xff000000);
            mLargePaint.clearShadowLayer();

            canvas.drawText("Hello!", 500, 600, mHugePaint);

            canvas.drawText("Hello OpenGL renderer!", 500, 40, mStrikePaint);
            mStrikePaint.setStrikeThruText(true);
            canvas.drawText("Hello OpenGL renderer!", 500, 70, mStrikePaint);
            mStrikePaint.setUnderlineText(false);
            canvas.drawText("Hello OpenGL renderer!", 500, 100, mStrikePaint);
            mStrikePaint.setStrikeThruText(false);
            mStrikePaint.setUnderlineText(true);

            mSkewPaint.setTextSkewX(-0.25f);
            canvas.drawText("Hello OpenGL renderer!", 980, 200, mSkewPaint);
            mSkewPaint.setTextSkewX(0.5f);
            canvas.drawText("Hello OpenGL renderer!", 980, 230, mSkewPaint);
            mSkewPaint.setTextSkewX(0.0f);
            canvas.drawText("Hello OpenGL renderer!", 980, 260, mSkewPaint);

            mScaledPaint.setTextScaleX(0.5f);
            canvas.drawText("Hello OpenGL renderer!", 500, 200, mScaledPaint);
            mScaledPaint.setTextScaleX(1.0f);
            canvas.drawText("Hello OpenGL renderer!", 500, 230, mScaledPaint);
            mScaledPaint.setTextScaleX(2.0f);
            canvas.drawText("Hello OpenGL renderer!", 500, 260, mScaledPaint);

            canvas.save();
            canvas.clipRect(150.0f, 220.0f, 450.0f, 320.0f);
            canvas.drawText("Hello OpenGL renderer!", 100, 300, mLargePaint);
            canvas.restore();

//            mStrikePaint.setUnderlineText(false);
//            canvas.save();
//            canvas.scale(20.0f, 20.0f);
//            canvas.drawText("aeiouyw", 5.0f, 750 / 20.0f, mStrikePaint);
//            canvas.restore();
//            mStrikePaint.setUnderlineText(true);
        }
    }
}
