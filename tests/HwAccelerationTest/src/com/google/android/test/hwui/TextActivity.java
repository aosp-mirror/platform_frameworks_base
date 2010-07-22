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

package com.google.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
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

        CustomTextView(Context c) {
            super(c);

            mMediumPaint = new Paint();
            mMediumPaint.setAntiAlias(true);
            mMediumPaint.setColor(0xffff0000);
            mLargePaint = new Paint();
            mLargePaint.setAntiAlias(true);
            mLargePaint.setTextSize(36.0f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(255, 255, 255);
            
            canvas.drawText("Hello OpenGL renderer!", 100, 100, mMediumPaint);
            canvas.drawText("Hello OpenGL renderer!", 100, 200, mLargePaint);
            
            canvas.save();
            canvas.clipRect(150.0f, 220.0f, 450.0f, 320.0f);
            canvas.drawText("Hello OpenGL renderer!", 100, 300, mLargePaint);
            canvas.restore();
        }
    }
}