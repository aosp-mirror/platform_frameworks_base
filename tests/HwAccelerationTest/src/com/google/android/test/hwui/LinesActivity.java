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

package com.google.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class LinesActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final LinesView view = new LinesView(this);
        setContentView(view);
    }

    static class LinesView extends View {
        private final Bitmap mBitmap1;
        private final Paint mSmallPaint;
        private final Paint mMediumPaint;
        private final Paint mLargePaint;
        private final BitmapShader mShader;
        private final float[] mPoints;
        private final Paint mAlphaPaint;

        LinesView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);

            mSmallPaint = new Paint();
            mSmallPaint.setAntiAlias(true);
            mSmallPaint.setColor(0xffff0000);
            mSmallPaint.setStrokeWidth(1.0f);

            mMediumPaint = new Paint();
            mMediumPaint.setAntiAlias(true);
            mMediumPaint.setColor(0xff0000ff);
            mMediumPaint.setStrokeWidth(4.0f);

            mLargePaint = new Paint();
            mLargePaint.setAntiAlias(true);
            mLargePaint.setColor(0xff00ff00);
            mLargePaint.setStrokeWidth(15.0f);

            mAlphaPaint = new Paint();
            mAlphaPaint.setAntiAlias(true);
            mAlphaPaint.setColor(0x7fff0050);
            mAlphaPaint.setStrokeWidth(10.0f);
            
            mShader = new BitmapShader(mBitmap1, BitmapShader.TileMode.MIRROR,
                    BitmapShader.TileMode.MIRROR);

            mPoints = new float[] {
                    62.0f, 0.0f, 302.0f, 400.0f,
                    302.0f, 400.0f, 352.0f, 400.0f,
                    352.0f, 400.0f, 352.0f, 500.0f
            };
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawARGB(255, 255, 255, 255);

            canvas.save();
            canvas.translate(100.0f, 20.0f);

            canvas.drawLine(0.0f, 0.0f, 40.0f, 400.0f, mSmallPaint);
            canvas.drawLine(5.0f, 0.0f, 95.0f, 400.0f, mMediumPaint);
            canvas.drawLine(22.0f, 0.0f, 162.0f, 400.0f, mLargePaint);
            
            mLargePaint.setShader(mShader);
            canvas.drawLine(42.0f, 0.0f, 222.0f, 400.0f, mLargePaint);
            mLargePaint.setShader(null);
            
            canvas.drawLines(mPoints, mAlphaPaint);
            
            canvas.translate(120.0f, 0.0f);
            mAlphaPaint.setShader(mShader);            
            canvas.drawLines(mPoints, mAlphaPaint);
            mAlphaPaint.setShader(null);

            canvas.restore();
        }
    }
}
