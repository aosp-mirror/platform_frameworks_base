/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.glwallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Message;
import android.util.Log;

/**
 * A helper class that computes histogram and percentile 85 from a bitmap.
 * Percentile 85 will be computed each time the user picks a new image wallpaper.
 */
class ImageProcessHelper {
    private static final String TAG = ImageProcessHelper.class.getSimpleName();
    private static final float DEFAULT_PER85 = 0.8f;
    private static final int MSG_UPDATE_PER85 = 1;

    /**
     * This color matrix will be applied to each pixel to get luminance from rgb by below formula:
     * Luminance = .2126f * r + .7152f * g + .0722f * b.
     */
    private static final float[] LUMINOSITY_MATRIX = new float[] {
            .2126f,     .0000f,     .0000f,     .0000f,     .0000f,
            .0000f,     .7152f,     .0000f,     .0000f,     .0000f,
            .0000f,     .0000f,     .0722f,     .0000f,     .0000f,
            .0000f,     .0000f,     .0000f,     1.000f,     .0000f
    };

    private final Handler mHandler = new Handler(new Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_PER85:
                    mPer85 = (float) msg.obj;
                    return true;
                default:
                    return false;
            }
        }
    });

    private float mPer85 = DEFAULT_PER85;

    void startComputingPercentile85(Bitmap bitmap) {
        new Per85ComputeTask(mHandler).execute(bitmap);
    }

    float getPercentile85() {
        return mPer85;
    }

    private static class Per85ComputeTask extends AsyncTask<Bitmap, Void, Float> {
        private Handler mUpdateHandler;

        Per85ComputeTask(Handler handler) {
            super(handler);
            mUpdateHandler = handler;
        }

        @Override
        protected Float doInBackground(Bitmap... bitmaps) {
            Bitmap bitmap = bitmaps[0];
            if (bitmap != null) {
                int[] histogram = processHistogram(bitmap);
                return computePercentile85(bitmap, histogram);
            }
            Log.e(TAG, "Per85ComputeTask: Can't get bitmap");
            return DEFAULT_PER85;
        }

        @Override
        protected void onPostExecute(Float result) {
            Message msg = mUpdateHandler.obtainMessage(MSG_UPDATE_PER85, result);
            mUpdateHandler.sendMessage(msg);
        }

        private int[] processHistogram(Bitmap bitmap) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            Bitmap target = Bitmap.createBitmap(width, height, bitmap.getConfig());
            Canvas canvas = new Canvas(target);
            ColorMatrix cm = new ColorMatrix(LUMINOSITY_MATRIX);
            Paint paint = new Paint();
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
            canvas.drawBitmap(bitmap, new Matrix(), paint);

            // TODO: Fine tune the performance here, tracking on b/123615079.
            int[] histogram = new int[256];
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int pixel = target.getPixel(col, row);
                    int y = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel);
                    histogram[y]++;
                }
            }

            return histogram;
        }

        private float computePercentile85(Bitmap bitmap, int[] histogram) {
            float per85 = DEFAULT_PER85;
            int pixelCount = bitmap.getWidth() * bitmap.getHeight();
            float[] acc = new float[256];
            for (int i = 0; i < acc.length; i++) {
                acc[i] = (float) histogram[i] / pixelCount;
                float prev = i == 0 ? 0f : acc[i - 1];
                float next = acc[i];
                float idx = (float) (i + 1) / 255;
                float sum = prev + next;
                if (prev < 0.85f && sum >= 0.85f) {
                    per85 = idx;
                }
                if (i > 0) {
                    acc[i] += acc[i - 1];
                }
            }
            return per85;
        }
    }
}
