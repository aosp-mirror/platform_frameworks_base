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
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class ShadersActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new ShadersView(this));
    }

    public static class ShadersView extends View {
        private BitmapShader mRepeatShader;
        private BitmapShader mTranslatedShader;
        private BitmapShader mScaledShader;
        private int mTexWidth;
        private int mTexHeight;
        private Paint mPaint;
        private float mDrawWidth;
        private float mDrawHeight;
        private LinearGradient mHorGradient;
        private LinearGradient mDiagGradient;
        private LinearGradient mVertGradient;
        private Bitmap mTexture;
        private Matrix mMtx1;
        private Matrix mMtx2;
        private Matrix mMtx3;

        public ShadersView(Context c) {
            super(c);

            mTexture = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            mTexWidth = mTexture.getWidth();
            mTexHeight = mTexture.getHeight();
            mDrawWidth = mTexWidth * 2.2f;
            mDrawHeight = mTexHeight * 1.2f;

            mRepeatShader = new BitmapShader(mTexture, Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT);

            mTranslatedShader = new BitmapShader(mTexture, Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT);
            mMtx1 = new Matrix();
            mMtx1.setTranslate(mTexWidth / 2.0f, mTexHeight / 2.0f);
            mMtx1.postRotate(45, 0, 0);
            mTranslatedShader.setLocalMatrix(mMtx1);

            mScaledShader = new BitmapShader(mTexture, Shader.TileMode.MIRROR,
                    Shader.TileMode.MIRROR);
            mMtx2 = new Matrix();
            mMtx2.setScale(0.5f, 0.5f);
            mScaledShader.setLocalMatrix(mMtx2);

            mHorGradient = new LinearGradient(0.0f, 0.0f, 1.0f, 0.0f,
                    Color.RED, Color.GREEN, Shader.TileMode.CLAMP);
            mMtx3 = new Matrix();
            mMtx3.setScale(mDrawHeight, 1.0f);
            mMtx3.postRotate(-90.0f);
            mMtx3.postTranslate(0.0f, mDrawHeight);
            mHorGradient.setLocalMatrix(mMtx3);

            mDiagGradient = new LinearGradient(0.0f, 0.0f, mDrawWidth / 1.5f, mDrawHeight,
                    Color.BLUE, Color.MAGENTA, Shader.TileMode.CLAMP);

            mVertGradient = new LinearGradient(0.0f, 0.0f, 0.0f, mDrawHeight / 2.0f,
                    Color.YELLOW, Color.MAGENTA, Shader.TileMode.MIRROR);

            mPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            //canvas.drawRGB(255, 255, 255);
            canvas.drawBitmap(mTexture, 0.0f, 0.0f, null);

            // Bitmap shaders
            canvas.save();
            canvas.translate(40.0f, 40.0f);

            mPaint.setShader(mRepeatShader);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.translate(0.0f, 40.0f + mDrawHeight);
            mPaint.setShader(mTranslatedShader);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.translate(0.0f, 40.0f + mDrawHeight);
            mPaint.setShader(mScaledShader);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.restore();

            // Gradients
            canvas.save();
            canvas.translate(40.0f + mDrawWidth + 40.0f, 40.0f);

            mPaint.setShader(mHorGradient);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.translate(0.0f, 40.0f + mDrawHeight);
            mPaint.setShader(mDiagGradient);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.translate(0.0f, 40.0f + mDrawHeight);
            mPaint.setShader(mVertGradient);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.restore();
        }
    }
}
