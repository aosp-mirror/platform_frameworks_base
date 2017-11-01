/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.test.uibench;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

/**
 * Test Canvas.saveLayer performance by interleaving drawText/drawRect with saveLayer.
 * This test will be used to measure if drawing interleaved layers at the beginning of a frame will
 * decrease FBO switching overhead (this is a future optimization in SkiaGL rendering pipeline).
 */
public class SaveLayerInterleaveActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new Drawable() {
            private final Paint mBluePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint mGreenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.OPAQUE;
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
            }

            @Override
            public void draw(Canvas canvas) {
                canvas.drawColor(Color.RED);

                Rect bounds = getBounds();
                int regions = 20;
                int smallRectHeight = (bounds.height()/regions);
                int padding = smallRectHeight / 4;
                int top = bounds.top;
                mBluePaint.setColor(Color.BLUE);
                mBluePaint.setTextSize(padding);
                mGreenPaint.setColor(Color.GREEN);
                mGreenPaint.setTextSize(padding);

                //interleave drawText and drawRect with saveLayer ops
                for (int i = 0; i < regions; i++, top += smallRectHeight) {
                    canvas.saveLayer(bounds.left, top, bounds.right, top + padding,
                            mBluePaint);
                    canvas.drawColor(Color.YELLOW);
                    canvas.drawText("offscreen line "+ i, bounds.left, top + padding,
                            mBluePaint);
                    canvas.restore();

                    Rect partX = new Rect(bounds.left, top + padding,
                            bounds.right,top + smallRectHeight - padding);
                    canvas.drawRect(partX, mBluePaint);
                    canvas.drawText("onscreen line "+ i, bounds.left,
                            top + smallRectHeight - padding, mGreenPaint);
                }

                invalidateSelf();
            }
        });
    }
}
