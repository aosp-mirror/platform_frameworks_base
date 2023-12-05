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
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class PathOpsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final PathsView view = new PathsView(this);
        setContentView(view);
    }

    public static class PathsView extends View {
        private final Paint mPaint;
        private Path[] mPaths;
        private float mSize;


        public PathsView(Context c) {
            super(c);

            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(Color.RED);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);

            Path.Op[] ops = Path.Op.values();
            mPaths = new Path[ops.length];

            mSize = w / (ops.length * 2.0f);

            Path p1 = new Path();
            p1.addRect(0.0f, 0.0f, mSize, mSize, Path.Direction.CW);

            Path p2 = new Path();
            p2.addCircle(mSize, mSize, mSize / 2.0f, Path.Direction.CW);

            for (int i = 0; i < ops.length; i++) {
                mPaths[i] = new Path();
                if (!mPaths[i].op(p1, p2, ops[i])) {
                    Log.d("PathOps", ops[i].name() + " failed!");
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.translate(mSize * 0.2f, getHeight() / 2.0f);
            for (Path path : mPaths) {
                canvas.drawPath(path, mPaint);
                canvas.translate(mSize * 1.8f, 0.0f);
            }
        }
    }
}
