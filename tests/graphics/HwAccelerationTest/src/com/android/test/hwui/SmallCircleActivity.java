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
import android.widget.LinearLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class SmallCircleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        View view = new PathView(this);
        layout.addView(view, new LinearLayout.LayoutParams(PathView.SIZE, PathView.SIZE));
        view = new PathView(this);
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        layout.addView(view, new LinearLayout.LayoutParams(PathView.SIZE, PathView.SIZE));

        setContentView(layout);
    }

    static class PathView extends View {
        private static final int SIZE = 37;
        private final Paint mPaint;
        private final Path mPath;

        PathView(Context c) {
            super(c);

            mPath = new Path();
            mPath.addCircle(SIZE * 0.5f, SIZE * 0.5f, SIZE * 0.275f, Path.Direction.CW);
            mPath.addCircle(SIZE * 0.5f, SIZE * 0.5f, SIZE * 0.225f, Path.Direction.CCW);
            
            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setColor(0xff00ffff);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawPath(mPath, mPaint);
        }
    }
}
