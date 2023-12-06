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
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class BitmapsAlphaActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final BitmapsView view = new BitmapsView(this);
        final FrameLayout layout = new FrameLayout(this);
        layout.addView(view, new FrameLayout.LayoutParams(480, 800, Gravity.CENTER));
        setContentView(layout);
    }

    static class BitmapsView extends View {
        private Paint mBitmapPaint;
        private final Bitmap mBitmap1;
        private final Bitmap mBitmap2;
        private Bitmap mBitmap3;

        BitmapsView(Context c) {
            super(c);

            Log.d("OpenGLRenderer", "Loading sunset1, default options");
            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            Log.d("OpenGLRenderer", "Loading sunset2, default options");
            mBitmap2 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset2);
            Log.d("OpenGLRenderer", "Loading sunset3, forcing ARGB-8888");
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            mBitmap3 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset3, opts);
            Log.d("OpenGLRenderer", "    has bitmap alpha? " + mBitmap3.hasAlpha());

            mBitmapPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            Log.d("OpenGLRenderer", "================= Draw");

            canvas.translate(120.0f, 50.0f);
            canvas.drawBitmap(mBitmap1, 0.0f, 0.0f, mBitmapPaint);

            canvas.translate(0.0f, mBitmap1.getHeight());
            canvas.translate(0.0f, 25.0f);
            canvas.drawBitmap(mBitmap2, 0.0f, 0.0f, null);
            
            canvas.translate(0.0f, mBitmap2.getHeight());
            canvas.translate(0.0f, 25.0f);
            canvas.drawBitmap(mBitmap3, 0.0f, 0.0f, null);
        }
    }
}
