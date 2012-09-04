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
import android.view.MotionEvent;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class PathOffsetActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final PathsView view = new PathsView(this);
        setContentView(view);
    }

    public class PathsView extends View {
        private Path mPath;
        private Paint mPaint;

        public PathsView(Context context) {
            super(context);

            mPaint = new Paint();
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(3);

            mPath = new Path();
            mPath.lineTo(100, 100);
            mPath.lineTo(200, 300);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            mPath.offset(1, 1);
            mPaint.setColor(Color.RED);
            canvas.drawPath(mPath, mPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            invalidate();
            return super.onTouchEvent(event);
        }
    }
}
