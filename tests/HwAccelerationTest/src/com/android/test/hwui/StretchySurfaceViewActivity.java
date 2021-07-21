/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.test.hwui;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

public class StretchySurfaceViewActivity extends Activity implements Callback {
    SurfaceView mSurfaceView;
    ObjectAnimator mAnimator;

    class MySurfaceView extends SurfaceView {
        boolean mSlow;
        boolean mScaled;
        int mToggle = 0;

        public MySurfaceView(Context context) {
            super(context);
            setOnClickListener(v -> {
                mToggle = (mToggle + 1) % 4;
                mSlow = (mToggle & 0x2) != 0;
                mScaled = (mToggle & 0x1) != 0;

                mSurfaceView.setScaleX(mScaled ? 1.6f : 1f);
                mSurfaceView.setScaleY(mScaled ? 0.8f : 1f);

                setTitle("Slow=" + mSlow + ", scaled=" + mScaled);
                invalidate();
            });
            setWillNotDraw(false);
        }

        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);
            if (mSlow) {
                try {
                    Thread.sleep(16);
                } catch (InterruptedException e) {}
            }
        }

        public void setMyTranslationY(float ty) {
            setTranslationY(ty);
            if (mSlow) {
                invalidate();
            }
        }

        public float getMyTranslationY() {
            return getTranslationY();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout content = new FrameLayout(this) {
            {
                setWillNotDraw(false);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setColor(Color.RED);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(10f);
                canvas.drawLine(0f, 0f, getWidth(), getHeight(), paint);
                super.onDraw(canvas);

                RenderNode node = ((RecordingCanvas) canvas).mNode;
                node.stretch(0f,
                        1f, 400f, 400f);
            }
        };

        mSurfaceView = new MySurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);

        final float density = getResources().getDisplayMetrics().density;
        int size = (int) (200 * density);

        content.addView(mSurfaceView, new FrameLayout.LayoutParams(
                size, size, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        mAnimator = ObjectAnimator.ofFloat(mSurfaceView, "myTranslationY",
                0, size);
        mAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        mAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        mAnimator.setDuration(1000);
        mAnimator.setInterpolator(new LinearInterpolator());
        setContentView(content);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Canvas canvas = holder.lockCanvas();
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(10f);
        canvas.drawLine(0, 0, width, height, paint);

        holder.unlockCanvasAndPost(canvas);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAnimator.start();
    }

    @Override
    protected void onPause() {
        mAnimator.pause();
        super.onPause();
    }
}
