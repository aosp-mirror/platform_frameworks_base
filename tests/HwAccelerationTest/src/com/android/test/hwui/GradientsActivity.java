/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;

@SuppressWarnings({"UnusedDeclaration"})
public class GradientsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final FrameLayout layout = new FrameLayout(this);

        final ShadersView shadersView = new ShadersView(this);
        final GradientView gradientView = new GradientView(this);
        final RadialGradientView radialGradientView = new RadialGradientView(this);
        final SweepGradientView sweepGradientView = new SweepGradientView(this);
        final BitmapView bitmapView = new BitmapView(this);

        final SeekBar rotateView = new SeekBar(this);
        rotateView.setMax(360);
        rotateView.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStopTrackingTouch(SeekBar seekBar) {
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gradientView.setRotationY((float) progress);
                radialGradientView.setRotationX((float) progress);
                sweepGradientView.setRotationY((float) progress);
                bitmapView.setRotationX((float) progress);
            }
        });

        layout.addView(shadersView);
        layout.addView(gradientView, new FrameLayout.LayoutParams(
                200, 200, Gravity.CENTER));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(200, 200, Gravity.CENTER);
        lp.setMargins(220, 0, 0, 0);
        layout.addView(radialGradientView, lp);

        lp = new FrameLayout.LayoutParams(200, 200, Gravity.CENTER);
        lp.setMargins(440, 0, 0, 0);
        layout.addView(sweepGradientView, lp);

        lp = new FrameLayout.LayoutParams(200, 200, Gravity.CENTER);
        lp.setMargins(220, -220, 0, 0);
        layout.addView(bitmapView, lp);

        layout.addView(rotateView, new FrameLayout.LayoutParams(
                300, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));

        setContentView(layout);
    }

    static class BitmapView extends View {
        private final Paint mPaint;

        BitmapView(Context c) {
            super(c);

            Bitmap texture = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            BitmapShader shader = new BitmapShader(texture, Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT);
            mPaint = new Paint();
            mPaint.setShader(shader);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(200, 200);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), mPaint);
        }
    }

    static class GradientView extends View {
        private final Paint mPaint;

        GradientView(Context c) {
            super(c);

            LinearGradient gradient = new LinearGradient(0, 0, 200, 0, 0xFF000000, 0,
                    Shader.TileMode.CLAMP);
            mPaint = new Paint();
            mPaint.setShader(gradient);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(200, 200);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), mPaint);
        }
    }

    static class RadialGradientView extends View {
        private final Paint mPaint;

        RadialGradientView(Context c) {
            super(c);

            RadialGradient gradient = new RadialGradient(0.0f, 0.0f, 100.0f, 0xff000000, 0xffffffff,
                    Shader.TileMode.MIRROR);
            mPaint = new Paint();
            mPaint.setShader(gradient);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(200, 200);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), mPaint);
        }
    }

    static class SweepGradientView extends View {
        private final Paint mPaint;

        SweepGradientView(Context c) {
            super(c);

            SweepGradient gradient = new SweepGradient(100.0f, 100.0f, 0xff000000, 0xffffffff);
            mPaint = new Paint();
            mPaint.setShader(gradient);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(200, 200);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), mPaint);
        }
    }

    static class ShadersView extends View {
        private final Paint mPaint;
        private final float mDrawWidth;
        private final float mDrawHeight;
        private final LinearGradient mGradient;
        private final LinearGradient mGradientStops;
        private final Matrix mMatrix;

        ShadersView(Context c) {
            super(c);

            mDrawWidth = 200;
            mDrawHeight = 200;

            mGradient = new LinearGradient(0, 0, 0, 1, 0xFF000000, 0, Shader.TileMode.CLAMP);
            mGradientStops = new LinearGradient(0, 0, 0, 1,
                    new int[] { 0xFFFF0000, 0xFF00FF00, 0xFF0000FF }, null, Shader.TileMode.CLAMP);

            mMatrix = new Matrix();

            mPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(255, 255, 255);

            // Gradients
            canvas.save();
            float top = 40.0f;
            float right = 40.0f + mDrawWidth;
            float left = 40.0f;
            float bottom = 40.0f + mDrawHeight;

            mPaint.setShader(mGradient);

            mMatrix.setScale(1, mDrawWidth);
            mMatrix.postRotate(90);
            mMatrix.postTranslate(right, top);
            mGradient.setLocalMatrix(mMatrix);
            canvas.drawRect(right - mDrawWidth, top, right, top + mDrawHeight, mPaint);

            top += 40.0f + mDrawHeight;
            bottom += 40.0f + mDrawHeight;

            mMatrix.setScale(1, mDrawHeight);
            mMatrix.postTranslate(left, top);
            mGradient.setLocalMatrix(mMatrix);
            canvas.drawRect(left, top, right, top + mDrawHeight, mPaint);

            left += 40.0f + mDrawWidth;
            right += 40.0f + mDrawWidth;
            top -= 40.0f + mDrawHeight;
            bottom -= 40.0f + mDrawHeight;

            mMatrix.setScale(1, mDrawHeight);
            mMatrix.postRotate(180);
            mMatrix.postTranslate(left, bottom);
            mGradient.setLocalMatrix(mMatrix);
            canvas.drawRect(left, bottom - mDrawHeight, right, bottom, mPaint);

            top += 40.0f + mDrawHeight;
            bottom += 40.0f + mDrawHeight;

            mMatrix.setScale(1, mDrawWidth);
            mMatrix.postRotate(-90);
            mMatrix.postTranslate(left, top);
            mGradient.setLocalMatrix(mMatrix);
            canvas.drawRect(left, top, left + mDrawWidth, bottom, mPaint);

            right = left + mDrawWidth;
            left = 40.0f;
            top = bottom + 20.0f;
            bottom = top + 50.0f;

            mPaint.setShader(mGradientStops);

            mMatrix.setScale(1, mDrawWidth);
            mMatrix.postRotate(90);
            mMatrix.postTranslate(right, top);
            mGradientStops.setLocalMatrix(mMatrix);
            canvas.drawRect(left, top, right, bottom, mPaint);
            
            canvas.restore();
        }
    }
}
