/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.benchmark.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import com.android.benchmark.R;
import com.android.benchmark.registry.BenchmarkRegistry;
import com.android.benchmark.ui.automation.Automator;
import com.android.benchmark.ui.automation.Interaction;

public class FullScreenOverdrawActivity extends AppCompatActivity {

    private Automator mAutomator;

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
        public boolean onTouchEvent(MotionEvent event) {
            ObjectAnimator objectAnimator = ObjectAnimator.ofInt(this, "colorValue", 0, 255);
            objectAnimator.setRepeatMode(ValueAnimator.REVERSE);
            objectAnimator.setRepeatCount(100);
            objectAnimator.start();
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(Color.rgb(mColorValue, 255 - mColorValue, 255));

            for (int i = 0; i < 10; i++) {
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final OverdrawView overdrawView = new OverdrawView(this);
        overdrawView.setKeepScreenOn(true);
        setContentView(overdrawView);

        final int runId = getIntent().getIntExtra("com.android.benchmark.RUN_ID", 0);
        final int iteration = getIntent().getIntExtra("com.android.benchmark.ITERATION", -1);

        String name = BenchmarkRegistry.getBenchmarkName(this, R.id.benchmark_overdraw);

        mAutomator = new Automator(name, runId, iteration, getWindow(),
                new Automator.AutomateCallback() {
                    @Override
                    public void onPostAutomate() {
                        Intent result = new Intent();
                        setResult(RESULT_OK, result);
                        finish();
                    }

                    @Override
                    public void onAutomate() {
                        int[] coordinates = new int[2];
                        overdrawView.getLocationOnScreen(coordinates);

                        int x = coordinates[0];
                        int y = coordinates[1];

                        float width = overdrawView.getWidth();
                        float height = overdrawView.getHeight();

                        float middleX = (x + width) / 5;
                        float middleY = (y + height) / 5;

                        addInteraction(Interaction.newTap(middleX, middleY));
                    }
                });

        mAutomator.start();
    }
}
