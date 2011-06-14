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
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class ClearActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final PathsView view = new PathsView(this);
        setContentView(view);
    }

    public static class PathsView extends View {
        private final Bitmap mBitmap1;
        private final Paint mClearPaint;
        private final Path mPath;

        public PathsView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset2);

            mClearPaint = new Paint();
            mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            mClearPaint.setAntiAlias(true);
            mClearPaint.setColor(0x0000ff00);
            mClearPaint.setStrokeWidth(15.0f);
            mClearPaint.setStyle(Paint.Style.FILL);
            mClearPaint.setTextSize(32.0f);

            mPath = new Path();
            mPath.moveTo(0.0f, 0.0f);
            mPath.cubicTo(0.0f, 0.0f, 100.0f, 150.0f, 100.0f, 200.0f);
            mPath.cubicTo(100.0f, 200.0f, 50.0f, 300.0f, -80.0f, 200.0f);
            mPath.cubicTo(-80.0f, 200.0f, 100.0f, 200.0f, 200.0f, 0.0f);

        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.save(); {
                canvas.drawARGB(255, 255, 255, 255);
                canvas.drawRect(100.0f, 100.0f, 200.0f, 200.0f, mClearPaint);
                canvas.drawCircle(150.0f, 400.0f, 100.0f, mClearPaint);
                canvas.drawBitmap(mBitmap1, 400.0f, 100.0f, mClearPaint);
                canvas.save(); {
                    canvas.translate(400.0f, 400.0f);
                    canvas.drawPath(mPath, mClearPaint);
                }
                canvas.restore();
                canvas.drawText("OpenGLRenderer", 50.0f, 50.0f, mClearPaint);
                mClearPaint.setColor(0xff000000);
                canvas.drawRect(800.0f, 100.0f, 900.0f, 200.0f, mClearPaint);
                mClearPaint.setColor(0x0000ff00);
            }
            canvas.restore();
        }
    }
}
