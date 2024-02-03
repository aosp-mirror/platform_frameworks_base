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

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;

public class CustomRenderer extends Activity {
    private RenderNode mRootNode = new RenderNode("CustomRenderer");
    private RenderNode mChildNode = new RenderNode("RedBox");
    private HardwareRenderer mRenderer = new HardwareRenderer();
    private ObjectAnimator mAnimator;
    private Handler mRedrawHandler = new Handler(true);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().takeSurface(mSurfaceCallbacks);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mAnimator = ObjectAnimator.ofFloat(mChildNode, "translationY", 0, 300);
        mAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        mAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        final Runnable redraw = this::draw;
        mAnimator.addUpdateListener(animation -> {
            mRedrawHandler.post(redraw);
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        mAnimator.end();
        mAnimator = null;
    }

    private void setupRoot(int width, int height) {
        mRootNode.setPosition(0, 0, width, height);

        RecordingCanvas canvas = mRootNode.beginRecording();
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.CENTER);
        float textSize = Math.min(width, height) * .05f;
        paint.setTextSize(textSize);
        canvas.drawText("Hello custom renderer!", width / 2, textSize * 2, paint);

        canvas.translate(0, height / 4);
        canvas.drawRenderNode(mChildNode);
        canvas.translate(width / 2, 0);
        canvas.drawRenderNode(mChildNode);
        mRootNode.endRecording();

        setupChild(width / 2, height / 2);
    }

    private void setupChild(int width, int height) {
        mChildNode.setPosition(0, 0, width, height);
        mChildNode.setScaleX(.5f);
        mChildNode.setScaleY(.5f);

        RecordingCanvas canvas = mChildNode.beginRecording();
        canvas.drawColor(Color.RED);
        mChildNode.endRecording();
    }

    private void draw() {
        // Since we are constantly pumping frames between onStart & onStop we don't really
        // care about any errors that may happen. They will self-correct.
        mRenderer.createRenderRequest()
                .setVsyncTime(System.nanoTime())
                .syncAndDraw();
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
            setupRoot(width, height);

            mRenderer.setContentRoot(mRootNode);
            mRenderer.setSurface(holder.getSurface());
            draw();
            if (!mAnimator.isStarted()) {
                mAnimator.start();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            mRenderer.destroy();
        }
    };
}
