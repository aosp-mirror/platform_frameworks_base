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
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class AdvancedGradientsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new GradientsView(this));
    }

    public static class GradientsView extends View {
        private final Paint mPaint;
        private final SweepGradient mSweepGradient;
        private final RadialGradient mRadialGradient;
        private final Matrix mMatrix;
        private final Matrix mMatrix2;
        private final Matrix mMatrix3;

        public GradientsView(Context c) {
            super(c);

            mSweepGradient = new SweepGradient(0.0f, 0.0f, 0xff000000, 0xffffffff);
            mRadialGradient = new RadialGradient(0.0f, 0.0f, 100.0f, 0xff000000, 0xffffffff,
                    Shader.TileMode.MIRROR);
            
            mMatrix = new Matrix();
            mMatrix.setRotate(-45, 0.0f, 0.0f);
            mMatrix.postTranslate(100.0f, 100.0f);

            mMatrix2 = new Matrix();
            mMatrix2.setScale(1.0f, 2.0f);
            mMatrix2.postRotate(-45, 0.0f, 0.0f);

            mMatrix3 = new Matrix();
            mMatrix3.setTranslate(100.0f, 100.0f);            
            
            mPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(255, 255, 255);

            canvas.save();
            canvas.translate(130.0f, 100.0f);

            mSweepGradient.setLocalMatrix(mMatrix3);
            mPaint.setShader(mSweepGradient);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 200.0f, mPaint);

            canvas.translate(400.0f, 000.0f);
            
            mSweepGradient.setLocalMatrix(mMatrix);
            mPaint.setShader(mSweepGradient);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 200.0f, mPaint);            

            canvas.translate(400.0f, 000.0f);
            
            mSweepGradient.setLocalMatrix(mMatrix2);
            mPaint.setShader(mSweepGradient);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 200.0f, mPaint);

            canvas.translate(-800.0f, 300.0f);

            mRadialGradient.setLocalMatrix(null);
            mPaint.setShader(mRadialGradient);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 200.0f, mPaint);
            
            canvas.translate(400.0f, 000.0f);
            
            mRadialGradient.setLocalMatrix(mMatrix);
            mPaint.setShader(mRadialGradient);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 200.0f, mPaint);

            canvas.translate(400.0f, 000.0f);
            
            mRadialGradient.setLocalMatrix(mMatrix2);
            mPaint.setShader(mRadialGradient);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 200.0f, mPaint);
            
            
            canvas.restore();
        }
    }
}
