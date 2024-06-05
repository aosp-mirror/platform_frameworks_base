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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class LayersActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new LayersView(this));
    }

    public static class LayersView extends View {
        private Paint mLayerPaint;
        private final Paint mRectPaint;

        public LayersView(Context c) {
            super(c);

            mLayerPaint = new Paint();
            mRectPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.translate(140.0f, 100.0f);

            //canvas.drawRGB(255, 255, 255);

            int count = canvas.saveLayer(0.0f, 0.0f, 200.0f, 100.0f, mLayerPaint,
                    Canvas.ALL_SAVE_FLAG);

            mRectPaint.setColor(0xffff0000);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 100.0f, mRectPaint);

            canvas.restoreToCount(count);

            canvas.translate(0.0f, 125.0f);

            count = canvas.saveLayer(0.0f, 0.0f, 200.0f, 100.0f, mLayerPaint,
                    Canvas.ALL_SAVE_FLAG);

            mRectPaint.setColor(0xff00ff00);
            mRectPaint.setAlpha(50);
            canvas.drawRect(0.0f, 0.0f, 200.0f, 100.0f, mRectPaint);

            canvas.restoreToCount(count);

            canvas.translate(25.0f, 125.0f);

            mRectPaint.setColor(0xff0000ff);
            mRectPaint.setAlpha(255);
            canvas.drawRect(0.0f, 0.0f, 100.0f, 50.0f, mRectPaint);

            mLayerPaint.setAlpha(127);
            mLayerPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OUT));
            count = canvas.saveLayer(50.0f, 25.0f, 150.0f, 75.0f, mLayerPaint,
                    Canvas.ALL_SAVE_FLAG);

            mRectPaint.setColor(0xffff0000);
            canvas.drawRect(50.0f, 25.0f, 150.0f, 75.0f, mRectPaint);

            canvas.restoreToCount(count);

            canvas.translate(0.0f, 125.0f);

            mRectPaint.setColor(0xff0000ff);
            mRectPaint.setAlpha(255);
            canvas.drawRect(0.0f, 0.0f, 100.0f, 50.0f, mRectPaint);

            mLayerPaint.setColor(0xffff0000);
            mLayerPaint.setAlpha(127);
            mLayerPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_ATOP));
            count = canvas.saveLayer(50.0f, 25.0f, 150.0f, 75.0f, mLayerPaint,
                    Canvas.ALL_SAVE_FLAG);

            mRectPaint.setColor(0xffff0000);
            canvas.drawRect(50.0f, 25.0f, 150.0f, 75.0f, mRectPaint);

            canvas.restoreToCount(count);

            mLayerPaint = new Paint();
        }
    }
}
