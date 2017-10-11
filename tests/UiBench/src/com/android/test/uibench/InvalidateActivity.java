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
import android.os.Bundle;
import android.support.annotation.ColorInt;
import android.support.v7.app.AppCompatActivity;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Tests invalidation performance by invalidating a large number of easily rendered views,
 */
public class InvalidateActivity extends AppCompatActivity {
    private static class ColorView extends View {
        @ColorInt
        public int mColor;

        public ColorView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public void setColor(@ColorInt int color) {
            mColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(mColor);
        }
    }

    private ColorView[][] mColorViews;

    @SuppressWarnings("unused")
    public void setColorValue(int colorValue) {
        @ColorInt int a = Color.rgb(colorValue, 255 - colorValue, 255);
        @ColorInt int b = Color.rgb(255, colorValue, 255 - colorValue);
        for (int y = 0; y < mColorViews.length; y++) {
            for (int x = 0; x < mColorViews[y].length; x++) {
                mColorViews[y][x].setColor((x + y) % 2 == 0 ? a : b);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_invalidate);

        ViewGroup root = (ViewGroup) findViewById(R.id.invalidate_root);
        for (int y = 0; y < root.getChildCount(); y++) {
            ViewGroup row = (ViewGroup) root.getChildAt(y);
            if (mColorViews == null) {
                mColorViews = new ColorView[root.getChildCount()][row.getChildCount()];
            }

            for (int x = 0; x < row.getChildCount(); x++) {
                mColorViews[y][x] = (ColorView) row.getChildAt(x);
            }
        }

        ObjectAnimator animator = ObjectAnimator.ofInt(this, "colorValue", 0, 255);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.start();
    }
}
