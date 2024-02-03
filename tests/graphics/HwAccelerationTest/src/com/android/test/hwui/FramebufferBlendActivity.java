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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class FramebufferBlendActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new BlendView(this));
    }

    static class BlendView extends View {
        private int mTexWidth;
        private int mTexHeight;
        private Paint mPaint;
        private LinearGradient mHorGradient;
        private Bitmap mTexture;

        BlendView(Context c) {
            super(c);

            mTexture = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            mTexWidth = mTexture.getWidth();
            mTexHeight = mTexture.getHeight();

            mHorGradient = new LinearGradient(0.0f, 0.0f, mTexWidth, 0.0f,
                    Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP);

            mPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(255, 255, 255);

            canvas.save();
            canvas.translate(40.0f, 40.0f);

            drawBlendedBitmap(canvas, PorterDuff.Mode.DARKEN);
            drawBlendedBitmap(canvas, PorterDuff.Mode.LIGHTEN);
            drawBlendedBitmap(canvas, PorterDuff.Mode.MULTIPLY);

            canvas.restore();

            canvas.save();
            canvas.translate(40.0f + mTexWidth + 40.0f, 40.0f);

            drawBlendedBitmap(canvas, PorterDuff.Mode.SCREEN);
            drawBlendedBitmap(canvas, PorterDuff.Mode.ADD);
            drawBlendedBitmapInverse(canvas, PorterDuff.Mode.OVERLAY);

            canvas.restore();
        }

        private void drawBlendedBitmap(Canvas canvas, PorterDuff.Mode mode) {
            mPaint.setShader(null);
            mPaint.setXfermode(null);
            canvas.drawBitmap(mTexture, 0.0f, 0.0f, mPaint);

            mPaint.setShader(mHorGradient);
            mPaint.setXfermode(new PorterDuffXfermode(mode));
            canvas.drawRect(0.0f, 0.0f, mTexWidth, mTexHeight, mPaint);

            canvas.translate(0.0f, 40.0f + mTexHeight);
        }

        private void drawBlendedBitmapInverse(Canvas canvas, PorterDuff.Mode mode) {
            mPaint.setXfermode(null);
            mPaint.setShader(mHorGradient);
            canvas.drawRect(0.0f, 0.0f, mTexWidth, mTexHeight, mPaint);

            mPaint.setXfermode(new PorterDuffXfermode(mode));
            mPaint.setShader(null);
            canvas.drawBitmap(mTexture, 0.0f, 0.0f, mPaint);

            canvas.translate(0.0f, 40.0f + mTexHeight);
        }
    }
}
