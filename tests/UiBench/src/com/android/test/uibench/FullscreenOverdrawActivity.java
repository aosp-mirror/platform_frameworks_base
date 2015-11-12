/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.test.uibench;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

/**
 * Draws hundreds of levels of overdraw over the content area.
 *
 * This should all be optimized out by the renderer.
 */
public class FullscreenOverdrawActivity extends AppCompatActivity {
    private class OverdrawView extends View {
        Paint paint = new Paint();
        int mColorValue = 0;

        public OverdrawView(Context context) {
            super(context);
        }

        @SuppressWarnings("unused")
        public void setColorValue(int colorValue) {
            mColorValue = colorValue;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(Color.rgb(mColorValue, 255 - mColorValue, 255));

            for (int i = 0; i < 400; i++) {
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        OverdrawView overdrawView = new OverdrawView(this);
        setContentView(overdrawView);

        ObjectAnimator objectAnimator = ObjectAnimator.ofInt(overdrawView, "colorValue", 0, 255);
        objectAnimator.setRepeatMode(ValueAnimator.REVERSE);
        objectAnimator.setRepeatCount(ValueAnimator.INFINITE);
        objectAnimator.start();
    }
}
