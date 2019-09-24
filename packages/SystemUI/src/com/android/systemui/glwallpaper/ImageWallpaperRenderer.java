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
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import android.util.MathUtils;
import android.util.Size;
import android.view.DisplayInfo;
import android.view.WindowManager;

import com.android.systemui.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A GL renderer for image wallpaper.
 */
public class ImageWallpaperRenderer implements GLWallpaperRenderer,
        ImageRevealHelper.RevealStateListener {
    private static final String TAG = ImageWallpaperRenderer.class.getSimpleName();
    private static final float SCALE_VIEWPORT_MIN = 1f;
    private static final float SCALE_VIEWPORT_MAX = 1.1f;
    private static final boolean DEBUG = true;

    private final WallpaperManager mWallpaperManager;
    private final ImageGLProgram mProgram;
    private final ImageGLWallpaper mWallpaper;
    private final ImageProcessHelper mImageProcessHelper;
    private final ImageRevealHelper mImageRevealHelper;

    private SurfaceProxy mProxy;
    private final Rect mScissor;
    private final Rect mSurfaceSize = new Rect();
    private final Rect mViewport = new Rect();
    private Bitmap mBitmap;
    private boolean mScissorMode;
    private float mXOffset;
    private float mYOffset;

    public ImageWallpaperRenderer(Context context, SurfaceProxy proxy) {
        mWallpaperManager = context.getSystemService(WallpaperManager.class);
        if (mWallpaperManager == null) {
            Log.w(TAG, "WallpaperManager not available");
        }

        DisplayInfo displayInfo = new DisplayInfo();
        WindowManager wm = context.getSystemService(WindowManager.class);
        wm.getDefaultDisplay().getDisplayInfo(displayInfo);

        // We only do transition in portrait currently, b/137962047.
        int orientation = context.getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            mScissor = new Rect(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);
        } else {
            mScissor = new Rect(0, 0, displayInfo.logicalHeight, displayInfo.logicalWidth);
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
        if (DEBUG) {
            Log.d(TAG, "loadBitmap: mBitmap=" + mBitmap);
        }
        if (mWallpaperManager != null && mBitmap == null) {
            mBitmap = mWallpaperManager.getBitmap();
            mWallpaperManager.forgetLoadedWallpaper();
            if (mBitmap != null) {
                float scale = (float) mScissor.height() / mBitmap.getHeight();
                int surfaceHeight = Math.max(mScissor.height(), mBitmap.getHeight());
                int surfaceWidth = scale > 1f
                        ? Math.round(mBitmap.getWidth() * scale)
                        : mBitmap.getWidth();
                mSurfaceSize.set(0, 0, surfaceWidth, surfaceHeight);
            }
        }
        if (DEBUG) {
            Log.d(TAG, "loadBitmap done");
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

        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_AOD2OPACITY), 1);
        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_PER85), threshold);
        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_REVEAL), reveal);

        glClear(GL_COLOR_BUFFER_BIT);
        // We only need to scale viewport while doing transition.
        if (mScissorMode) {
            scaleViewport(reveal);
        } else {
            glViewport(0, 0, mSurfaceSize.width(), mSurfaceSize.height());
        }
        mWallpaper.useTexture();
        mWallpaper.draw();
    }

    @Override
    public void updateAmbientMode(boolean inAmbientMode, long duration) {
        mImageRevealHelper.updateAwake(!inAmbientMode, duration);
    }

    @Override
    public void updateOffsets(float xOffset, float yOffset) {
        mXOffset = xOffset;
        mYOffset = yOffset;
        int left = (int) ((mSurfaceSize.width() - mScissor.width()) * xOffset);
        int right = left + mScissor.width();
        mScissor.set(left, mScissor.top, right, mScissor.bottom);
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
        int left = mScissor.left;
        int top = mScissor.top;
        int width = mScissor.width();
        int height = mScissor.height();
        // Interpolation between SCALE_VIEWPORT_MAX and SCALE_VIEWPORT_MIN by reveal.
        float vpScaled = MathUtils.lerp(SCALE_VIEWPORT_MIN, SCALE_VIEWPORT_MAX, reveal);
        // Calculate the offset amount from the lower left corner.
        float offset = (SCALE_VIEWPORT_MIN - vpScaled) / 2;
        // Change the viewport.
        mViewport.set((int) (left + width * offset), (int) (top + height * offset),
                (int) (width * vpScaled), (int) (height * vpScaled));
        glViewport(mViewport.left, mViewport.top, mViewport.right, mViewport.bottom);
    }

    @Override
    public void onRevealStateChanged() {
        mProxy.requestRender();
    }

    @Override
    public void onRevealStart(boolean animate) {
        if (animate) {
            mScissorMode = true;
            // Use current display area of texture.
            mWallpaper.adjustTextureCoordinates(mSurfaceSize, mScissor, mXOffset, mYOffset);
        }
        mProxy.preRender();
    }

    @Override
    public void onRevealEnd() {
        if (mScissorMode) {
            mScissorMode = false;
            // reset texture coordinates to use full texture.
            mWallpaper.adjustTextureCoordinates(null, null, 0, 0);
            // We need draw full texture back before finishing render.
            mProxy.requestRender();
        }
        mProxy.postRender();
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
        out.print(prefix); out.print("mProxy="); out.print(mProxy);
        out.print(prefix); out.print("mSurfaceSize="); out.print(mSurfaceSize);
        out.print(prefix); out.print("mScissor="); out.print(mScissor);
        out.print(prefix); out.print("mViewport="); out.print(mViewport);
        out.print(prefix); out.print("mScissorMode="); out.print(mScissorMode);
        out.print(prefix); out.print("mXOffset="); out.print(mXOffset);
        out.print(prefix); out.print("mYOffset="); out.print(mYOffset);
        out.print(prefix); out.print("threshold="); out.print(mImageProcessHelper.getThreshold());
        out.print(prefix); out.print("mReveal="); out.print(mImageRevealHelper.getReveal());
        mWallpaper.dump(prefix, fd, out, args);
    }
}
