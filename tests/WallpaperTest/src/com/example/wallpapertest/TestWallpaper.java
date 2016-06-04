/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.wallpapertest;

import android.service.wallpaper.WallpaperService;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.SurfaceHolder;
import android.content.res.XmlResourceParser;

import android.os.Handler;
import android.util.Log;

import android.view.WindowInsets;

public class TestWallpaper extends WallpaperService {
    private static final String LOG_TAG = "PolarClock";
    
    private final Handler mHandler = new Handler();

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public Engine onCreateEngine() {
        return new ClockEngine();
    }

    class ClockEngine extends Engine {
        private static final int OUTER_COLOR = 0xffff0000;
        private static final int INNER_COLOR = 0xff000080;
        private static final int STABLE_COLOR = 0xa000ff00;
        private static final int TEXT_COLOR = 0xa0ffffff;

        private final Paint.FontMetrics mTextMetrics = new Paint.FontMetrics();

        private int mPadding;

        private final Rect mMainInsets = new Rect();
        private final Rect mStableInsets = new Rect();
        private boolean mRound = false;

        private int mDesiredWidth;
        private int mDesiredHeight;

        private float mOffsetX;
        private float mOffsetY;
        private float mOffsetXStep;
        private float mOffsetYStep;
        private int mOffsetXPixels;
        private int mOffsetYPixels;

        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        private final Runnable mDrawClock = new Runnable() {
            public void run() {
                drawFrame();
            }
        };
        private boolean mVisible;

        ClockEngine() {
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);

            mDesiredWidth = getDesiredMinimumWidth();
            mDesiredHeight = getDesiredMinimumHeight();

            Paint paint = mFillPaint;
            paint.setStyle(Paint.Style.FILL);

            paint = mStrokePaint;
            paint.setStrokeWidth(3);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);

            TextPaint tpaint = mTextPaint;
            tpaint.density = getResources().getDisplayMetrics().density;
            tpaint.setCompatibilityScaling(getResources().getCompatibilityInfo().applicationScale);
            tpaint.setColor(TEXT_COLOR);
            tpaint.setTextSize(18 * getResources().getDisplayMetrics().scaledDensity);
            tpaint.setShadowLayer(4 * getResources().getDisplayMetrics().density, 0, 0, 0xff000000);

            mTextPaint.getFontMetrics(mTextMetrics);

            mPadding = (int)(16 * getResources().getDisplayMetrics().density);

            if (isPreview()) {
                mOffsetX = 0.5f;
                mOffsetY = 0.5f;
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            mHandler.removeCallbacks(mDrawClock);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            mVisible = visible;
            if (!visible) {
                mHandler.removeCallbacks(mDrawClock);
            }
            drawFrame();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            drawFrame();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);

            // Simulate some slowness, so we can test the loading process in the live wallpaper
            // picker.
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            mVisible = false;
            mHandler.removeCallbacks(mDrawClock);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            mMainInsets.set(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            mStableInsets.set(insets.getStableInsetLeft(), insets.getStableInsetTop(),
                    insets.getStableInsetRight(), insets.getStableInsetBottom());
            mRound = insets.isRound();
            drawFrame();
        }

        @Override
        public void onDesiredSizeChanged(int desiredWidth, int desiredHeight) {
            super.onDesiredSizeChanged(desiredWidth, desiredHeight);
            mDesiredWidth = desiredWidth;
            mDesiredHeight = desiredHeight;
            drawFrame();
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                float xStep, float yStep, int xPixels, int yPixels) {
            super.onOffsetsChanged(xOffset, yOffset, xStep, yStep, xPixels, yPixels);

            if (isPreview()) return;

            mOffsetX = xOffset;
            mOffsetY = yOffset;
            mOffsetXStep = xStep;
            mOffsetYStep = yStep;
            mOffsetXPixels = xPixels;
            mOffsetYPixels = yPixels;

            drawFrame();
        }

        void drawFrame() {
            final SurfaceHolder holder = getSurfaceHolder();
            final Rect frame = holder.getSurfaceFrame();
            final int width = frame.width();
            final int height = frame.height();

            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) {
                    final Paint paint = mFillPaint;

                    paint.setColor(OUTER_COLOR);
                    c.drawRect(0, 0, width, height, paint);

                    paint.setColor(INNER_COLOR);
                    c.drawRect(0+mMainInsets.left, 0+mMainInsets.top,
                            width-mMainInsets.right, height-mMainInsets.bottom, paint);

                    mStrokePaint.setColor(STABLE_COLOR);
                    c.drawRect(0 + mStableInsets.left, 0 + mStableInsets.top,
                            width - mStableInsets.right, height - mStableInsets.bottom,
                            mStrokePaint);

                    final int ascdesc = (int)(-mTextMetrics.ascent + mTextMetrics.descent);
                    final int linegap = (int)(-mTextMetrics.ascent + mTextMetrics.descent
                            + mTextMetrics.leading);

                    int x = mStableInsets.left + mPadding;
                    int y = height - mStableInsets.bottom - mPadding - ascdesc;
                    c.drawText("Surface Size: " + width + " x " + height,
                            x, y, mTextPaint);
                    y -= linegap;
                    c.drawText("Desired Size: " + mDesiredWidth + " x " + mDesiredHeight,
                            x, y, mTextPaint);
                    y -= linegap;
                    c.drawText("Cur Offset Raw: " + mOffsetX + ", " + mOffsetY,
                            x, y, mTextPaint);
                    y -= linegap;
                    c.drawText("Cur Offset Step: " + mOffsetXStep + ", " + mOffsetYStep,
                            x, y, mTextPaint);
                    y -= linegap;
                    c.drawText("Cur Offset Pixels: " + mOffsetXPixels + ", " + mOffsetYPixels,
                            x, y, mTextPaint);
                    y -= linegap;
                    c.drawText("Stable Insets: (" + mStableInsets.left + ", " + mStableInsets.top
                            + ") - (" + mStableInsets.right + ", " + mStableInsets.bottom + ")",
                            x, y, mTextPaint);
                    y -= linegap;
                    c.drawText("System Insets: (" + mMainInsets.left + ", " + mMainInsets.top
                            + ") - (" + mMainInsets.right + ", " + mMainInsets.bottom + ")",
                            x, y, mTextPaint);

                }
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }
        }
    }
}
