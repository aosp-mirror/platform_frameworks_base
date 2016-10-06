/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.view.FrameMetrics;
import android.view.View;
import android.view.Window;
import android.view.Window.OnFrameMetricsAvailableListener;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

public class RenderingJitter extends Activity {
    private TextView mJitterReport;
    private TextView mUiFrameTimeReport;
    private TextView mRenderThreadTimeReport;
    private TextView mTotalFrameTimeReport;
    private TextView mMostlyTotalFrameTimeReport;

    private static Handler sMetricsHandler;
    static {
        HandlerThread thread = new HandlerThread("frameMetricsListener");
        thread.start();
        sMetricsHandler = new Handler(thread.getLooper());
    }

    private Handler mUpdateHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case R.id.jitter_mma:
                    mJitterReport.setText((CharSequence) msg.obj);
                    break;
                case R.id.totalish_mma:
                    mMostlyTotalFrameTimeReport.setText((CharSequence) msg.obj);
                    break;
                case R.id.ui_frametime_mma:
                    mUiFrameTimeReport.setText((CharSequence) msg.obj);
                    break;
                case R.id.rt_frametime_mma:
                    mRenderThreadTimeReport.setText((CharSequence) msg.obj);
                    break;
                case R.id.total_mma:
                    mTotalFrameTimeReport.setText((CharSequence) msg.obj);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.rendering_jitter);
        View content = findViewById(android.R.id.content);
        content.setBackground(new AnimatedBackgroundDrawable());
        content.setKeepScreenOn(true);
        mJitterReport = (TextView) findViewById(R.id.jitter_mma);
        mMostlyTotalFrameTimeReport = (TextView) findViewById(R.id.totalish_mma);
        mUiFrameTimeReport = (TextView) findViewById(R.id.ui_frametime_mma);
        mRenderThreadTimeReport = (TextView) findViewById(R.id.rt_frametime_mma);
        mTotalFrameTimeReport = (TextView) findViewById(R.id.total_mma);
        mJitterReport.setText("abcdefghijklmnopqrstuvwxyz");
        mMostlyTotalFrameTimeReport.setText("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        mUiFrameTimeReport.setText("0123456789");
        mRenderThreadTimeReport.setText(",.!()[]{};");
        getWindow().addOnFrameMetricsAvailableListener(mMetricsListener, sMetricsHandler);
    }

    private final OnFrameMetricsAvailableListener mMetricsListener = new OnFrameMetricsAvailableListener() {
        private final static double WEIGHT = 40;
        private long mPreviousFrameTotal;
        private double mJitterMma;
        private double mUiFrametimeMma;
        private double mRtFrametimeMma;
        private double mTotalFrametimeMma;
        private double mMostlyTotalFrametimeMma;
        private boolean mNeedsFirstValues = true;

        @Override
        public void onFrameMetricsAvailable(Window window, FrameMetrics frameMetrics,
                int dropCountSinceLastInvocation) {
            if (frameMetrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1) {
                return;
            }

            long uiDuration = frameMetrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
                    + frameMetrics.getMetric(FrameMetrics.ANIMATION_DURATION)
                    + frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                    + frameMetrics.getMetric(FrameMetrics.DRAW_DURATION);
            long rtDuration = frameMetrics.getMetric(FrameMetrics.SYNC_DURATION)
                    + frameMetrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION);
            long totalDuration = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION);
            long jitter = Math.abs(totalDuration - mPreviousFrameTotal);
            if (mNeedsFirstValues) {
                mJitterMma = 0;
                mUiFrametimeMma = uiDuration;
                mRtFrametimeMma = rtDuration;
                mTotalFrametimeMma = totalDuration;
                mMostlyTotalFrametimeMma = uiDuration + rtDuration;
                mNeedsFirstValues = false;
            } else {
                mJitterMma = add(mJitterMma, jitter);
                mUiFrametimeMma = add(mUiFrametimeMma, uiDuration);
                mRtFrametimeMma = add(mRtFrametimeMma, rtDuration);
                mTotalFrametimeMma = add(mTotalFrametimeMma, totalDuration);
                mMostlyTotalFrametimeMma = add(mMostlyTotalFrametimeMma, uiDuration + rtDuration);
            }
            mPreviousFrameTotal = totalDuration;
            mUpdateHandler.obtainMessage(R.id.jitter_mma,
                    String.format("Jitter: %.3fms", toMs(mJitterMma))).sendToTarget();
            mUpdateHandler.obtainMessage(R.id.totalish_mma,
                    String.format("CPU-total duration: %.3fms", toMs(mMostlyTotalFrametimeMma))).sendToTarget();
            mUpdateHandler.obtainMessage(R.id.ui_frametime_mma,
                    String.format("UI duration: %.3fms", toMs(mUiFrametimeMma))).sendToTarget();
            mUpdateHandler.obtainMessage(R.id.rt_frametime_mma,
                    String.format("RT duration: %.3fms", toMs(mRtFrametimeMma))).sendToTarget();
            mUpdateHandler.obtainMessage(R.id.total_mma,
                    String.format("Total duration: %.3fms", toMs(mTotalFrametimeMma))).sendToTarget();
        }

        double add(double previous, double today) {
            return (((WEIGHT - 1) * previous) + today) / WEIGHT;
        }

        double toMs(double val) {
            return val / 1000000;
        }
    };

    private static final class AnimatedBackgroundDrawable extends Drawable {
        private static final int FROM_COLOR = 0xFF18FFFF;
        private static final int TO_COLOR = 0xFF40C4FF;
        private static final int DURATION = 1400;

        private final Paint mPaint;
        private boolean mReverse;
        private long mStartTime;
        private int mColor;

        private boolean mReverseX;
        private boolean mReverseY;
        private float mX;
        private float mY;
        private float mRadius;
        private float mMoveStep = 10.0f;

        public AnimatedBackgroundDrawable() {
            mPaint = new Paint();
            mPaint.setColor(0xFFFFFF00);
            mPaint.setAntiAlias(true);
        }

        @Override
        public void draw(Canvas canvas) {
            stepColor();
            canvas.drawColor(mColor);

            mX += (mReverseX ? -mMoveStep : mMoveStep);
            mY += (mReverseY ? -mMoveStep : mMoveStep);
            clampXY();
            canvas.drawCircle(mX, mY, mRadius, mPaint);

            invalidateSelf();
        }

        private void clampXY() {
            if (mX <= mRadius) {
                mReverseX = false;
                mX = mRadius;
            }
            if (mY <= mRadius) {
                mReverseY = false;
                mY = mRadius;
            }
            float maxX = getBounds().width() - mRadius;
            if (mX >= maxX) {
                mReverseX = true;
                mX = maxX;
            }
            float maxY = getBounds().height() - mRadius;
            if (mY >= maxY) {
                mReverseY = true;
                mY = maxY;
            }
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            mMoveStep = Math.min(bounds.width(), bounds.height()) / 130.0f;
            mRadius = Math.min(bounds.width(), bounds.height()) / 20.0f;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }

        private void stepColor() {
            if (mStartTime == 0) {
                mStartTime = AnimationUtils.currentAnimationTimeMillis();
            }
            float frac = (AnimationUtils.currentAnimationTimeMillis() - mStartTime)
                    / (float) DURATION;
            if (frac > 1.0f) frac = 1.0f;
            int dest = mReverse ? FROM_COLOR : TO_COLOR;
            int src = mReverse ? TO_COLOR : FROM_COLOR;
            int r = (int) (Color.red(src) + (Color.red(dest) - Color.red(src)) * frac);
            int g = (int) (Color.green(src) + (Color.green(dest) - Color.green(src)) * frac);
            int b = (int) (Color.blue(src) + (Color.blue(dest) - Color.blue(src)) * frac);
            mColor = Color.rgb(r, g, b);
            if (frac == 1.0f) {
                mStartTime = 0;
                mReverse = !mReverse;
            }
        }
    }
}
