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
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;

/**
 * Draws hundreds of levels of overdraw over the content area.
 *
 * This should all be optimized out by the renderer.
 */
public class FullscreenOverdrawActivity extends AppCompatActivity {
    private class OverdrawDrawable extends Drawable {
        Paint paint = new Paint();
        int mColorValue = 0;

        @SuppressWarnings("unused")
        public void setColorValue(int colorValue) {
            mColorValue = colorValue;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            paint.setColor(Color.rgb(mColorValue, 255 - mColorValue, 255));

            for (int i = 0; i < 400; i++) {
                canvas.drawRect(getBounds(), paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        OverdrawDrawable overdraw = new OverdrawDrawable();
        getWindow().setBackgroundDrawable(overdraw);

        setContentView(new View(this));

        ObjectAnimator objectAnimator = ObjectAnimator.ofInt(overdraw, "colorValue", 0, 255);
        objectAnimator.setRepeatMode(ValueAnimator.REVERSE);
        objectAnimator.setRepeatCount(ValueAnimator.INFINITE);
        objectAnimator.start();
    }
}
