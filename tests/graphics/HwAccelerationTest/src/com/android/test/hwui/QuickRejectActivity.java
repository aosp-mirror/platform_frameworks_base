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
import android.os.Bundle;
import android.util.Log;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class QuickRejectActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final QuickRejectView view = new QuickRejectView(this);
        setContentView(view);
    }

    static class QuickRejectView extends View {
        private Paint mBitmapPaint;
        private final Bitmap mBitmap1;

        QuickRejectView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);

            mBitmapPaint = new Paint();
            mBitmapPaint.setFilterBitmap(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int count = canvas.getSaveCount();
            Log.d("OpenGLRenderer", "count=" + count);
            count = canvas.save();
            Log.d("OpenGLRenderer", "count after save=" + count);
            count = canvas.getSaveCount();
            Log.d("OpenGLRenderer", "getSaveCount after save=" + count);
            canvas.restore();
            count = canvas.getSaveCount();
            Log.d("OpenGLRenderer", "count after restore=" + count);
            canvas.save();
            Log.d("OpenGLRenderer", "count after save=" + canvas.getSaveCount());
            canvas.save();
            Log.d("OpenGLRenderer", "count after save=" + canvas.getSaveCount());
            canvas.save();
            Log.d("OpenGLRenderer", "count after save=" + canvas.getSaveCount());
            canvas.restoreToCount(count);
            count = canvas.getSaveCount();
            Log.d("OpenGLRenderer", "count after restoreToCount=" + count);
            count = canvas.saveLayer(0, 0, 10, 10, mBitmapPaint, Canvas.ALL_SAVE_FLAG);
            Log.d("OpenGLRenderer", "count after saveLayer=" + count);
            count = canvas.getSaveCount();
            Log.d("OpenGLRenderer", "getSaveCount after saveLayer=" + count);
            canvas.restore();
            count = canvas.getSaveCount();
            Log.d("OpenGLRenderer", "count after restore=" + count);

            canvas.save();
            canvas.clipRect(0.0f, 0.0f, 40.0f, 40.0f);
            canvas.drawBitmap(mBitmap1, 0.0f, 0.0f, mBitmapPaint);
            canvas.drawBitmap(mBitmap1, -mBitmap1.getWidth(), 0.0f, mBitmapPaint);
            canvas.drawBitmap(mBitmap1, 50.0f, 0.0f, mBitmapPaint);
            canvas.restore();
        }
    }
}