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
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class ScaledPathsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final PathsView view = new PathsView(this);
        setContentView(view);
    }

    public static class PathsView extends View {
        private final Paint mPathPaint;
        private final Path mPath;
        private final RectF mPathBounds = new RectF();

        public PathsView(Context c) {
            super(c);

            mPathPaint = new Paint();
            mPathPaint.setAntiAlias(true);
            mPathPaint.setColor(0xff0000ff);
            mPathPaint.setStrokeWidth(5.0f);
            mPathPaint.setStyle(Paint.Style.FILL);

            mPath = new Path();
            mPath.moveTo(0.0f, 0.0f);
            mPath.cubicTo(0.0f, 0.0f, 100.0f, 150.0f, 100.0f, 200.0f);
            mPath.cubicTo(100.0f, 200.0f, 50.0f, 300.0f, -80.0f, 200.0f);
            mPath.cubicTo(-80.0f, 200.0f, 100.0f, 200.0f, 200.0f, 0.0f);

            mPath.computeBounds(mPathBounds, true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawARGB(255, 255, 255, 255);

            mPathPaint.setColor(0xff0000ff);
            mPathPaint.setStyle(Paint.Style.FILL);

            canvas.save();
            drawPath(canvas, 1.0f, 1.0f);
            drawPath(canvas, 2.0f, 2.0f);
            drawPath(canvas, 4.0f, 4.0f);
            canvas.restore();

            mPathPaint.setColor(0xffff0000);
            mPathPaint.setStyle(Paint.Style.STROKE);

            canvas.save();
            drawPath(canvas, 1.0f, 1.0f);
            drawPath(canvas, 2.0f, 2.0f);
            drawPath(canvas, 4.0f, 4.0f);
            canvas.restore();
        }

        private void drawPath(Canvas canvas, float scaleX, float scaleY) {
            canvas.save();
            canvas.scale(scaleX, scaleY);
            canvas.drawPath(mPath, mPathPaint);
            canvas.restore();
            canvas.translate(mPathBounds.width() * scaleX, 0.0f);
        }
    }
}
