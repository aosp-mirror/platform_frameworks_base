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
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class NewLayersActivity extends Activity {
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
            mLayerPaint.setAlpha(127);
            mRectPaint = new Paint();
            mRectPaint.setAntiAlias(true);
            mRectPaint.setTextSize(24.0f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(128, 255, 128);

            canvas.save();

            canvas.translate(140.0f, 100.0f);
            drawStuff(canvas, Canvas.ALL_SAVE_FLAG);

            canvas.translate(0.0f, 200.0f);
            drawStuff(canvas, Canvas.HAS_ALPHA_LAYER_SAVE_FLAG);

            canvas.restore();
        }

        private void drawStuff(Canvas canvas, int saveFlags) {
            int count = canvas.saveLayer(0.0f, 0.0f, 200.0f, 100.0f, mLayerPaint, saveFlags);

            mRectPaint.setColor(0x7fff0000);
            canvas.drawRect(-20.0f, -20.0f, 220.0f, 120.0f, mRectPaint);

            mRectPaint.setColor(0xff000000);
            canvas.drawText("This is a very long string to overlap between layers and framebuffer",
                    -100.0f, 50.0f, mRectPaint);

            canvas.restoreToCount(count);
        }
    }
}
