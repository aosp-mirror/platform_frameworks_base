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
import android.graphics.ColorFilter;
import android.graphics.ComposeShader;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class MoreShadersActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new ShadersView(this));
    }

    static class ShadersView extends View {
        private BitmapShader mScaledShader;
        private int mTexWidth;
        private int mTexHeight;
        private Paint mPaint;
        private float mDrawWidth;
        private float mDrawHeight;
        private LinearGradient mHorGradient;
        private LinearGradient mVertGradient;
        private ComposeShader mComposeShader;
        private ComposeShader mCompose2Shader;
        private Paint mLargePaint;
        private BitmapShader mScaled2Shader;
        private ColorFilter mColorFilter;
        private final Matrix mMtx1;

        ShadersView(Context c) {
            super(c);

            Bitmap texture = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            mTexWidth = texture.getWidth();
            mTexHeight = texture.getHeight();
            mDrawWidth = mTexWidth * 2.2f;
            mDrawHeight = mTexHeight * 1.2f;

            mScaledShader = new BitmapShader(texture, Shader.TileMode.MIRROR,
                    Shader.TileMode.MIRROR);
            Matrix m2 = new Matrix();
            m2.setScale(0.1f, 0.1f);
            mScaledShader.setLocalMatrix(m2);
            
            mScaled2Shader = new BitmapShader(texture, Shader.TileMode.MIRROR,
                    Shader.TileMode.MIRROR);
            Matrix m3 = new Matrix();
            m3.setScale(0.1f, 0.1f);
            mScaled2Shader.setLocalMatrix(m3);

            mHorGradient = new LinearGradient(0.0f, 0.0f, mDrawWidth, 0.0f,
                    Color.RED, 0x7f00ff00, Shader.TileMode.CLAMP);
            Matrix m4 = new Matrix();
            m4.setScale(0.5f, 0.5f);
            mHorGradient.setLocalMatrix(m4);

            mVertGradient = new LinearGradient(0.0f, 0.0f, 0.0f, mDrawHeight / 2.0f,
                    Color.YELLOW, Color.MAGENTA, Shader.TileMode.MIRROR);

            mComposeShader = new ComposeShader(mScaledShader, mHorGradient,
                    PorterDuff.Mode.SRC_OVER);
            mMtx1 = new Matrix();
            mMtx1.setTranslate(mTexWidth / 2.0f, mTexHeight / 2.0f);
            mMtx1.postRotate(45, 0, 0);
            mComposeShader.setLocalMatrix(mMtx1);

            mCompose2Shader = new ComposeShader(mHorGradient, mScaledShader,
                    PorterDuff.Mode.SRC_OUT);

            mColorFilter = new LightingColorFilter(0x0060ffff, 0x00101030);
 
            mLargePaint = new Paint();
            mLargePaint.setAntiAlias(true);
            mLargePaint.setTextSize(36.0f);
            mLargePaint.setColor(0xff000000);
            mLargePaint.setShadowLayer(3.0f, 0.0f, 3.0f, 0x7f00ff00);

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

            canvas.restore();

            canvas.save();
            canvas.translate(40.0f + mDrawWidth + 40.0f, 40.0f);

            mLargePaint.setShader(mHorGradient);
            canvas.drawText("OpenGL rendering", 0.0f, 20.0f, mLargePaint);

            mLargePaint.setShader(mScaled2Shader);
            canvas.drawText("OpenGL rendering", 0.0f, 60.0f, mLargePaint);

            mLargePaint.setShader(mCompose2Shader);
            mLargePaint.setColorFilter(mColorFilter);
            canvas.drawText("OpenGL rendering", 0.0f, 100.0f, mLargePaint);
            mLargePaint.setColorFilter(null);

            canvas.translate(0.0f, 40.0f + mDrawHeight);
            mLargePaint.setShader(mVertGradient);
            canvas.drawText("OpenGL rendering", 0.0f, 20.0f, mLargePaint);

            canvas.restore();
        }
    }
}
