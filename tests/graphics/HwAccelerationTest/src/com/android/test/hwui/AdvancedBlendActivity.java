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
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class AdvancedBlendActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new ShadersView(this));
    }

    public static class ShadersView extends View {
        private BitmapShader mScaledShader;
        private int mTexWidth;
        private int mTexHeight;
        private Paint mPaint;
        private float mDrawWidth;
        private float mDrawHeight;
        private LinearGradient mHorGradient;
        private ComposeShader mComposeShader;
        private ComposeShader mCompose2Shader;
        private ComposeShader mCompose3Shader;
        private ComposeShader mCompose4Shader;
        private ComposeShader mCompose5Shader;
        private ComposeShader mCompose6Shader;
        private BitmapShader mScaled2Shader;

        public ShadersView(Context c) {
            super(c);

            Bitmap texture = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            mTexWidth = texture.getWidth();
            mTexHeight = texture.getHeight();
            mDrawWidth = mTexWidth * 2.2f;
            mDrawHeight = mTexHeight * 1.2f;

            mScaledShader = new BitmapShader(texture, Shader.TileMode.MIRROR,
                    Shader.TileMode.MIRROR);
            Matrix m2 = new Matrix();
            m2.setScale(0.5f, 0.5f);
            mScaledShader.setLocalMatrix(m2);

            mScaled2Shader = new BitmapShader(texture, Shader.TileMode.MIRROR,
                    Shader.TileMode.MIRROR);
            Matrix m3 = new Matrix();
            m3.setScale(0.1f, 0.1f);
            mScaled2Shader.setLocalMatrix(m3);

            mHorGradient = new LinearGradient(0.0f, 0.0f, mDrawWidth, 0.0f,
                    Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP);

            mComposeShader = new ComposeShader(mScaledShader, mHorGradient,
                    PorterDuff.Mode.DARKEN);
            mCompose2Shader = new ComposeShader(mScaledShader, mHorGradient,
                    PorterDuff.Mode.LIGHTEN);
            mCompose3Shader = new ComposeShader(mScaledShader, mHorGradient,
                    PorterDuff.Mode.MULTIPLY);
            mCompose4Shader = new ComposeShader(mScaledShader, mHorGradient,
                    PorterDuff.Mode.SCREEN);
            mCompose5Shader = new ComposeShader(mScaledShader, mHorGradient,
                    PorterDuff.Mode.ADD);
            mCompose6Shader = new ComposeShader(mHorGradient, mScaledShader,
                    PorterDuff.Mode.OVERLAY);

            mPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(255, 255, 255);

            canvas.save();
            canvas.translate(40.0f, 40.0f);

            mPaint.setShader(mComposeShader);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.translate(0.0f, 40.0f + mDrawHeight);
            mPaint.setShader(mCompose2Shader);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.translate(0.0f, 40.0f + mDrawHeight);
            mPaint.setShader(mCompose3Shader);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.restore();

            canvas.save();
            canvas.translate(40.0f + mDrawWidth + 40.0f, 40.0f);

            mPaint.setShader(mCompose4Shader);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.translate(0.0f, 40.0f + mDrawHeight);
            mPaint.setShader(mCompose5Shader);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.translate(0.0f, 40.0f + mDrawHeight);
            mPaint.setShader(mCompose6Shader);
            canvas.drawRect(0.0f, 0.0f, mDrawWidth, mDrawHeight, mPaint);

            canvas.restore();
        }
    }
}
