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
import android.graphics.Path;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;

@SuppressWarnings({"UnusedDeclaration"})
public class TextPathActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(new CustomTextView(this));
        setContentView(scroller);
    }

    static class CustomTextView extends View {
        private final Paint mHugePaint;

        CustomTextView(Context c) {
            super(c);

            mHugePaint = new Paint();
            mHugePaint.setAntiAlias(true);
            mHugePaint.setColor(0xff000000);
            mHugePaint.setTextSize(300f);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 3000);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(255, 255, 255);

            Path path = new Path();

            canvas.translate(100.0f, 300.0f);
            drawTextAsPath(canvas, "Hello", path);

            canvas.translate(0.0f, 400.0f);
            drawTextAsPath(canvas, "OpenGL", path);
        }

        private void drawTextAsPath(Canvas canvas, String text, Path path) {
            int count = text.length();
            mHugePaint.getTextPath(text, 0, count, 0, 0, path);
            path.close();
            canvas.drawPath(path, mHugePaint);
        }
    }
}
