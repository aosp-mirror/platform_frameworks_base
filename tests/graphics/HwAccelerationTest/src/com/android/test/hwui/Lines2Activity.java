/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class Lines2Activity extends Activity {
    private ObjectAnimator mAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xff000000));
        FrameLayout frame = new FrameLayout(this);
        final LinesView gpuView = new LinesView(this, 0, Color.GREEN);
        frame.addView(gpuView);
        final LinesView swView = new LinesView(this, 400, Color.RED);
        swView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        frame.addView(swView);
        final LinesView hwBothView = new LinesView(this, 850, Color.GREEN);
        // Don't actually need to render to a hw layer, but it's a good check that
        // we're rendering to/from layers correctly
        hwBothView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        frame.addView(hwBothView);
        final LinesView swBothView = new LinesView(this, 854, Color.RED);
        swBothView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        frame.addView(swBothView);
        setContentView(frame);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    public static class LinesView extends View {

        private float mOffset;
        private int mColor;
        private float[] basePoints = {
                120, 0, 120, 20, 120, 20, 125, 0, 130, 0, 132, 10
        };
        private float[] copyPoints = new float[12];

        public LinesView(Context c, float offset, int color) {
            super(c);
            mOffset = offset;
            mColor = color;
        }

        private void drawLines(Canvas canvas, Paint p, float xOffset, float yOffset) {
            canvas.drawLine(10 + xOffset, yOffset, 10 + xOffset, 40 + yOffset, p);
            canvas.drawLine(30 + xOffset, yOffset, 40 + xOffset, 40 + yOffset, p);
            canvas.drawLine(40 + xOffset, yOffset, 75 + xOffset, 35 + yOffset, p);
            canvas.drawLine(50 + xOffset, 5+ yOffset, 100 + xOffset, 15 + yOffset, p);
            canvas.drawLine(60 + xOffset, yOffset, 110 + xOffset, 2 + yOffset, p);
            canvas.drawLine(60 + xOffset, 40 + yOffset, 110 + xOffset, 40 + yOffset, p);
            for (int i = 0; i < 12; i += 2) {
                copyPoints[i] = basePoints[i] + xOffset;
                copyPoints[i+1] = basePoints[i+1] + yOffset;
            }
            canvas.drawLines(copyPoints, 0, 12, p);
        }

        private void drawVerticalLine(Canvas canvas, Paint p, float length, float x, float y) {
            canvas.drawLine(x, y, x, y + length, p);
        }

        private void drawDiagonalLine(Canvas canvas, Paint p, float length, float x, float y) {
            canvas.drawLine(x, y, x + length, y + length, p);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Paint p = new Paint();
            p.setColor(mColor);
            float yOffset = 10;

            canvas.save();
            drawLines(canvas, p, mOffset, yOffset);
            canvas.scale(2, 2);
            canvas.translate(60, 0);
            drawLines(canvas, p, mOffset/2, yOffset/2);
            canvas.restore();

            yOffset +=100;
            canvas.save();
            p.setStrokeWidth(1);
            drawLines(canvas, p, mOffset, yOffset);
            canvas.scale(2, 2);
            canvas.translate(60, 0);
            drawLines(canvas, p, mOffset/2, yOffset/2);
            canvas.restore();

            yOffset += 100;
            canvas.save();
            p.setStrokeWidth(2);
            drawLines(canvas, p, mOffset, yOffset);
            canvas.scale(2, 2);
            canvas.translate(60, 0);
            drawLines(canvas, p, mOffset/2, yOffset/2);
            canvas.restore();

            p.setAntiAlias(true);
            p.setStrokeWidth(0);
            yOffset += 100;
            canvas.save();
            drawLines(canvas, p, mOffset, yOffset);
            canvas.scale(2, 2);
            canvas.translate(60, 0);
            drawLines(canvas, p, mOffset/2, yOffset/2);
            canvas.restore();

            yOffset += 100;
            canvas.save();
            p.setStrokeWidth(1);
            drawLines(canvas, p, mOffset, yOffset);
            canvas.scale(2, 2);
            canvas.translate(60, 0);
            drawLines(canvas, p, mOffset/2, yOffset/2);
            canvas.restore();

            yOffset += 100;
            canvas.save();
            p.setStrokeWidth(2);
            drawLines(canvas, p, mOffset, yOffset);
            canvas.scale(2, 2);
            canvas.translate(60, 0);
            drawLines(canvas, p, mOffset/2, yOffset/2);
            canvas.restore();

            yOffset += 100;
            canvas.save();
            p.setStrokeWidth(1);
            float x = 10 + mOffset;
            for (float length = 1; length <= 10; length +=1 ) {
                p.setAntiAlias(false);
                drawVerticalLine(canvas, p, length, x, yOffset);
                x += 5;
                p.setAntiAlias(true);
                drawVerticalLine(canvas, p, length, x, yOffset);
                x += 5;
            }
            p.setStrokeWidth(5);
            for (float length = 1; length <= 10; length +=1 ) {
                p.setAntiAlias(false);
                drawVerticalLine(canvas, p, length, x, yOffset);
                x += 10;
                p.setAntiAlias(true);
                drawVerticalLine(canvas, p, length, x, yOffset);
                x += 10;
            }
            canvas.restore();

            yOffset += 20;
            canvas.save();
            p.setStrokeWidth(1);
            x = 10 + mOffset;
            for (float length = 1; length <= 10; length +=1 ) {
                p.setAntiAlias(false);
                drawDiagonalLine(canvas, p, length, x, yOffset);
                x += 5;
                p.setAntiAlias(true);
                drawDiagonalLine(canvas, p, length, x, yOffset);
                x += 5;
            }
            p.setStrokeWidth(2);
            for (float length = 1; length <= 10; length +=1 ) {
                p.setAntiAlias(false);
                drawDiagonalLine(canvas, p, length, x, yOffset);
                x += 10;
                p.setAntiAlias(true);
                drawDiagonalLine(canvas, p, length, x, yOffset);
                x += 10;
            }
            canvas.restore();

            yOffset += 20;
            canvas.save();
            p.setStrokeWidth(1);
            x = 10 + mOffset;
            for (float length = 1; length <= 10; length +=1 ) {
                p.setAntiAlias(false);
                canvas.drawLine(x, yOffset, x + 1, yOffset + length, p);
                x += 5;
                p.setAntiAlias(true);
                canvas.drawLine(x, yOffset, x + 1, yOffset + length, p);
                x += 5;
            }
            p.setStrokeWidth(2);
            for (float length = 1; length <= 10; length +=1 ) {
                p.setAntiAlias(false);
                canvas.drawLine(x, yOffset, x + 1, yOffset + length, p);
                x += 10;
                p.setAntiAlias(true);
                canvas.drawLine(x, yOffset, x + 1, yOffset + length, p);
                x += 10;
            }
            canvas.restore();

            yOffset += 20;
            canvas.save();
            p.setStrokeWidth(1);
            x = 10 + mOffset;
            for (float length = 1; length <= 10; length +=1 ) {
                p.setAntiAlias(false);
                canvas.drawLine(x, yOffset, x + length, yOffset + 1, p);
                x += 5;
                p.setAntiAlias(true);
                canvas.drawLine(x, yOffset, x + length, yOffset + 1, p);
                x += 5;
            }
            p.setStrokeWidth(2);
            for (float length = 1; length <= 10; length +=1 ) {
                p.setAntiAlias(false);
                canvas.drawLine(x, yOffset, x + length, yOffset + 1, p);
                x += 10;
                p.setAntiAlias(true);
                canvas.drawLine(x, yOffset, x + length, yOffset + 1, p);
                x += 10;
            }
            canvas.restore();

        }
    }
}
