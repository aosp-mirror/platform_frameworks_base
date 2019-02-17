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

package com.android.systemui.glwallpaper;

import static android.opengl.GLES20.GL_COLOR_BUFFER_BIT;
import static android.opengl.GLES20.glClear;
import static android.opengl.GLES20.glClearColor;
import static android.opengl.GLES20.glUniform1f;
import static android.opengl.GLES20.glViewport;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.util.Log;

import com.android.systemui.ImageWallpaper;
import com.android.systemui.ImageWallpaper.ImageGLView;
import com.android.systemui.R;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A GL renderer for image wallpaper.
 */
public class ImageWallpaperRenderer implements GLSurfaceView.Renderer,
        ImageWallpaper.WallpaperStatusListener, ImageRevealHelper.RevealStateListener {
    private static final String TAG = ImageWallpaperRenderer.class.getSimpleName();

    private final WallpaperManager mWallpaperManager;
    private final ImageGLProgram mProgram;
    private final ImageGLWallpaper mWallpaper;
    private final ImageProcessHelper mImageProcessHelper;
    private final ImageRevealHelper mImageRevealHelper;
    private final ImageGLView mGLView;
    private float mXOffset = 0f;
    private float mYOffset = 0f;

    public ImageWallpaperRenderer(Context context, ImageGLView glView) {
        mWallpaperManager = context.getSystemService(WallpaperManager.class);
        if (mWallpaperManager == null) {
            Log.w(TAG, "WallpaperManager not available");
        }

        mProgram = new ImageGLProgram(context);
        mWallpaper = new ImageGLWallpaper(mProgram);
        mImageProcessHelper = new ImageProcessHelper();
        mImageRevealHelper = new ImageRevealHelper(this);
        mGLView = glView;

        if (mWallpaperManager != null) {
            // Compute per85 as transition threshold, this is an async work.
            mImageProcessHelper.startComputingPercentile85(mWallpaperManager.getBitmap());
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        glClearColor(0f, 0f, 0f, 1.0f);
        mProgram.useGLProgram(
                R.raw.image_wallpaper_vertex_shader, R.raw.image_wallpaper_fragment_shader);
        mWallpaper.setup();
        mWallpaper.setupTexture(mWallpaperManager.getBitmap());
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        glViewport(0, 0, width, height);
        if (Build.IS_DEBUGGABLE) {
            Log.d(TAG, "onSurfaceChanged: width=" + width + ", height=" + height
                    + ", xOffset=" + mXOffset + ", yOffset=" + mYOffset);
        }
        mWallpaper.adjustTextureCoordinates(mWallpaperManager.getBitmap(),
                width, height, mXOffset, mYOffset);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        float threshold = mImageProcessHelper.getPercentile85();
        float reveal = mImageRevealHelper.getReveal();

        glClear(GL_COLOR_BUFFER_BIT);

        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_AOD2OPACITY), 1);
        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_CENTER_REVEAL), threshold);
        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_REVEAL), reveal);

        mWallpaper.useTexture();
        mWallpaper.draw();
    }

    @Override
    public void onAmbientModeChanged(boolean inAmbientMode, long duration) {
        mImageRevealHelper.updateAwake(!inAmbientMode, duration);
        requestRender();
    }

    @Override
    public void onOffsetsChanged(float xOffset, float yOffset, Rect frame) {
        if (frame == null || mWallpaperManager == null
                || (xOffset == mXOffset && yOffset == mYOffset)) {
            return;
        }

        Bitmap bitmap = mWallpaperManager.getBitmap();
        if (bitmap == null) {
            return;
        }

        int width = frame.width();
        int height = frame.height();
        mXOffset = xOffset;
        mYOffset = yOffset;

        if (Build.IS_DEBUGGABLE) {
            Log.d(TAG, "onOffsetsChanged: width=" + width + ", height=" + height
                    + ", xOffset=" + mXOffset + ", yOffset=" + mYOffset);
        }
        mWallpaper.adjustTextureCoordinates(bitmap, width, height, mXOffset, mYOffset);
        requestRender();
    }

    @Override
    public void onRevealStateChanged() {
        requestRender();
    }

    private void requestRender() {
        if (mGLView != null) {
            mGLView.render();
        }
    }
}
