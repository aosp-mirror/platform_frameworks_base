/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.test.uibench;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.test.uibench.opengl.ImageFlipRenderThread;

public class GlTextureViewActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private ImageFlipRenderThread mRenderThread;
    private TextureView mTextureView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);
        setContentView(mTextureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mRenderThread = new ImageFlipRenderThread(getResources(), surface);
        mRenderThread.start();

        DisplayMetrics metrics = mTextureView.getContext().getResources().getDisplayMetrics();
        int distance = Math.max(mTextureView.getWidth(), mTextureView.getHeight());
        mTextureView.setCameraDistance(distance * metrics.density);

        ObjectAnimator animator = ObjectAnimator.ofFloat(mTextureView, "rotationY", 0.0f, 360.0f);
        animator.setRepeatMode(ObjectAnimator.REVERSE);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.setDuration(4000);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mTextureView.invalidate();
            }
        });
        animator.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mRenderThread.finish();
        try {
            mRenderThread.join();
        } catch (InterruptedException e) {
            Log.e(ImageFlipRenderThread.LOG_TAG, "Could not wait for render thread");
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

}