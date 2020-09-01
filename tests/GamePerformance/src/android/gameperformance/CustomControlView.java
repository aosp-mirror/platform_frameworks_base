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
package android.gameperformance;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.AnimationDrawable;
import android.view.WindowManager;
import android.widget.AbsoluteLayout;
import android.widget.ImageView;
import android.window.WindowMetricsHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * View that holds requested number of UI controls as ImageView with an infinite animation.
 */
public class CustomControlView extends AbsoluteLayout {
    private final static int CONTROL_DIMENSION = 48;

    private final int mPerRowControlCount;
    private List<Long> mFrameTimes = new ArrayList<>();

    public CustomControlView(@NonNull Context context) {
        super(context);

        final WindowManager wm = context.getSystemService(WindowManager.class);
        final int width = WindowMetricsHelper.getBoundsExcludingNavigationBarAndCutout(
                wm.getCurrentWindowMetrics()).width();
        mPerRowControlCount = width / CONTROL_DIMENSION;
    }

    /**
     * Helper class that overrides ImageView and observes draw requests. Only
     * one such control is created which is the first control in the view.
     */
    class ReferenceImageView extends ImageView {
        public ReferenceImageView(Context context) {
            super(context);
        }
        @Override
        public void draw(Canvas canvas) {
            reportFrame();
            super.draw(canvas);
        }
    }

    @WorkerThread
    public void createControls(
            @NonNull Activity activity, int controlCount) throws InterruptedException {
        synchronized (this) {
            final CountDownLatch latch = new CountDownLatch(1);
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    removeAllViews();

                    for (int i = 0; i < controlCount; ++i) {
                        final ImageView image = (i == 0) ?
                                new ReferenceImageView(activity) : new ImageView(activity);
                        final int x = (i % mPerRowControlCount) * CONTROL_DIMENSION;
                        final int y = (i / mPerRowControlCount) * CONTROL_DIMENSION;
                        final AbsoluteLayout.LayoutParams layoutParams =
                                new AbsoluteLayout.LayoutParams(
                                        CONTROL_DIMENSION, CONTROL_DIMENSION, x, y);
                        image.setLayoutParams(layoutParams);
                        image.setBackgroundResource(R.drawable.animation);
                        final AnimationDrawable animation =
                                (AnimationDrawable)image.getBackground();
                        animation.start();
                        addView(image);
                    }

                    latch.countDown();
                }
            });
            latch.await();
        }
    }

    @MainThread
    private void reportFrame() {
        final long time = System.currentTimeMillis();
        synchronized (mFrameTimes) {
            mFrameTimes.add(time);
        }
    }

    /**
     * Resets frame times in order to calculate FPS for the different test pass.
     */
    public void resetFrameTimes() {
        synchronized (mFrameTimes) {
            mFrameTimes.clear();
        }
    }

    /**
     * Returns current FPS based on collected frame times.
     */
    public double getFps() {
        synchronized (mFrameTimes) {
            if (mFrameTimes.size() < 2) {
                return 0.0f;
            }
            return 1000.0 * mFrameTimes.size() /
                    (mFrameTimes.get(mFrameTimes.size() - 1) - mFrameTimes.get(0));
        }
    }
}