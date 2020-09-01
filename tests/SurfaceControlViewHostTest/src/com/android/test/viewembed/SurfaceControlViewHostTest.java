/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.test.viewembed;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.SurfaceControlViewHost;
import android.widget.Button;
import android.widget.FrameLayout;


public class SurfaceControlViewHostTest extends Activity implements SurfaceHolder.Callback{
    SurfaceView mView;
    SurfaceControlViewHost mVr;

    protected void onCreate(Bundle savedInstanceState) {
        FrameLayout content = new FrameLayout(this);
        super.onCreate(savedInstanceState);
        mView = new SurfaceView(this);
        content.addView(mView, new FrameLayout.LayoutParams(
                500, 500, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        setContentView(content);

        mView.setZOrderOnTop(true);
        mView.getHolder().addCallback(this);

        addEmbeddedView();
    }

    void addEmbeddedView() {
        mVr = new SurfaceControlViewHost(this, this.getDisplay(),
                mView.getHostToken());

        mView.setChildSurfacePackage(mVr.getSurfacePackage());

        Button v = new Button(this);
        v.setBackgroundColor(Color.BLUE);
        v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    v.setBackgroundColor(Color.RED);
                }
        });
        WindowManager.LayoutParams lp =
            new WindowManager.LayoutParams(500, 500, WindowManager.LayoutParams.TYPE_APPLICATION,
                    0, PixelFormat.OPAQUE);
        mVr.setView(v, lp);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Canvas canvas = holder.lockCanvas();
        canvas.drawColor(Color.GREEN);
        holder.unlockCanvasAndPost(canvas);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }
}
