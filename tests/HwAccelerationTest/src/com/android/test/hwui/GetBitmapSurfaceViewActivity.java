/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;

public class GetBitmapSurfaceViewActivity extends Activity implements SurfaceHolder.Callback {
    private Camera mCamera;
    private SurfaceView mSurfaceView;

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
                            Toast.makeText(GetBitmapSurfaceViewActivity.this,
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

        content.addView(mSurfaceView, new FrameLayout.LayoutParams(500, 400, Gravity.CENTER));
        content.addView(button, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
        setContentView(content);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mCamera = Camera.open();

        try {
            mCamera.setPreviewSurface(holder.getSurface());
        } catch (IOException t) {
            android.util.Log.e("TextureView", "Cannot set preview texture target!", t);
        }

        mCamera.startPreview();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mCamera.stopPreview();
        mCamera.release();
    }
}
