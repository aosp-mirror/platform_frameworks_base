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

import static android.opengl.GLES20.*;

import static javax.microedition.khronos.egl.EGL10.*;

import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region.Op;
import android.opengl.GLUtils;
import android.os.AsyncTask;
import android.os.SystemProperties;
import android.os.Trace;
import android.renderscript.Matrix4f;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Default built-in wallpaper that simply shows a static image.
 */
@SuppressWarnings({"UnusedDeclaration"})
public class ImageWallpaper extends WallpaperService {
    private static final String TAG = "ImageWallpaper";
    private static final String GL_LOG_TAG = "ImageWallpaperGL";
    private static final boolean DEBUG = false;
    private static final String PROPERTY_KERNEL_QEMU = "ro.kernel.qemu";

    static final boolean FIXED_SIZED_SURFACE = true;
    static final boolean USE_OPENGL = true;

    WallpaperManager mWallpaperManager;

    DrawableEngine mEngine;

    boolean mIsHwAccelerated;

    @Override
    public void onCreate() {
        super.onCreate();
        mWallpaperManager = (WallpaperManager) getSystemService(WALLPAPER_SERVICE);

        //noinspection PointlessBooleanExpression,ConstantConditions
        if (FIXED_SIZED_SURFACE && USE_OPENGL) {
            mIsHwAccelerated = ActivityManager.isHighEndGfx();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        if (mEngine != null) {
            mEngine.trimMemory(level);
        }
    }

    @Override
    public Engine onCreateEngine() {
        mEngine = new DrawableEngine();
        return mEngine;
    }

    class DrawableEngine extends Engine {
        static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        static final int EGL_OPENGL_ES2_BIT = 4;

        Bitmap mBackground;
        int mBackgroundWidth = -1, mBackgroundHeight = -1;
        int mLastSurfaceWidth = -1, mLastSurfaceHeight = -1;
        int mLastRotation = -1;
        float mXOffset = 0.5f;
        float mYOffset = 0.5f;
        float mScale = 1f;

        private Display mDefaultDisplay;
        private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();

        boolean mVisible = true;
        boolean mOffsetsChanged;
        int mLastXTranslation;
        int mLastYTranslation;

        private EGL10 mEgl;
        private EGLDisplay mEglDisplay;
        private EGLConfig mEglConfig;
        private EGLContext mEglContext;
        private EGLSurface mEglSurface;

        private static final String sSimpleVS =
                "attribute vec4 position;\n" +
                "attribute vec2 texCoords;\n" +
                "varying vec2 outTexCoords;\n" +
                "uniform mat4 projection;\n" +
                "\nvoid main(void) {\n" +
                "    outTexCoords = texCoords;\n" +
                "    gl_Position = projection * position;\n" +
                "}\n\n";
        private static final String sSimpleFS =
                "precision mediump float;\n\n" +
                "varying vec2 outTexCoords;\n" +
                "uniform sampler2D texture;\n" +
                "\nvoid main(void) {\n" +
                "    gl_FragColor = texture2D(texture, outTexCoords);\n" +
                "}\n\n";

        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
        private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
        private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;

        private int mRotationAtLastSurfaceSizeUpdate = -1;
        private int mDisplayWidthAtLastSurfaceSizeUpdate = -1;
        private int mDisplayHeightAtLastSurfaceSizeUpdate = -1;

        private int mLastRequestedWidth = -1;
        private int mLastRequestedHeight = -1;
        private AsyncTask<Void, Void, Bitmap> mLoader;
        private boolean mNeedsDrawAfterLoadingWallpaper;
        private boolean mSurfaceValid;

        public DrawableEngine() {
            super();
            setFixedSizeAllowed(true);
        }

        public void trimMemory(int level) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                    && level <= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                    && mBackground != null) {
                if (DEBUG) {
                    Log.d(TAG, "trimMemory");
                }
                unloadWallpaper(true /* forgetSize */);
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            if (DEBUG) {
                Log.d(TAG, "onCreate");
            }

            super.onCreate(surfaceHolder);

            mDefaultDisplay = getSystemService(WindowManager.class).getDefaultDisplay();
            setOffsetNotificationsEnabled(false);

            updateSurfaceSize(surfaceHolder, getDefaultDisplayInfo(), false /* forDraw */);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            mBackground = null;
            unloadWallpaper(true /* forgetSize */);
        }

        boolean updateSurfaceSize(SurfaceHolder surfaceHolder, DisplayInfo displayInfo,
                boolean forDraw) {
            boolean hasWallpaper = true;

            // Load background image dimensions, if we haven't saved them yet
            if (mBackgroundWidth <= 0 || mBackgroundHeight <= 0) {
                // Need to load the image to get dimensions
                loadWallpaper(forDraw, false /* needsReset */);
                if (DEBUG) {
                    Log.d(TAG, "Reloading, redoing updateSurfaceSize later.");
                }
                hasWallpaper = false;
            }

            // Force the wallpaper to cover the screen in both dimensions
            int surfaceWidth = Math.max(displayInfo.logicalWidth, mBackgroundWidth);
            int surfaceHeight = Math.max(displayInfo.logicalHeight, mBackgroundHeight);

            if (FIXED_SIZED_SURFACE) {
                // Used a fixed size surface, because we are special.  We can do
                // this because we know the current design of window animations doesn't
                // cause this to break.
                surfaceHolder.setFixedSize(surfaceWidth, surfaceHeight);
                mLastRequestedWidth = surfaceWidth;
                mLastRequestedHeight = surfaceHeight;
            } else {
                surfaceHolder.setSizeFromLayout();
            }
            return hasWallpaper;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (DEBUG) {
                Log.d(TAG, "onVisibilityChanged: mVisible, visible=" + mVisible + ", " + visible);
            }

            if (mVisible != visible) {
                if (DEBUG) {
                    Log.d(TAG, "Visibility changed to visible=" + visible);
                }
                mVisible = visible;
                if (visible) {
                    drawFrame();
                }
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                float xOffsetStep, float yOffsetStep,
                int xPixels, int yPixels) {
            if (DEBUG) {
                Log.d(TAG, "onOffsetsChanged: xOffset=" + xOffset + ", yOffset=" + yOffset
                        + ", xOffsetStep=" + xOffsetStep + ", yOffsetStep=" + yOffsetStep
                        + ", xPixels=" + xPixels + ", yPixels=" + yPixels);
            }

            if (mXOffset != xOffset || mYOffset != yOffset) {
                if (DEBUG) {
                    Log.d(TAG, "Offsets changed to (" + xOffset + "," + yOffset + ").");
                }
                mXOffset = xOffset;
                mYOffset = yOffset;
                mOffsetsChanged = true;
            }
            drawFrame();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceChanged: width=" + width + ", height=" + height);
            }

            super.onSurfaceChanged(holder, format, width, height);

            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            if (DEBUG) {
                Log.i(TAG, "onSurfaceDestroyed");
            }

            mLastSurfaceWidth = mLastSurfaceHeight = -1;
            mSurfaceValid = false;
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            if (DEBUG) {
                Log.i(TAG, "onSurfaceCreated");
            }

            mLastSurfaceWidth = mLastSurfaceHeight = -1;
            mSurfaceValid = true;
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceRedrawNeeded");
            }
            super.onSurfaceRedrawNeeded(holder);
            drawFrame();
        }

        private DisplayInfo getDefaultDisplayInfo() {
            mDefaultDisplay.getDisplayInfo(mTmpDisplayInfo);
            return mTmpDisplayInfo;
        }

        void drawFrame() {
            if (!mSurfaceValid) {
                return;
            }
            try {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW, "drawWallpaper");
                DisplayInfo displayInfo = getDefaultDisplayInfo();
                int newRotation = displayInfo.rotation;

                // Sometimes a wallpaper is not large enough to cover the screen in one dimension.
                // Call updateSurfaceSize -- it will only actually do the update if the dimensions
                // should change
                if (newRotation != mLastRotation) {
                    // Update surface size (if necessary)
                    if (!updateSurfaceSize(getSurfaceHolder(), displayInfo, true /* forDraw */)) {
                        return; // had to reload wallpaper, will retry later
                    }
                    mRotationAtLastSurfaceSizeUpdate = newRotation;
                    mDisplayWidthAtLastSurfaceSizeUpdate = displayInfo.logicalWidth;
                    mDisplayHeightAtLastSurfaceSizeUpdate = displayInfo.logicalHeight;
                }
                SurfaceHolder sh = getSurfaceHolder();
                final Rect frame = sh.getSurfaceFrame();
                final int dw = frame.width();
                final int dh = frame.height();
                boolean surfaceDimensionsChanged = dw != mLastSurfaceWidth
                        || dh != mLastSurfaceHeight;

                boolean redrawNeeded = surfaceDimensionsChanged || newRotation != mLastRotation;
                if (!redrawNeeded && !mOffsetsChanged) {
                    if (DEBUG) {
                        Log.d(TAG, "Suppressed drawFrame since redraw is not needed "
                                + "and offsets have not changed.");
                    }
                    return;
                }
                mLastRotation = newRotation;

                // Load bitmap if it is not yet loaded
                if (mBackground == null) {
                    if (DEBUG) {
                        Log.d(TAG, "Reloading bitmap: mBackground, bgw, bgh, dw, dh = " +
                                mBackground + ", " +
                                ((mBackground == null) ? 0 : mBackground.getWidth()) + ", " +
                                ((mBackground == null) ? 0 : mBackground.getHeight()) + ", " +
                                dw + ", " + dh);
                    }
                    loadWallpaper(true /* needDraw */, true /* needReset */);
                    if (DEBUG) {
                        Log.d(TAG, "Reloading, resuming draw later");
                    }
                    return;
                }

                // Center the scaled image
                mScale = Math.max(1f, Math.max(dw / (float) mBackground.getWidth(),
                        dh / (float) mBackground.getHeight()));
                final int availw = dw - (int) (mBackground.getWidth() * mScale);
                final int availh = dh - (int) (mBackground.getHeight() * mScale);
                int xPixels = availw / 2;
                int yPixels = availh / 2;

                // Adjust the image for xOffset/yOffset values. If window manager is handling offsets,
                // mXOffset and mYOffset are set to 0.5f by default and therefore xPixels and yPixels
                // will remain unchanged
                final int availwUnscaled = dw - mBackground.getWidth();
                final int availhUnscaled = dh - mBackground.getHeight();
                if (availwUnscaled < 0)
                    xPixels += (int) (availwUnscaled * (mXOffset - .5f) + .5f);
                if (availhUnscaled < 0)
                    yPixels += (int) (availhUnscaled * (mYOffset - .5f) + .5f);

                mOffsetsChanged = false;
                if (surfaceDimensionsChanged) {
                    mLastSurfaceWidth = dw;
                    mLastSurfaceHeight = dh;
                }
                if (!redrawNeeded && xPixels == mLastXTranslation && yPixels == mLastYTranslation) {
                    if (DEBUG) {
                        Log.d(TAG, "Suppressed drawFrame since the image has not "
                                + "actually moved an integral number of pixels.");
                    }
                    return;
                }
                mLastXTranslation = xPixels;
                mLastYTranslation = yPixels;

                if (DEBUG) {
                    Log.d(TAG, "Redrawing wallpaper");
                }

                if (mIsHwAccelerated) {
                    if (!drawWallpaperWithOpenGL(sh, availw, availh, xPixels, yPixels)) {
                        drawWallpaperWithCanvas(sh, availw, availh, xPixels, yPixels);
                    }
                } else {
                    drawWallpaperWithCanvas(sh, availw, availh, xPixels, yPixels);
                    if (FIXED_SIZED_SURFACE) {
                        // If the surface is fixed-size, we should only need to
                        // draw it once and then we'll let the window manager
                        // position it appropriately.  As such, we no longer needed
                        // the loaded bitmap.  Yay!
                        // hw-accelerated renderer retains bitmap for faster rotation
                        unloadWallpaper(false /* forgetSize */);
                    }
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
        }

        /**
         * Loads the wallpaper on background thread and schedules updating the surface frame,
         * and if {@param needsDraw} is set also draws a frame.
         *
         * If loading is already in-flight, subsequent loads are ignored (but needDraw is or-ed to
         * the active request).
         *
         * If {@param needsReset} is set also clears the cache in WallpaperManager first.
         */
        private void loadWallpaper(boolean needsDraw, boolean needsReset) {
            mNeedsDrawAfterLoadingWallpaper |= needsDraw;
            if (mLoader != null) {
                if (needsReset) {
                    mLoader.cancel(false /* interrupt */);
                    mLoader = null;
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Skipping loadWallpaper, already in flight ");
                    }
                    return;
                }
            }
            mLoader = new AsyncTask<Void, Void, Bitmap>() {
                @Override
                protected Bitmap doInBackground(Void... params) {
                    Throwable exception;
                    try {
                        if (needsReset) {
                            mWallpaperManager.forgetLoadedWallpaper();
                        }
                        return mWallpaperManager.getBitmap();
                    } catch (RuntimeException | OutOfMemoryError e) {
                        exception = e;
                    }

                    if (isCancelled()) {
                        return null;
                    }

                    if (exception != null) {
                        // Note that if we do fail at this, and the default wallpaper can't
                        // be loaded, we will go into a cycle.  Don't do a build where the
                        // default wallpaper can't be loaded.
                        Log.w(TAG, "Unable to load wallpaper!", exception);
                        try {
                            mWallpaperManager.clear();
                        } catch (IOException ex) {
                            // now we're really screwed.
                            Log.w(TAG, "Unable reset to default wallpaper!", ex);
                        }

                        if (isCancelled()) {
                            return null;
                        }

                        try {
                            return mWallpaperManager.getBitmap();
                        } catch (RuntimeException | OutOfMemoryError e) {
                            Log.w(TAG, "Unable to load default wallpaper!", e);
                        }
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Bitmap b) {
                    mBackground = null;
                    mBackgroundWidth = -1;
                    mBackgroundHeight = -1;

                    if (b != null) {
                        mBackground = b;
                        mBackgroundWidth = mBackground.getWidth();
                        mBackgroundHeight = mBackground.getHeight();
                    }

                    if (DEBUG) {
                        Log.d(TAG, "Wallpaper loaded: " + mBackground);
                    }
                    updateSurfaceSize(getSurfaceHolder(), getDefaultDisplayInfo(),
                            false /* forDraw */);
                    if (mNeedsDrawAfterLoadingWallpaper) {
                        drawFrame();
                    }

                    mLoader = null;
                    mNeedsDrawAfterLoadingWallpaper = false;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        private void unloadWallpaper(boolean forgetSize) {
            if (mLoader != null) {
                mLoader.cancel(false);
                mLoader = null;
            }
            mBackground = null;
            if (forgetSize) {
                mBackgroundWidth = -1;
                mBackgroundHeight = -1;
            }

            mLoader = new AsyncTask<Void, Void, Bitmap>() {
                @Override
                protected Bitmap doInBackground(Void... params) {
                    mWallpaperManager.forgetLoadedWallpaper();
                    return null;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        @Override
        protected void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
            super.dump(prefix, fd, out, args);

            out.print(prefix); out.println("ImageWallpaper.DrawableEngine:");
            out.print(prefix); out.print(" mBackground="); out.print(mBackground);
            out.print(" mBackgroundWidth="); out.print(mBackgroundWidth);
            out.print(" mBackgroundHeight="); out.println(mBackgroundHeight);

            out.print(prefix); out.print(" mLastRotation="); out.print(mLastRotation);
            out.print(" mLastSurfaceWidth="); out.print(mLastSurfaceWidth);
            out.print(" mLastSurfaceHeight="); out.println(mLastSurfaceHeight);

            out.print(prefix); out.print(" mXOffset="); out.print(mXOffset);
            out.print(" mYOffset="); out.println(mYOffset);

            out.print(prefix); out.print(" mVisible="); out.print(mVisible);
            out.print(" mOffsetsChanged="); out.println(mOffsetsChanged);

            out.print(prefix); out.print(" mLastXTranslation="); out.print(mLastXTranslation);
            out.print(" mLastYTranslation="); out.print(mLastYTranslation);
            out.print(" mScale="); out.println(mScale);

            out.print(prefix); out.print(" mLastRequestedWidth="); out.print(mLastRequestedWidth);
            out.print(" mLastRequestedHeight="); out.println(mLastRequestedHeight);

            out.print(prefix); out.println(" DisplayInfo at last updateSurfaceSize:");
            out.print(prefix);
            out.print("  rotation="); out.print(mRotationAtLastSurfaceSizeUpdate);
            out.print("  width="); out.print(mDisplayWidthAtLastSurfaceSizeUpdate);
            out.print("  height="); out.println(mDisplayHeightAtLastSurfaceSizeUpdate);
        }

        private void drawWallpaperWithCanvas(SurfaceHolder sh, int w, int h, int left, int top) {
            Canvas c = sh.lockCanvas();
            if (c != null) {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "Redrawing: left=" + left + ", top=" + top);
                    }

                    final float right = left + mBackground.getWidth() * mScale;
                    final float bottom = top + mBackground.getHeight() * mScale;
                    if (w < 0 || h < 0) {
                        c.save(Canvas.CLIP_SAVE_FLAG);
                        c.clipRect(left, top, right, bottom,
                                Op.DIFFERENCE);
                        c.drawColor(0xff000000);
                        c.restore();
                    }
                    if (mBackground != null) {
                        RectF dest = new RectF(left, top, right, bottom);
                        // add a filter bitmap?
                        c.drawBitmap(mBackground, null, dest, null);
                    }
                } finally {
                    sh.unlockCanvasAndPost(c);
                }
            }
        }

        private boolean drawWallpaperWithOpenGL(SurfaceHolder sh, int w, int h, int left, int top) {
            if (!initGL(sh)) return false;

            final float right = left + mBackground.getWidth() * mScale;
            final float bottom = top + mBackground.getHeight() * mScale;

            final Rect frame = sh.getSurfaceFrame();
            final Matrix4f ortho = new Matrix4f();
            ortho.loadOrtho(0.0f, frame.width(), frame.height(), 0.0f, -1.0f, 1.0f);

            final FloatBuffer triangleVertices = createMesh(left, top, right, bottom);

            final int texture = loadTexture(mBackground);
            final int program = buildProgram(sSimpleVS, sSimpleFS);

            final int attribPosition = glGetAttribLocation(program, "position");
            final int attribTexCoords = glGetAttribLocation(program, "texCoords");
            final int uniformTexture = glGetUniformLocation(program, "texture");
            final int uniformProjection = glGetUniformLocation(program, "projection");

            checkGlError();

            glViewport(0, 0, frame.width(), frame.height());
            glBindTexture(GL_TEXTURE_2D, texture);

            glUseProgram(program);
            glEnableVertexAttribArray(attribPosition);
            glEnableVertexAttribArray(attribTexCoords);
            glUniform1i(uniformTexture, 0);
            glUniformMatrix4fv(uniformProjection, 1, false, ortho.getArray(), 0);

            checkGlError();

            if (w > 0 || h > 0) {
                glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
                glClear(GL_COLOR_BUFFER_BIT);
            }

            // drawQuad
            triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            glVertexAttribPointer(attribPosition, 3, GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices);

            triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            glVertexAttribPointer(attribTexCoords, 3, GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices);

            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

            boolean status = mEgl.eglSwapBuffers(mEglDisplay, mEglSurface);
            checkEglError();

            finishGL(texture, program);

            return status;
        }

        private FloatBuffer createMesh(int left, int top, float right, float bottom) {
            final float[] verticesData = {
                    // X, Y, Z, U, V
                     left,  bottom, 0.0f, 0.0f, 1.0f,
                     right, bottom, 0.0f, 1.0f, 1.0f,
                     left,  top,    0.0f, 0.0f, 0.0f,
                     right, top,    0.0f, 1.0f, 0.0f,
            };

            final int bytes = verticesData.length * FLOAT_SIZE_BYTES;
            final FloatBuffer triangleVertices = ByteBuffer.allocateDirect(bytes).order(
                    ByteOrder.nativeOrder()).asFloatBuffer();
            triangleVertices.put(verticesData).position(0);
            return triangleVertices;
        }

        private int loadTexture(Bitmap bitmap) {
            int[] textures = new int[1];

            glActiveTexture(GL_TEXTURE0);
            glGenTextures(1, textures, 0);
            checkGlError();

            int texture = textures[0];
            glBindTexture(GL_TEXTURE_2D, texture);
            checkGlError();

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            GLUtils.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bitmap, GL_UNSIGNED_BYTE, 0);
            checkGlError();

            return texture;
        }

        private int buildProgram(String vertex, String fragment) {
            int vertexShader = buildShader(vertex, GL_VERTEX_SHADER);
            if (vertexShader == 0) return 0;

            int fragmentShader = buildShader(fragment, GL_FRAGMENT_SHADER);
            if (fragmentShader == 0) return 0;

            int program = glCreateProgram();
            glAttachShader(program, vertexShader);
            glAttachShader(program, fragmentShader);
            glLinkProgram(program);
            checkGlError();

            glDeleteShader(vertexShader);
            glDeleteShader(fragmentShader);

            int[] status = new int[1];
            glGetProgramiv(program, GL_LINK_STATUS, status, 0);
            if (status[0] != GL_TRUE) {
                String error = glGetProgramInfoLog(program);
                Log.d(GL_LOG_TAG, "Error while linking program:\n" + error);
                glDeleteProgram(program);
                return 0;
            }

            return program;
        }

        private int buildShader(String source, int type) {
            int shader = glCreateShader(type);

            glShaderSource(shader, source);
            checkGlError();

            glCompileShader(shader);
            checkGlError();

            int[] status = new int[1];
            glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
            if (status[0] != GL_TRUE) {
                String error = glGetShaderInfoLog(shader);
                Log.d(GL_LOG_TAG, "Error while compiling shader:\n" + error);
                glDeleteShader(shader);
                return 0;
            }

            return shader;
        }

        private void checkEglError() {
            int error = mEgl.eglGetError();
            if (error != EGL_SUCCESS) {
                Log.w(GL_LOG_TAG, "EGL error = " + GLUtils.getEGLErrorString(error));
            }
        }

        private void checkGlError() {
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                Log.w(GL_LOG_TAG, "GL error = 0x" + Integer.toHexString(error), new Throwable());
            }
        }

        private void finishGL(int texture, int program) {
            int[] textures = new int[1];
            textures[0] = texture;
            glDeleteTextures(1, textures, 0);
            glDeleteProgram(program);
            mEgl.eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
            mEgl.eglDestroyContext(mEglDisplay, mEglContext);
            mEgl.eglTerminate(mEglDisplay);
        }

        private boolean initGL(SurfaceHolder surfaceHolder) {
            mEgl = (EGL10) EGLContext.getEGL();

            mEglDisplay = mEgl.eglGetDisplay(EGL_DEFAULT_DISPLAY);
            if (mEglDisplay == EGL_NO_DISPLAY) {
                throw new RuntimeException("eglGetDisplay failed " +
                        GLUtils.getEGLErrorString(mEgl.eglGetError()));
            }

            int[] version = new int[2];
            if (!mEgl.eglInitialize(mEglDisplay, version)) {
                throw new RuntimeException("eglInitialize failed " +
                        GLUtils.getEGLErrorString(mEgl.eglGetError()));
            }

            mEglConfig = chooseEglConfig();
            if (mEglConfig == null) {
                throw new RuntimeException("eglConfig not initialized");
            }

            mEglContext = createContext(mEgl, mEglDisplay, mEglConfig);
            if (mEglContext == EGL_NO_CONTEXT) {
                throw new RuntimeException("createContext failed " +
                        GLUtils.getEGLErrorString(mEgl.eglGetError()));
            }

            int attribs[] = {
                EGL_WIDTH, 1,
                EGL_HEIGHT, 1,
                EGL_NONE
            };
            EGLSurface tmpSurface = mEgl.eglCreatePbufferSurface(mEglDisplay, mEglConfig, attribs);
            mEgl.eglMakeCurrent(mEglDisplay, tmpSurface, tmpSurface, mEglContext);

            int[] maxSize = new int[1];
            Rect frame = surfaceHolder.getSurfaceFrame();
            glGetIntegerv(GL_MAX_TEXTURE_SIZE, maxSize, 0);

            mEgl.eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            mEgl.eglDestroySurface(mEglDisplay, tmpSurface);

            if(frame.width() > maxSize[0] || frame.height() > maxSize[0]) {
                mEgl.eglDestroyContext(mEglDisplay, mEglContext);
                mEgl.eglTerminate(mEglDisplay);
                Log.e(GL_LOG_TAG, "requested  texture size " +
                    frame.width() + "x" + frame.height() + " exceeds the support maximum of " +
                    maxSize[0] + "x" + maxSize[0]);
                return false;
            }

            mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay, mEglConfig, surfaceHolder, null);
            if (mEglSurface == null || mEglSurface == EGL_NO_SURFACE) {
                int error = mEgl.eglGetError();
                if (error == EGL_BAD_NATIVE_WINDOW || error == EGL_BAD_ALLOC) {
                    Log.e(GL_LOG_TAG, "createWindowSurface returned " +
                                         GLUtils.getEGLErrorString(error) + ".");
                    return false;
                }
                throw new RuntimeException("createWindowSurface failed " +
                        GLUtils.getEGLErrorString(error));
            }

            if (!mEgl.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                throw new RuntimeException("eglMakeCurrent failed " +
                        GLUtils.getEGLErrorString(mEgl.eglGetError()));
            }

            return true;
        }


        EGLContext createContext(EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig) {
            int[] attrib_list = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
            return egl.eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT, attrib_list);
        }

        private EGLConfig chooseEglConfig() {
            int[] configsCount = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            int[] configSpec = getConfig();
            if (!mEgl.eglChooseConfig(mEglDisplay, configSpec, configs, 1, configsCount)) {
                throw new IllegalArgumentException("eglChooseConfig failed " +
                        GLUtils.getEGLErrorString(mEgl.eglGetError()));
            } else if (configsCount[0] > 0) {
                return configs[0];
            }
            return null;
        }

        private int[] getConfig() {
            return new int[] {
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_ALPHA_SIZE, 0,
                    EGL_DEPTH_SIZE, 0,
                    EGL_STENCIL_SIZE, 0,
                    EGL_CONFIG_CAVEAT, EGL_NONE,
                    EGL_NONE
            };
        }
    }
}
