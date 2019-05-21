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
import android.util.Log;
import android.util.MathUtils;
import android.util.Size;

import com.android.systemui.R;

/**
 * A GL renderer for image wallpaper.
 */
public class ImageWallpaperRenderer implements GLWallpaperRenderer,
        ImageRevealHelper.RevealStateListener {
    private static final String TAG = ImageWallpaperRenderer.class.getSimpleName();
    private static final float SCALE_VIEWPORT_MIN = 0.98f;
    private static final float SCALE_VIEWPORT_MAX = 1f;

    private final WallpaperManager mWallpaperManager;
    private final ImageGLProgram mProgram;
    private final ImageGLWallpaper mWallpaper;
    private final ImageProcessHelper mImageProcessHelper;
    private final ImageRevealHelper mImageRevealHelper;

    private SurfaceProxy mProxy;
    private Rect mSurfaceSize;
    private Bitmap mBitmap;

    public ImageWallpaperRenderer(Context context, SurfaceProxy proxy) {
        mWallpaperManager = context.getSystemService(WallpaperManager.class);
        if (mWallpaperManager == null) {
            Log.w(TAG, "WallpaperManager not available");
        }

        mProxy = proxy;
        mProgram = new ImageGLProgram(context);
        mWallpaper = new ImageGLWallpaper(mProgram);
        mImageProcessHelper = new ImageProcessHelper();
        mImageRevealHelper = new ImageRevealHelper(this);

        if (loadBitmap()) {
            // Compute threshold of the image, this is an async work.
            mImageProcessHelper.start(mBitmap);
        }
    }

    @Override
    public void onSurfaceCreated() {
        glClearColor(0f, 0f, 0f, 1.0f);
        mProgram.useGLProgram(
                R.raw.image_wallpaper_vertex_shader, R.raw.image_wallpaper_fragment_shader);

        if (!loadBitmap()) {
            Log.w(TAG, "reload bitmap failed!");
        }

        mWallpaper.setup(mBitmap);
        mBitmap = null;
    }

    private boolean loadBitmap() {
        if (mWallpaperManager != null && mBitmap == null) {
            mBitmap = mWallpaperManager.getBitmap();
            mWallpaperManager.forgetLoadedWallpaper();
            if (mBitmap != null) {
                mSurfaceSize = new Rect(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
            }
        }
        return mBitmap != null;
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame() {
        float threshold = mImageProcessHelper.getThreshold();
        float reveal = mImageRevealHelper.getReveal();

        glClear(GL_COLOR_BUFFER_BIT);

        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_AOD2OPACITY), 1);
        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_PER85), threshold);
        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_REVEAL), reveal);

        scaleViewport(reveal);
        mWallpaper.useTexture();
        mWallpaper.draw();
    }

    @Override
    public void updateAmbientMode(boolean inAmbientMode, long duration) {
        mImageRevealHelper.updateAwake(!inAmbientMode, duration);
    }

    @Override
    public Size reportSurfaceSize() {
        return new Size(mSurfaceSize.width(), mSurfaceSize.height());
    }

    @Override
    public void finish() {
        mProxy = null;
    }

    private void scaleViewport(float reveal) {
        int width = mSurfaceSize.width();
        int height = mSurfaceSize.height();
        // Interpolation between SCALE_VIEWPORT_MAX and SCALE_VIEWPORT_MIN by reveal.
        float vpScaled = MathUtils.lerp(SCALE_VIEWPORT_MAX, SCALE_VIEWPORT_MIN, reveal);
        // Calculate the offset amount from the lower left corner.
        float offset = (SCALE_VIEWPORT_MAX - vpScaled) / 2;
        // Change the viewport.
        glViewport((int) (width * offset), (int) (height * offset),
                (int) (width * vpScaled), (int) (height * vpScaled));
    }

    @Override
    public void onRevealStateChanged() {
        mProxy.requestRender();
    }

    @Override
    public void onRevealStart() {
        mProxy.preRender();
    }

    @Override
    public void onRevealEnd() {
        mProxy.postRender();
    }
}
