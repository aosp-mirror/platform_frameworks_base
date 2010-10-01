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
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class GradientsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new ShadersView(this));
    }

    static class ShadersView extends View {
        private final Paint mPaint;
        private final float mDrawWidth;
        private final float mDrawHeight;
        private final LinearGradient mGradient;
        private final Matrix mMatrix;

        ShadersView(Context c) {
            super(c);

            mDrawWidth = 200;
            mDrawHeight = 200;

            mGradient = new LinearGradient(0, 0, 0, 1, 0xFF000000, 0, Shader.TileMode.CLAMP);
            mMatrix = new Matrix();

            mPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(255, 255, 255);

            // Gradients
            canvas.save();
            float top = 40.0f;
            float right = 40.0f + mDrawWidth;
            float left = 40.0f;
            float bottom = 40.0f + mDrawHeight;

            mPaint.setShader(mGradient);

            mMatrix.setScale(1, mDrawWidth);
            mMatrix.postRotate(90);
            mMatrix.postTranslate(right, top);
            mGradient.setLocalMatrix(mMatrix);
            canvas.drawRect(right - mDrawWidth, top, right, top + mDrawHeight, mPaint);

            top += 40.0f + mDrawHeight;
            bottom += 40.0f + mDrawHeight;
            
            mMatrix.setScale(1, mDrawHeight);
            mMatrix.postTranslate(left, top);
            mGradient.setLocalMatrix(mMatrix);
            canvas.drawRect(left, top, right, top + mDrawHeight, mPaint);
            
            left += 40.0f + mDrawWidth;
            right += 40.0f + mDrawWidth;
            top -= 40.0f + mDrawHeight;
            bottom -= 40.0f + mDrawHeight;

            mMatrix.setScale(1, mDrawHeight);
            mMatrix.postRotate(180);
            mMatrix.postTranslate(left, bottom);
            mGradient.setLocalMatrix(mMatrix);
            canvas.drawRect(left, bottom - mDrawHeight, right, bottom, mPaint);

            top += 40.0f + mDrawHeight;
            bottom += 40.0f + mDrawHeight;
            
            mMatrix.setScale(1, mDrawWidth);
            mMatrix.postRotate(-90);
            mMatrix.postTranslate(left, top);
            mGradient.setLocalMatrix(mMatrix);
            canvas.drawRect(left, top, left + mDrawWidth, bottom, mPaint);
           
            canvas.restore();
        }
    }
}
