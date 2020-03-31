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

import static com.android.systemui.glwallpaper.ImageWallpaperRenderer.WallpaperTexture;

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
 * A helper class that computes threshold from a bitmap.
 * Threshold will be computed each time the user picks a new image wallpaper.
 */
class ImageProcessHelper {
    private static final String TAG = ImageProcessHelper.class.getSimpleName();
    private static final float DEFAULT_THRESHOLD = 0.8f;
    private static final float DEFAULT_OTSU_THRESHOLD = 0f;
    private static final float MAX_THRESHOLD = 0.89f;
    private static final int MSG_UPDATE_THRESHOLD = 1;

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
                case MSG_UPDATE_THRESHOLD:
                    mThreshold = (float) msg.obj;
                    return true;
                default:
                    return false;
            }
        }
    });

    private float mThreshold = DEFAULT_THRESHOLD;

    void start(WallpaperTexture texture) {
        new ThresholdComputeTask(mHandler).execute(texture);
    }

    float getThreshold() {
        return Math.min(mThreshold, MAX_THRESHOLD);
    }

    private static class ThresholdComputeTask extends AsyncTask<WallpaperTexture, Void, Float> {
        private Handler mUpdateHandler;

        ThresholdComputeTask(Handler handler) {
            super(handler);
            mUpdateHandler = handler;
        }

        @Override
        protected Float doInBackground(WallpaperTexture... textures) {
            WallpaperTexture texture = textures[0];
            final float[] threshold = new float[] {DEFAULT_THRESHOLD};
            if (texture == null) {
                Log.e(TAG, "ThresholdComputeTask: WallpaperTexture not initialized");
                return threshold[0];
            }

            texture.use(bitmap -> {
                if (bitmap != null) {
                    threshold[0] = new Threshold().compute(bitmap);
                } else {
                    Log.e(TAG, "ThresholdComputeTask: Can't get bitmap");
                }
            });
            return threshold[0];
        }

        @Override
        protected void onPostExecute(Float result) {
            Message msg = mUpdateHandler.obtainMessage(MSG_UPDATE_THRESHOLD, result);
            mUpdateHandler.sendMessage(msg);
        }
    }

    private static class Threshold {
        public float compute(Bitmap bitmap) {
            Bitmap grayscale = toGrayscale(bitmap);
            int[] histogram = getHistogram(grayscale);
            boolean isSolidColor = isSolidColor(grayscale, histogram);

            // We will see gray wallpaper during the transition if solid color wallpaper is set,
            // please refer to b/130360362#comment16.
            // As a result, we use Percentile85 rather than Otsus if a solid color wallpaper is set.
            ThresholdAlgorithm algorithm = isSolidColor ? new Percentile85() : new Otsus();
            return algorithm.compute(grayscale, histogram);
        }

        private Bitmap toGrayscale(Bitmap bitmap) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            Bitmap grayscale = Bitmap.createBitmap(width, height, bitmap.getConfig(),
                    false /* hasAlpha */, bitmap.getColorSpace());
            Canvas canvas = new Canvas(grayscale);
            ColorMatrix cm = new ColorMatrix(LUMINOSITY_MATRIX);
            Paint paint = new Paint();
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
            canvas.drawBitmap(bitmap, new Matrix(), paint);

            return grayscale;
        }

        private int[] getHistogram(Bitmap grayscale) {
            int width = grayscale.getWidth();
            int height = grayscale.getHeight();

            // TODO: Fine tune the performance here, tracking on b/123615079.
            int[] histogram = new int[256];
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int pixel = grayscale.getPixel(col, row);
                    int y = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel);
                    histogram[y]++;
                }
            }

            return histogram;
        }

        private boolean isSolidColor(Bitmap bitmap, int[] histogram) {
            boolean solidColor = false;
            int pixels = bitmap.getWidth() * bitmap.getHeight();

            // In solid color case, only one element of histogram has value,
            // which is pixel counts and the value of other elements should be 0.
            for (int value : histogram) {
                if (value != 0 && value != pixels) {
                    break;
                }
                if (value == pixels) {
                    solidColor = true;
                    break;
                }
            }
            return solidColor;
        }
    }

    private static class Percentile85 implements ThresholdAlgorithm {
        @Override
        public float compute(Bitmap bitmap, int[] histogram) {
            float per85 = DEFAULT_THRESHOLD;
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

    private static class Otsus implements ThresholdAlgorithm {
        @Override
        public float compute(Bitmap bitmap, int[] histogram) {
            float threshold = DEFAULT_OTSU_THRESHOLD;
            float maxVariance = 0;
            float pixelCount = bitmap.getWidth() * bitmap.getHeight();
            float[] w = new float[2];
            float[] m = new float[2];
            float[] u = new float[2];

            for (int i = 0; i < histogram.length; i++) {
                m[1] += i * histogram[i];
            }

            w[1] = pixelCount;
            for (int tonalValue = 0; tonalValue < histogram.length; tonalValue++) {
                float dU;
                float variance;
                float numPixels = histogram[tonalValue];
                float tmp = numPixels * tonalValue;
                w[0] += numPixels;
                w[1] -= numPixels;

                if (w[0] == 0 || w[1] == 0) {
                    continue;
                }

                m[0] += tmp;
                m[1] -= tmp;
                u[0] = m[0] / w[0];
                u[1] = m[1] / w[1];
                dU = u[0] - u[1];
                variance = w[0] * w[1] * dU * dU;

                if (variance > maxVariance) {
                    threshold = (tonalValue + 1f) / histogram.length;
                    maxVariance = variance;
                }
            }
            return threshold;
        }
    }

    private interface ThresholdAlgorithm {
        float compute(Bitmap bitmap, int[] histogram);
    }
}
