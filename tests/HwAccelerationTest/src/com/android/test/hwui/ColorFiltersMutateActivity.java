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

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class ColorFiltersMutateActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final BitmapsView view = new BitmapsView(this);
        setContentView(view);
    }

    static class BitmapsView extends View {
        private final Bitmap mBitmap1;
        private final Bitmap mBitmap2;
        private final Paint mColorMatrixPaint;
        private final Paint mLightingPaint;
        private final Paint mBlendPaint;

        private float mSaturation = 0.0f;
        private int mLightAdd = 0;
        private int mLightMul = 0;
        private int mPorterDuffColor = 0;

        BitmapsView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            mBitmap2 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset2);

            mColorMatrixPaint = new Paint();
            final ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            mColorMatrixPaint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));

            mLightingPaint = new Paint();
            mLightingPaint.setColorFilter(new LightingColorFilter(0, 0));

            mBlendPaint = new Paint();
            mBlendPaint.setColorFilter(new PorterDuffColorFilter(0, PorterDuff.Mode.SRC_OVER));

            ObjectAnimator sat = ObjectAnimator.ofFloat(this, "saturation", 1.0f);
            sat.setDuration(1000);
            sat.setRepeatCount(ObjectAnimator.INFINITE);
            sat.setRepeatMode(ObjectAnimator.REVERSE);
            sat.start();

            ObjectAnimator light = ObjectAnimator.ofInt(this, "lightAdd", 0x00101030);
            light.setEvaluator(new ArgbEvaluator());
            light.setDuration(1000);
            light.setRepeatCount(ObjectAnimator.INFINITE);
            light.setRepeatMode(ObjectAnimator.REVERSE);
            light.start();

            ObjectAnimator mult = ObjectAnimator.ofInt(this, "lightMul", 0x0060ffff);
            mult.setEvaluator(new ArgbEvaluator());
            mult.setDuration(1000);
            mult.setRepeatCount(ObjectAnimator.INFINITE);
            mult.setRepeatMode(ObjectAnimator.REVERSE);
            mult.start();

            ObjectAnimator color = ObjectAnimator.ofInt(this, "porterDuffColor", 0x7f990040);
            color.setEvaluator(new ArgbEvaluator());
            color.setDuration(1000);
            color.setRepeatCount(ObjectAnimator.INFINITE);
            color.setRepeatMode(ObjectAnimator.REVERSE);
            color.start();
        }

        public int getPorterDuffColor() {
            return mPorterDuffColor;
        }

        public void setPorterDuffColor(int porterDuffColor) {
            mPorterDuffColor = porterDuffColor;
            final PorterDuffColorFilter filter =
                    (PorterDuffColorFilter) mBlendPaint.getColorFilter();
            filter.setColor(mPorterDuffColor);
            invalidate();
        }

        public int getLightAdd() {
            return mLightAdd;
        }

        public void setLightAdd(int lightAdd) {
            mLightAdd = lightAdd;
            final LightingColorFilter filter =
                    (LightingColorFilter) mLightingPaint.getColorFilter();
            filter.setColorAdd(lightAdd);
            invalidate();
        }

        public int getLightMul() {
            return mLightAdd;
        }

        public void setLightMul(int lightMul) {
            mLightMul = lightMul;
            final LightingColorFilter filter =
                    (LightingColorFilter) mLightingPaint.getColorFilter();
            filter.setColorMultiply(lightMul);
            invalidate();
        }

        public void setSaturation(float saturation) {
            mSaturation = saturation;
            final ColorMatrixColorFilter filter =
                    (ColorMatrixColorFilter) mColorMatrixPaint.getColorFilter();
            final ColorMatrix m = new ColorMatrix();
            m.setSaturation(saturation);
            filter.setColorMatrix(m);
            invalidate();
        }

        public float getSaturation() {
            return mSaturation;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawARGB(255, 255, 255, 255);

            canvas.save();
            canvas.translate(120.0f, 50.0f);
            canvas.drawBitmap(mBitmap1, 0.0f, 0.0f, mColorMatrixPaint);

            canvas.translate(0.0f, 50.0f + mBitmap1.getHeight());
            canvas.drawBitmap(mBitmap1, 0.0f, 0.0f, mLightingPaint);

            canvas.translate(0.0f, 50.0f + mBitmap1.getHeight());
            canvas.drawBitmap(mBitmap1, 0.0f, 0.0f, mBlendPaint);
            canvas.restore();

            canvas.save();
            canvas.translate(120.0f + mBitmap1.getWidth() + 120.0f, 50.0f);
            canvas.drawBitmap(mBitmap2, 0.0f, 0.0f, mColorMatrixPaint);

            canvas.translate(0.0f, 50.0f + mBitmap2.getHeight());
            canvas.drawBitmap(mBitmap2, 0.0f, 0.0f, mLightingPaint);

            canvas.translate(0.0f, 50.0f + mBitmap2.getHeight());
            canvas.drawBitmap(mBitmap2, 0.0f, 0.0f, mBlendPaint);
            canvas.restore();
        }
    }
}
