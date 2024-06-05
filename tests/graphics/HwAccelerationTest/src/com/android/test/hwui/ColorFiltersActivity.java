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
public class ColorFiltersActivity extends Activity {
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

        BitmapsView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            mBitmap2 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset2);

            mColorMatrixPaint = new Paint();
            final ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            mColorMatrixPaint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));

            mLightingPaint = new Paint();
            mLightingPaint.setColorFilter(new LightingColorFilter(0x0060ffff, 0x00101030));

            mBlendPaint = new Paint();
            mBlendPaint.setColorFilter(new PorterDuffColorFilter(0x7f990040,
                    PorterDuff.Mode.SRC_OVER));
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
