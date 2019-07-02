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
package com.android.keyguard.clock;

import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * Creates a preview image ({@link Bitmap}) of a {@link View} for a custom clock face.
 */
final class ViewPreviewer {

    private static final String TAG = "ViewPreviewer";

    /**
     * Handler used to run {@link View#draw(Canvas)} on the main thread.
     */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * Generate a realistic preview of a clock face.
     *
     * @param view view is used to generate preview image.
     * @param width width of the preview image, should be the same as device width in pixels.
     * @param height height of the preview image, should be the same as device height in pixels.
     * @return bitmap of view.
     */
    @Nullable
    Bitmap createPreview(View view, int width, int height) {
        if (view == null) {
            return null;
        }
        FutureTask<Bitmap> task = new FutureTask<>(new Callable<Bitmap>() {
            @Override
            public Bitmap call() {
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                // Draw clock view hierarchy to canvas.
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.BLACK);
                dispatchVisibilityAggregated(view, true);
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, width, height);
                view.draw(canvas);

                return bitmap;
            }
        });

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            mMainHandler.post(task);
        }

        try {
            return task.get();
        } catch (Exception e) {
            Log.e(TAG, "Error completing task", e);
            return null;
        }
    }

    private void dispatchVisibilityAggregated(View view, boolean isVisible) {
        // Similar to View.dispatchVisibilityAggregated implementation.
        final boolean thisVisible = view.getVisibility() == View.VISIBLE;
        if (thisVisible || !isVisible) {
            view.onVisibilityAggregated(isVisible);
        }

        if (view instanceof ViewGroup) {
            isVisible = thisVisible && isVisible;
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();

            for (int i = 0; i < count; i++) {
                dispatchVisibilityAggregated(vg.getChildAt(i), isVisible);
            }
        }
    }
}
