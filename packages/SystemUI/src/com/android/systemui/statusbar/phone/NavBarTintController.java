/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.SurfaceControl;
import android.view.View;

import com.android.systemui.R;

public class NavBarTintController {
    public static final int MIN_COLOR_ADAPT_TRANSITION_TIME = 400;
    public static final int DEFAULT_COLOR_ADAPT_TRANSITION_TIME = 1700;

    private final HandlerThread mColorAdaptHandlerThread = new HandlerThread("ColorExtractThread");
    private Handler mColorAdaptionHandler;

    // Poll time for each iteration to color sample
    private static final int COLOR_ADAPTION_TIMEOUT = 300;

    // Passing the threshold of this luminance value will make the button black otherwise white
    private static final float LUMINANCE_THRESHOLD = 0.3f;

    // The margin from the bounds of the view to color sample around
    private static final int COLOR_SAMPLE_MARGIN = 10;

    private boolean mRunning;

    private final NavigationBarView mNavigationBarView;
    private final LightBarTransitionsController mLightBarController;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final int mBarRadius;
    private final int mBarBottom;

    public NavBarTintController(NavigationBarView navigationBarView,
            LightBarTransitionsController lightBarController) {
        mNavigationBarView = navigationBarView;
        mLightBarController = lightBarController;

        final Resources res = navigationBarView.getResources();
        mBarRadius = res.getDimensionPixelSize(R.dimen.navigation_handle_radius);
        mBarBottom = res.getDimensionPixelSize(R.dimen.navigation_handle_bottom);
    }

    public void start() {
        if (!isEnabled(mNavigationBarView.getContext())) {
            return;
        }
        if (mColorAdaptionHandler == null) {
            mColorAdaptHandlerThread.start();
            mColorAdaptionHandler = new Handler(mColorAdaptHandlerThread.getLooper());
        }
        mColorAdaptionHandler.removeCallbacksAndMessages(null);
        mColorAdaptionHandler.post(this::updateTint);
        mRunning = true;
    }

    public void end() {
        if (mColorAdaptionHandler != null) {
            mColorAdaptionHandler.removeCallbacksAndMessages(null);
        }
        mRunning = false;
    }

    public void stop() {
        end();
        if (mColorAdaptionHandler != null) {
            mColorAdaptHandlerThread.quitSafely();
        }
    }

    private void updateTint() {
        int[] navPos = new int[2];
        int[] butPos = new int[2];
        View view = mNavigationBarView.getHomeHandle().getCurrentView();
        if (view == null) {
            return;
        }

        // Determine the area of the icon within its view bounds
        view.getLocationInSurface(butPos);
        final int navWidth = view.getWidth();
        final int navHeight = view.getHeight();
        int viewBottom = butPos[1] + navHeight - mBarBottom;
        final Rect viewIconRect = new Rect(butPos[0], viewBottom - mBarRadius * 2,
                butPos[0] + navWidth, viewBottom);

        if (mNavigationBarView.getCurrentView() == null || viewIconRect.isEmpty()) {
            scheduleColorAdaption();
            return;
        }
        mNavigationBarView.getCurrentView().getLocationOnScreen(navPos);
        viewIconRect.offset(navPos[0], navPos[1]);

        // Apply a margin area around the button region to sample the colors, crop from screenshot
        final Rect cropRect = new Rect(viewIconRect);
        cropRect.inset(-COLOR_SAMPLE_MARGIN, -COLOR_SAMPLE_MARGIN);
        if (cropRect.isEmpty()) {
            scheduleColorAdaption();
            return;
        }

        // Determine the size of the home area
        Rect homeArea = new Rect(COLOR_SAMPLE_MARGIN, COLOR_SAMPLE_MARGIN,
                viewIconRect.width() + COLOR_SAMPLE_MARGIN,
                viewIconRect.height() + COLOR_SAMPLE_MARGIN);

        // Get the screenshot around the home button icon to determine the color
        DisplayMetrics mDisplayMetrics = new DisplayMetrics();
        mNavigationBarView.getContext().getDisplay().getRealMetrics(mDisplayMetrics);
        final Bitmap hardBitmap = SurfaceControl
                .screenshot(new Rect(), mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels,
                        mNavigationBarView.getContext().getDisplay().getRotation());
        if (cropRect.bottom <= hardBitmap.getHeight()
                && cropRect.left + cropRect.width() <= hardBitmap.getWidth()) {
            final Bitmap cropBitmap = Bitmap.createBitmap(hardBitmap, cropRect.left, cropRect.top,
                    cropRect.width(), cropRect.height());
            final Bitmap softBitmap = cropBitmap.copy(Config.ARGB_8888, false);

            // Get the luminance value to determine if the home button should be black or white
            final int[] pixels = new int[softBitmap.getByteCount() / 4];
            softBitmap.getPixels(pixels, 0, softBitmap.getWidth(), 0, 0, softBitmap.getWidth(),
                    softBitmap.getHeight());
            float r = 0, g = 0, blue = 0;

            int width = cropRect.width();
            int total = 0;
            for (int i = 0; i < pixels.length; i += 4) {
                int x = i % width;
                int y = i / width;
                if (!homeArea.contains(x, y)) {
                    r += Color.red(pixels[i]);
                    g += Color.green(pixels[i]);
                    blue += Color.blue(pixels[i]);
                    total++;
                }
            }

            r /= total;
            g /= total;
            blue /= total;

            r = Math.max(Math.min(r / 255f, 1), 0);
            g = Math.max(Math.min(g / 255f, 1), 0);
            blue = Math.max(Math.min(blue / 255f, 1), 0);

            if (r <= 0.03928) {
                r /= 12.92;
            } else {
                r = (float) Math.pow((r + 0.055) / 1.055, 2.4);
            }
            if (g <= 0.03928) {
                g /= 12.92;
            } else {
                g = (float) Math.pow((g + 0.055) / 1.055, 2.4);
            }
            if (blue <= 0.03928) {
                blue /= 12.92;
            } else {
                blue = (float) Math.pow((blue + 0.055) / 1.055, 2.4);
            }

            if (r * 0.2126 + g * 0.7152 + blue * 0.0722 > LUMINANCE_THRESHOLD) {
                // Black
                mMainHandler.post(
                        () -> mLightBarController
                                .setIconsDark(true /* dark */, true /* animate */));
            } else {
                // White
                mMainHandler.post(
                        () -> mLightBarController
                                .setIconsDark(false /* dark */, true /* animate */));
            }
            cropBitmap.recycle();
            hardBitmap.recycle();
        }
        scheduleColorAdaption();
    }

    private void scheduleColorAdaption() {
        mColorAdaptionHandler.removeCallbacksAndMessages(null);
        if (!mRunning || !isEnabled(mNavigationBarView.getContext())) {
            return;
        }
        mColorAdaptionHandler.postDelayed(this::updateTint, COLOR_ADAPTION_TIMEOUT);
    }

    public static boolean isEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                NavigationPrototypeController.NAV_COLOR_ADAPT_ENABLE_SETTING, 0) == 1
            && Settings.Global.getInt(context.getContentResolver(),
                NavigationPrototypeController.SHOW_HOME_HANDLE_SETTING, 0) == 1;
    }
}
