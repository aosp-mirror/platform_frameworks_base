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

package com.android.test.hwui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;

public class CustomRenderer extends Activity {
    private RenderNode mContent = new RenderNode("CustomRenderer");
    private HardwareRenderer mRenderer = new HardwareRenderer();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().takeSurface(mSurfaceCallbacks);
    }

    private SurfaceHolder.Callback2 mSurfaceCallbacks = new SurfaceHolder.Callback2() {

        @Override
        public void surfaceRedrawNeeded(SurfaceHolder holder) {
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            mContent.setLeftTopRightBottom(0, 0, width, height);
            RecordingCanvas canvas = mContent.beginRecording();
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.min(width, height) * .05f);
            canvas.drawText("Hello custom renderer!", width / 2, height / 2, paint);
            mContent.endRecording();

            mRenderer.setContentRoot(mContent);
            mRenderer.setSurface(holder.getSurface());
            mRenderer.createRenderRequest()
                    .setVsyncTime(System.nanoTime())
                    .setFrameCommitCallback(Runnable::run, () -> {
                        Log.d("CustomRenderer", "Frame committed!");
                    })
                    .syncAndDraw();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            mRenderer.destroy();
        }
    };
}
