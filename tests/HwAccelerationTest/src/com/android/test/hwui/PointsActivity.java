/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;

@SuppressWarnings({"UnusedDeclaration"})
public class PointsActivity extends Activity {

    float mSeekValue = .5f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xff000000));
        SeekBar slider = new SeekBar(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        setContentView(container);

        container.addView(slider);
        slider.setMax(100);
        slider.setProgress(50);
        FrameLayout frame = new FrameLayout(this);
        final RenderingView gpuView = new RenderingView(this, Color.GREEN);
        frame.addView(gpuView);
        final RenderingView swView = new RenderingView(this, Color.RED);
        swView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        frame.addView(swView);
        container.addView(frame);

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mSeekValue = (float)progress / 100.0f;
                float gpuAlpha = Math.min(2.0f * mSeekValue, 1f);
                gpuView.setAlpha(gpuAlpha);
                float swAlpha = Math.min((1 - mSeekValue) * 2.0f, 1f);
                System.out.println("(gpuAlpha, swAlpha = " + gpuAlpha + ", " + swAlpha);
                swView.setAlpha(swAlpha);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    public static class RenderingView extends View {

        private int mColor;

        public RenderingView(Context c, int color) {
            super(c);
            mColor = color;
        }

        private void drawPoints(Canvas canvas, Paint p, float xOffset, float yOffset) {
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Paint p = new Paint();
            p.setColor(mColor);

            float yOffset = 0;
            for (int i = 0; i < 2; ++i) {
                float xOffset = 0;

                p.setStrokeWidth(0f);
                p.setStrokeCap(Paint.Cap.SQUARE);
                canvas.drawPoint(100 + xOffset, 100 + yOffset, p);
                xOffset += 5;

                p.setStrokeWidth(1f);
                canvas.drawPoint(100 + xOffset, 100 + yOffset, p);
                xOffset += 15;

                p.setStrokeWidth(20);
                canvas.drawPoint(100 + xOffset, 100 + yOffset, p);
                xOffset += 30;

                p.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawPoint(100 + xOffset, 100 + yOffset, p);

                p.setAntiAlias(true);
                yOffset += 30;
            }

        }
    }
}
