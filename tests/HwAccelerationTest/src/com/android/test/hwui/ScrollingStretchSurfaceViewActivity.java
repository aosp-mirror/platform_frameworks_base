/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class ScrollingStretchSurfaceViewActivity extends Activity implements Callback {

    SurfaceView mVerticalSurfaceView;
    SurfaceView mHorizontalSurfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.scrolling_stretch_surfaceview);

        mVerticalSurfaceView = new SurfaceView(this);
        mVerticalSurfaceView.getHolder().addCallback(this);

        mHorizontalSurfaceView = new SurfaceView(this);
        mHorizontalSurfaceView.getHolder().addCallback(this);

        FrameLayout verticalContainer = findViewById(R.id.vertical_surfaceview_container);
        verticalContainer.addView(mVerticalSurfaceView,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout horizontalContainer = findViewById(R.id.horizontal_surfaceview_container);
        horizontalContainer.addView(mHorizontalSurfaceView,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ImageView verticalImageView = findViewById(R.id.vertical_imageview);
        verticalImageView.setImageDrawable(new LineDrawable());

        ImageView horizontalImageView = findViewById(R.id.horizontal_imageview);
        horizontalImageView.setImageDrawable(new LineDrawable());
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Canvas canvas = holder.lockCanvas();

        drawLine(canvas, width, height);
        holder.unlockCanvasAndPost(canvas);
    }

    private static void drawLine(Canvas canvas, int width, int height) {
        canvas.drawColor(Color.GRAY);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(10f);
        canvas.drawLine(0, 0, width, height, paint);
    }

    private static class LineDrawable extends Drawable {
        @Override
        public void draw(Canvas canvas) {
            drawLine(canvas, getBounds().width(), getBounds().height());
        }

        @Override
        public void setAlpha(int alpha) {
            // NO-OP
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // NO-OP
        }

        @Override
        public int getOpacity() {
            return 0;
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }
}
