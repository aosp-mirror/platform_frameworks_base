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

package com.android.server.wm.flicker.testapp;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class GameActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceHolder mSurfaceHolder;
    private SurfaceView mSurfaceView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_surfaceview);

        mSurfaceView = (SurfaceView) findViewById(R.id.surface_view);
        mSurfaceView.setZOrderOnTop(true);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.setFormat(PixelFormat.TRANSPARENT);
        mSurfaceHolder.addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        hideSystemBars();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Canvas canvas = holder.lockCanvas();
        canvas.drawColor(Color.BLUE);
        holder.unlockCanvasAndPost(canvas);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat windowInsetsController =
                ViewCompat.getWindowInsetsController(getWindow().getDecorView());
        if (windowInsetsController == null) {
            return;
        }
        // Configure the behavior of the hidden system bars.
        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
        // Hide status bar only to avoid flakiness on gesture quick switch cases.
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars());
    }
}
