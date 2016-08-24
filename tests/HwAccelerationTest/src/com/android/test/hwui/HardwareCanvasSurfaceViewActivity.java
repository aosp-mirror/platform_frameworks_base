/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Environment;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.FileOutputStream;

public class HardwareCanvasSurfaceViewActivity extends Activity implements Callback {
    private SurfaceView mSurfaceView;
    private HardwareCanvasSurfaceViewActivity.RenderingThread mThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout content = new FrameLayout(this);

        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);

        Button button = new Button(this);
        button.setText("Copy bitmap to /sdcard/surfaceview.png");
        button.setOnClickListener((View v) -> {
            final Bitmap b = Bitmap.createBitmap(
                    mSurfaceView.getWidth(), mSurfaceView.getHeight(),
                    Bitmap.Config.ARGB_8888);
            PixelCopy.request(mSurfaceView, b,
                    (int result) -> {
                        if (result != PixelCopy.SUCCESS) {
                            Toast.makeText(HardwareCanvasSurfaceViewActivity.this,
                                    "Failed to copy", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            try (FileOutputStream out = new FileOutputStream(
                                    Environment.getExternalStorageDirectory() + "/surfaceview.png");) {
                                b.compress(Bitmap.CompressFormat.PNG, 100, out);
                            }
                        } catch (Exception e) {
                            // Ignore
                        }
                    }, mSurfaceView.getHandler());
        });

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(button, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.addView(mSurfaceView, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);

        content.addView(layout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(content);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mThread = new RenderingThread(holder.getSurface());
        mThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mThread.setSize(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mThread != null) mThread.stopRendering();
    }

    private static class RenderingThread extends Thread {
        private final Surface mSurface;
        private volatile boolean mRunning = true;
        private int mWidth, mHeight;

        public RenderingThread(Surface surface) {
            mSurface = surface;
        }

        void setSize(int width, int height) {
            mWidth = width;
            mHeight = height;
        }

        @Override
        public void run() {
            float x = 0.0f;
            float y = 0.0f;
            float speedX = 5.0f;
            float speedY = 3.0f;

            Paint paint = new Paint();
            paint.setColor(0xff00ff00);

            while (mRunning && !Thread.interrupted()) {
                final Canvas canvas = mSurface.lockHardwareCanvas();
                try {
                    canvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR);
                    canvas.drawRect(x, y, x + 20.0f, y + 20.0f, paint);
                } finally {
                    mSurface.unlockCanvasAndPost(canvas);
                }

                if (x + 20.0f + speedX >= mWidth || x + speedX <= 0.0f) {
                    speedX = -speedX;
                }
                if (y + 20.0f + speedY >= mHeight || y + speedY <= 0.0f) {
                    speedY = -speedY;
                }

                x += speedX;
                y += speedY;

                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    // Interrupted
                }
            }
        }

        void stopRendering() {
            interrupt();
            mRunning = false;
        }
    }
}
