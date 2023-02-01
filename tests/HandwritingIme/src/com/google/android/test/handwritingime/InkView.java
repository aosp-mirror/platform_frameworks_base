/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.test.handwritingime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

class InkView extends View {
    private static final long FINISH_TIMEOUT = 2500;
    private final HandwritingIme.HandwritingFinisher mHwCanceller;
    private final HandwritingIme.StylusConsumer mConsumer;
    private final int mTopInset;
    private Paint mPaint;
    private Path  mPath;
    private float mX, mY;
    private static final float STYLUS_MOVE_TOLERANCE = 1;
    private Runnable mFinishRunnable;

    InkView(Context context, HandwritingIme.HandwritingFinisher hwController,
            HandwritingIme.StylusConsumer consumer) {
        super(context);
        mHwCanceller = hwController;
        mConsumer = consumer;

        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setDither(true);
        mPaint.setColor(Color.GREEN);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeWidth(14);

        mPath = new Path();

        WindowManager wm = context.getSystemService(WindowManager.class);
        WindowMetrics metrics =  wm.getCurrentWindowMetrics();
        Insets insets = metrics.getWindowInsets()
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
        setLayoutParams(new ViewGroup.LayoutParams(
                metrics.getBounds().width() - insets.left - insets.right,
                metrics.getBounds().height() - insets.top - insets.bottom));
        mTopInset = insets.top;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawPath(mPath, mPaint);
        canvas.drawARGB(20, 255, 50, 50);
    }

    private void stylusStart(float x, float y) {
        y = y - mTopInset;
        mPath.moveTo(x, y);
        mX = x;
        mY = y;
    }

    private void stylusMove(float x, float y) {
        y = y - mTopInset;
        float dx = Math.abs(x - mX);
        float dy = Math.abs(y - mY);
        if (mPath.isEmpty()) {
            stylusStart(x, y);
        }
        if (dx >= STYLUS_MOVE_TOLERANCE || dy >= STYLUS_MOVE_TOLERANCE) {
            mPath.quadTo(mX, mY, (x + mX) / 2, (y + mY) / 2);
            mX = x;
            mY = y;
        }
    }

    private void stylusFinish() {
        mPath.lineTo(mX, mY);
        // TODO: support offscreen? e.g. mCanvas.drawPath(mPath, mPaint);
        mPath.reset();
        mX = 0;
        mY = 0;

    }


    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            mConsumer.onStylusEvent(event);
            android.util.Log.w(HandwritingIme.TAG, "INK touch onStylusEvent " + event);
            float x = event.getX();
            float y = event.getY();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    cancelTimer();
                    stylusStart(x, y);
                    invalidate();
                    break;
                case MotionEvent.ACTION_MOVE:
                    stylusMove(x, y);
                    invalidate();
                    break;

                case MotionEvent.ACTION_UP:
                    scheduleTimer();
                    break;

            }
            return true;
        }
        return false;
    }

    private void cancelTimer() {
        if (mFinishRunnable != null) {
            if (getHandler() != null) {
                getHandler().removeCallbacks(mFinishRunnable);
            }
            mFinishRunnable = null;
        }
        if (getHandler() != null) {
            getHandler().removeCallbacksAndMessages(null);
        }
    }

    private void scheduleTimer() {
        cancelTimer();
        if (getHandler() != null) {
            postDelayed(getFinishRunnable(), FINISH_TIMEOUT);
        }
    }

    private Runnable getFinishRunnable() {
        mFinishRunnable = () -> {
            android.util.Log.e(HandwritingIme.TAG, "Hw view timer finishHandwriting ");
            mHwCanceller.finish();
            stylusFinish();
            mPath.reset();
            invalidate();
        };

        return mFinishRunnable;
    }

}
