/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.systemui;

import android.content.Context;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import com.android.systemui.glwallpaper.ImageWallpaperRenderer;

/**
 * Default built-in wallpaper that simply shows a static image.
 */
@SuppressWarnings({"UnusedDeclaration"})
public class ImageWallpaper extends WallpaperService {
    private static final String TAG = ImageWallpaper.class.getSimpleName();

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public Engine onCreateEngine() {
        if (Build.IS_DEBUGGABLE) {
            Log.v(TAG, "We are using GLEngine");
        }
        return new GLEngine(this);
    }

    class GLEngine extends Engine {
        private GLWallpaperSurfaceView mWallpaperSurfaceView;

        GLEngine(Context context) {
            mWallpaperSurfaceView = new GLWallpaperSurfaceView(context);
            mWallpaperSurfaceView.setRenderer(
                    new ImageWallpaperRenderer(context, mWallpaperSurfaceView));
            mWallpaperSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            setOffsetNotificationsEnabled(true);
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode, long animationDuration) {
            if (mWallpaperSurfaceView != null) {
                mWallpaperSurfaceView.notifyAmbientModeChanged(inAmbientMode, animationDuration);
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep,
                float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            if (mWallpaperSurfaceView != null) {
                mWallpaperSurfaceView.notifyOffsetsChanged(xOffset, yOffset);
            }
        }

        private class GLWallpaperSurfaceView extends GLSurfaceView implements ImageGLView {
            private WallpaperStatusListener mWallpaperChangedListener;

            GLWallpaperSurfaceView(Context context) {
                super(context);
                setEGLContextClientVersion(2);
            }

            @Override
            public SurfaceHolder getHolder() {
                return getSurfaceHolder();
            }

            @Override
            public void setRenderer(Renderer renderer) {
                super.setRenderer(renderer);
                mWallpaperChangedListener = (WallpaperStatusListener) renderer;
            }

            private void notifyAmbientModeChanged(boolean inAmbient, long duration) {
                if (mWallpaperChangedListener != null) {
                    mWallpaperChangedListener.onAmbientModeChanged(inAmbient, duration);
                }
            }

            private void notifyOffsetsChanged(float xOffset, float yOffset) {
                if (mWallpaperChangedListener != null) {
                    mWallpaperChangedListener.onOffsetsChanged(
                            xOffset, yOffset, getHolder().getSurfaceFrame());
                }
            }

            @Override
            public void render() {
                requestRender();
            }
        }
    }

    /**
     * A listener to trace status of image wallpaper.
     */
    public interface WallpaperStatusListener {

        /**
         * Called back while ambient mode changes.
         * @param inAmbientMode true if is in ambient mode, false otherwise.
         * @param duration the duration of animation.
         */
        void onAmbientModeChanged(boolean inAmbientMode, long duration);

        /**
         * Called back while wallpaper offsets.
         * @param xOffset The offset portion along x.
         * @param yOffset The offset portion along y.
         */
        void onOffsetsChanged(float xOffset, float yOffset, Rect frame);
    }

    /**
     * An abstraction for view of GLRenderer.
     */
    public interface ImageGLView {

        /**
         * Ask the view to render.
         */
        void render();
    }
}
