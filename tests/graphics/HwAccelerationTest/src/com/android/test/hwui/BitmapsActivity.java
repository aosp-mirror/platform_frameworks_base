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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class BitmapsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final BitmapsView view = new BitmapsView(this);
        final FrameLayout layout = new FrameLayout(this);
        layout.addView(view, new FrameLayout.LayoutParams(480, 800, Gravity.CENTER));
        setContentView(layout);
        
        ScaleAnimation a = new ScaleAnimation(1.0f, 2.0f, 1.0f, 2.0f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF,0.5f);
        a.setDuration(2000);
        a.setRepeatCount(Animation.INFINITE);
        a.setRepeatMode(Animation.REVERSE);
        view.startAnimation(a);
    }

    static class BitmapsView extends View {
        private Paint mBitmapPaint;
        private final Bitmap mBitmap1;
        private final Bitmap mBitmap2;
        private final PorterDuffXfermode mDstIn;

        BitmapsView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            mBitmap2 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset2);
            
            Log.d("Bitmap", "mBitmap1.isMutable() = " + mBitmap1.isMutable());
            Log.d("Bitmap", "mBitmap2.isMutable() = " + mBitmap2.isMutable());

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inMutable = true;
            Bitmap bitmap = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1, opts);
            Log.d("Bitmap", "bitmap.isMutable() = " + bitmap.isMutable());
            
            mBitmapPaint = new Paint();
            mDstIn = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.translate(120.0f, 50.0f);
            canvas.drawBitmap(mBitmap1, 0.0f, 0.0f, mBitmapPaint);

            canvas.translate(0.0f, mBitmap1.getHeight());
            canvas.translate(0.0f, 25.0f);
            canvas.drawBitmap(mBitmap2, 0.0f, 0.0f, null);
            
            mBitmapPaint.setAlpha(127);
            canvas.translate(0.0f, mBitmap2.getHeight());
            canvas.translate(0.0f, 25.0f);
            canvas.drawBitmap(mBitmap1, 0.0f, 0.0f, mBitmapPaint);
            
            mBitmapPaint.setAlpha(255);
            canvas.translate(0.0f, mBitmap1.getHeight());
            canvas.translate(0.0f, 25.0f);
            mBitmapPaint.setColor(0xffff0000);
            canvas.drawRect(0.0f, 0.0f, mBitmap2.getWidth(), mBitmap2.getHeight(), mBitmapPaint);
            mBitmapPaint.setXfermode(mDstIn);
            canvas.drawBitmap(mBitmap2, 0.0f, 0.0f, mBitmapPaint);

            mBitmapPaint.reset();
        }
    }
}
