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

    static class GradientsView extends View {
        private final Paint mPaint;
        private final SweepGradient mSweepGradient;
        private final RadialGradient mRadialGradient;
        private final Matrix mMatrix;
        private final Matrix mMatrix2;

        GradientsView(Context c) {
            super(c);

            mSweepGradient = new SweepGradient(100.0f, 100.0f, 0xff000000, 0xffffffff);
            mRadialGradient = new RadialGradient(100.0f, 100.0f, 100.0f, 0xff000000, 0xffffffff,
                    Shader.TileMode.MIRROR);
            
            mMatrix = new Matrix();
            mMatrix.setTranslate(50.0f, 50.0f);

            mMatrix2 = new Matrix();
            mMatrix2.setScale(2.0f, 2.0f);
            
            mPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(255, 255, 255);

            // Bitmap shaders
            canvas.save();
            canvas.translate(130.0f, 100.0f);

            mSweepGradient.setLocalMatrix(null);
            mPaint.setShader(mSweepGradient);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 200.0f, mPaint);

            canvas.translate(400.0f, 000.0f);
            
            mPaint.setShader(mRadialGradient);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 200.0f, mPaint);

            canvas.translate(400.0f, 000.0f);
            
            mSweepGradient.setLocalMatrix(mMatrix);
            mPaint.setShader(mSweepGradient);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 200.0f, mPaint);

            canvas.translate(-800.0f, 300.0f);
            
            mSweepGradient.setLocalMatrix(mMatrix2);
            mPaint.setShader(mSweepGradient);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 200.0f, mPaint);
            
            
            canvas.restore();
        }
    }
}
