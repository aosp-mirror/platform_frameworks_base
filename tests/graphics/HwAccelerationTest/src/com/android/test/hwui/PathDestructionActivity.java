/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.graphics.Path;
import android.os.Bundle;
import android.util.MathUtils;
import android.view.View;

import java.util.Random;

/**
 * The point of this test is to ensure that we can cause many paths to be created, drawn,
 * and destroyed without causing hangs or crashes. This tests the native reference counting
 * scheme in particular, because we should be able to have the Java-level path finalized
 * without destroying the underlying native path object until we are done referencing it
 * in pending DisplayLists.
 */
public class PathDestructionActivity extends Activity {

    private static final int MIN_SIZE = 20;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MyView view = new MyView(this);
        setContentView(view);
    }

    private static class MyView extends View {
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint fillAndStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private MyView(Context context) {
            super(context);
            strokePaint.setStyle(Paint.Style.STROKE);
            fillPaint.setStyle(Paint.Style.FILL);
            fillAndStrokePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        }

        private Path getRandomPath() {
            float left, top, right, bottom;
            Random r = new Random();
            left = r.nextFloat() * (getWidth() - MIN_SIZE);
            top = r.nextFloat() * (getHeight() - MIN_SIZE);
            right = left + r.nextFloat() * (getWidth() - left);
            bottom = top + r.nextFloat() * (getHeight() - top);
            Path path = new Path();
            path.moveTo(left, top);
            path.lineTo(right, top);
            path.lineTo(right, bottom);
            path.lineTo(left, bottom);
            path.close();
            return path;
        }

        private int getRandomColor() {
            Random r = new Random();
            int red = r.nextInt(255);
            int green = r.nextInt(255);
            int blue = r.nextInt(255);
            return 0xff000000 | red << 16 | green << 8 | blue;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Path path;
            for (int i = 0; i < 15; ++i) {
                path = getRandomPath();
                strokePaint.setColor(getRandomColor());
                canvas.drawPath(path, strokePaint);
                path = null;
                path = getRandomPath();
                fillPaint.setColor(getRandomColor());
                canvas.drawPath(path, fillPaint);
                path = null;
                path = getRandomPath();
                fillAndStrokePaint.setColor(getRandomColor());
                canvas.drawPath(path, fillAndStrokePaint);
                path = null;
            }

            invalidate();
        }
    }
}
