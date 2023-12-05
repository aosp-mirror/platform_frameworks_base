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
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class BitmapsRectActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final BitmapsView view = new BitmapsView(this);
        setContentView(view);
    }

    static class BitmapsView extends View {
        private Paint mBitmapPaint;
        private final Bitmap mBitmap1;
        private final Bitmap mBitmap2;
        private final Rect mSrcRect;
        private final RectF mDstRect;
        private final RectF mDstRect2;

        BitmapsView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            mBitmap2 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset2);

            mBitmapPaint = new Paint();
            mBitmapPaint.setFilterBitmap(true);

            final float fourth = mBitmap1.getWidth() / 4.0f;
            final float half = mBitmap1.getHeight() / 2.0f;
            mSrcRect = new Rect((int) fourth, (int) (half - half / 2.0f),
                    (int) (fourth + fourth), (int) (half + half / 2.0f));
            mDstRect = new RectF(fourth, half - half / 2.0f, fourth + fourth, half + half / 2.0f);
            mDstRect2 = new RectF(fourth, half - half / 2.0f,
                    (fourth + fourth) * 3.0f, (half + half / 2.0f) * 3.0f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.translate(120.0f, 50.0f);
            canvas.drawBitmap(mBitmap1, mSrcRect, mDstRect, mBitmapPaint);

            canvas.translate(0.0f, mBitmap1.getHeight());
            canvas.translate(-100.0f, 25.0f);
            canvas.drawBitmap(mBitmap1, mSrcRect, mDstRect2, mBitmapPaint);
        }
    }
}