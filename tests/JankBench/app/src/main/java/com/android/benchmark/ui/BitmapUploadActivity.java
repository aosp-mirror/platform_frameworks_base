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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

import com.android.benchmark.R;
import com.android.benchmark.ui.automation.Automator;
import com.android.benchmark.ui.automation.Interaction;

/**
 *
 */
public class BitmapUploadActivity extends AppCompatActivity {
    private Automator mAutomator;

    public static class UploadView extends View {
        private int mColorValue;
        private Bitmap mBitmap;
        private final DisplayMetrics mMetrics = new DisplayMetrics();
        private final Rect mRect = new Rect();

        public UploadView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @SuppressWarnings("unused")
        public void setColorValue(int colorValue) {
            if (colorValue == mColorValue) return;

            mColorValue = colorValue;

            // modify the bitmap's color to ensure it's uploaded to the GPU
            mBitmap.eraseColor(Color.rgb(mColorValue, 255 - mColorValue, 255));

            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();

            getDisplay().getMetrics(mMetrics);
            int minDisplayDimen = Math.min(mMetrics.widthPixels, mMetrics.heightPixels);
            int bitmapSize = Math.min((int) (minDisplayDimen * 0.75), 720);
            if (mBitmap == null
                    || mBitmap.getWidth() != bitmapSize
                    || mBitmap.getHeight() != bitmapSize) {
                mBitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mBitmap != null) {
                mRect.set(0, 0, getWidth(), getHeight());
                canvas.drawBitmap(mBitmap, null, mRect, null);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // animate color to force bitmap uploads
            return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bitmap_upload);

        final View uploadRoot = findViewById(R.id.upload_root);
        uploadRoot.setKeepScreenOn(true);
        uploadRoot.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                UploadView uploadView = (UploadView) findViewById(R.id.upload_view);
                ObjectAnimator colorValueAnimator =
                        ObjectAnimator.ofInt(uploadView, "colorValue", 0, 255);
                colorValueAnimator.setRepeatMode(ValueAnimator.REVERSE);
                colorValueAnimator.setRepeatCount(100);
                colorValueAnimator.start();

                // animate scene root to guarantee there's a minimum amount of GPU rendering work
                ObjectAnimator yAnimator = ObjectAnimator.ofFloat(
                        view, "translationY", 0, 100);
                yAnimator.setRepeatMode(ValueAnimator.REVERSE);
                yAnimator.setRepeatCount(100);
                yAnimator.start();

                return true;
            }
        });

        final UploadView uploadView = (UploadView) findViewById(R.id.upload_view);
        final int runId = getIntent().getIntExtra("com.android.benchmark.RUN_ID", 0);
        final int iteration = getIntent().getIntExtra("com.android.benchmark.ITERATION", -1);

        mAutomator = new Automator("BMUpload", runId, iteration, getWindow(),
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
                        uploadRoot.getLocationOnScreen(coordinates);

                        int x = coordinates[0];
                        int y = coordinates[1];

                        float width = uploadRoot.getWidth();
                        float height = uploadRoot.getHeight();

                        float middleX = (x + width) / 5;
                        float middleY = (y + height) / 5;

                        addInteraction(Interaction.newTap(middleX, middleY));
                    }
                });

        mAutomator.start();
    }

}
