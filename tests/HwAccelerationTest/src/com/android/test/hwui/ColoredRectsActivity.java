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
public class ColoredRectsActivity extends Activity {
    private ObjectAnimator mAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xff000000));
        FrameLayout frame = new FrameLayout(this);
        final RectsView gpuView = new RectsView(this, 0, Color.GREEN);
        frame.addView(gpuView);
        final RectsView swView = new RectsView(this, 400, Color.RED);
        swView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        frame.addView(swView);
        final RectsView hwBothView = new RectsView(this, 850, Color.GREEN);
        // Don't actually need to render to a hw layer, but it's a good sanity-check that
        // we're rendering to/from layers correctly
        hwBothView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        frame.addView(hwBothView);
        final RectsView swBothView = new RectsView(this, 854, Color.RED);
        swBothView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        frame.addView(swBothView);
        setContentView(frame);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    public static class RectsView extends View {

        private float mOffset;
        private int mColor;

        public RectsView(Context c, float offset, int color) {
            super(c);
            mOffset = offset;
            mColor = color;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Paint p = new Paint();
            p.setColor(mColor);
            float yOffset = 10;

            for (int i = 0; i < 2; ++i) {
                canvas.save();
                canvas.translate(mOffset, yOffset);
                canvas.drawRect(0, 0, 20, 10, p);
                canvas.drawRect(35, 0, 45, 20, p);
                canvas.translate(0, -yOffset);
                canvas.scale(2, 2);
                canvas.translate(60, yOffset/2);
                canvas.drawRect(0, 0, 20, 10, p);
                canvas.translate(15, 0);
                canvas.drawRect(35, 0, 45, 20, p);
                canvas.restore();

                yOffset += 100;

                canvas.save();
                canvas.save();
                canvas.translate(mOffset + 10, yOffset);
                canvas.rotate(45);
                canvas.drawRect(0, 0, 20, 10, p);
                canvas.restore();
                canvas.save();
                canvas.translate(mOffset + 70, yOffset);
                canvas.rotate(5);
                canvas.drawRect(0, 0, 20, 10, p);
                canvas.restore();
                canvas.save();
                canvas.translate(mOffset + 140, yOffset);
                canvas.scale(2, 2);
                canvas.rotate(5);
                canvas.drawRect(0, 0, 20, 10, p);
                canvas.restore();
                canvas.save();
                canvas.translate(mOffset + 210, yOffset);
                canvas.scale(2, 2);
                canvas.rotate(45);
                canvas.drawRect(0, 0, 20, 10, p);
                canvas.restore();
                canvas.restore();

                yOffset += 100;

                p.setAntiAlias(true);
            }
        }
    }
}
