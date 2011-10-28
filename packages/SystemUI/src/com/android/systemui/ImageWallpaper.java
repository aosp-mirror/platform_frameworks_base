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

import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Region.Op;
import android.opengl.GLUtils;
import android.os.SystemProperties;
import android.renderscript.Matrix4f;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static android.opengl.GLES20.*;
import static javax.microedition.khronos.egl.EGL10.*;

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

    boolean mIsHwAccelerated;

    @Override
    public void onCreate() {
        super.onCreate();
        mWallpaperManager = (WallpaperManager) getSystemService(WALLPAPER_SERVICE);

        //noinspection PointlessBooleanExpression,ConstantConditions
        if (FIXED_SIZED_SURFACE && USE_OPENGL) {
            if (!isEmulator()) {
                WindowManager windowManager =
                        (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                Display display = windowManager.getDefaultDisplay();
                mIsHwAccelerated = ActivityManager.isHighEndGfx(display);
            }
        }
    }

    private static boolean isEmulator() {
        return "1".equals(SystemProperties.get(PROPERTY_KERNEL_QEMU, "0"));
    }

    public Engine onCreateEngine() {
        return new DrawableEngine();
    }

    class DrawableEngine extends Engine {
        static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        static final int EGL_OPENGL_ES2_BIT = 4;

        private final Object mLock = new Object[0];

        // TODO: Not currently used, keeping around until we know we don't need it
        @SuppressWarnings({"UnusedDeclaration"})
        private WallpaperObserver mReceiver;

        Bitmap mBackground;
        int mBackgroundWidth = -1, mBackgroundHeight = -1;
        float mXOffset;
        float mYOffset;

        boolean mVisible = true;
        boolean mRedrawNeeded;
        boolean mOffsetsChanged;
        int mLastXTranslation;
        int mLastYTranslation;

        private EGL10 mEgl;
        private EGLDisplay mEglDisplay;
        private EGLConfig mEglConfig;
        private EGLContext mEglContext;
        private EGLSurface mEglSurface;
        private GL mGL;

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

        class WallpaperObserver extends BroadcastReceiver {
            public void onReceive(Context context, Intent intent) {
                if (DEBUG) {
                    Log.d(TAG, "onReceive");
                }

                synchronized (mLock) {
                    mBackgroundWidth = mBackgroundHeight = -1;
                    mBackground = null;
                    mRedrawNeeded = true;
                    drawFrameLocked();
                }
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            if (DEBUG) {
                Log.d(TAG, "onCreate");
            }

            super.onCreate(surfaceHolder);
            
            // TODO: Don't need this currently because the wallpaper service
            // will restart the image wallpaper whenever the image changes.
            //IntentFilter filter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
            //mReceiver = new WallpaperObserver();
            //registerReceiver(mReceiver, filter, null, mHandler);

            updateSurfaceSize(surfaceHolder);

            setOffsetNotificationsEnabled(false);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (mReceiver != null) {
                unregisterReceiver(mReceiver);
            }
        }

        @Override
        public void onDesiredSizeChanged(int desiredWidth, int desiredHeight) {
            super.onDesiredSizeChanged(desiredWidth, desiredHeight);
            SurfaceHolder surfaceHolder = getSurfaceHolder();
            if (surfaceHolder != null) {
                updateSurfaceSize(surfaceHolder);
            }
        }

        void updateSurfaceSize(SurfaceHolder surfaceHolder) {
            if (FIXED_SIZED_SURFACE) {
                // Used a fixed size surface, because we are special.  We can do
                // this because we know the current design of window animations doesn't
                // cause this to break.
                surfaceHolder.setFixedSize(getDesiredMinimumWidth(), getDesiredMinimumHeight());
            } else {
                surfaceHolder.setSizeFromLayout();
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (DEBUG) {
                Log.d(TAG, "onVisibilityChanged: visible=" + visible);
            }

            synchronized (mLock) {
                if (mVisible != visible) {
                    if (DEBUG) {
                        Log.d(TAG, "Visibility changed to visible=" + visible);
                    }
                    mVisible = visible;
                    drawFrameLocked();
                }
            }
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
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

            synchronized (mLock) {
                if (mXOffset != xOffset || mYOffset != yOffset) {
                    if (DEBUG) {
                        Log.d(TAG, "Offsets changed to (" + xOffset + "," + yOffset + ").");
                    }
                    mXOffset = xOffset;
                    mYOffset = yOffset;
                    mOffsetsChanged = true;
                }
                drawFrameLocked();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceChanged: width=" + width + ", height=" + height);
            }

            super.onSurfaceChanged(holder, format, width, height);

            synchronized (mLock) {
                mRedrawNeeded = true;
                drawFrameLocked();
            }
        }

        void drawFrameLocked() {
            if (!mVisible) {
                if (DEBUG) {
                    Log.d(TAG, "Suppressed drawFrame since wallpaper is not visible.");
                }
                return;
            }
            if (!mRedrawNeeded && !mOffsetsChanged) {
                if (DEBUG) {
                    Log.d(TAG, "Suppressed drawFrame since redraw is not needed "
                            + "and offsets have not changed.");
                }
                return;
            }

            if (mBackgroundWidth < 0 || mBackgroundHeight < 0) {
                // If we don't yet know the size of the wallpaper bitmap,
                // we need to get it now.
                updateWallpaperLocked();
            }

            SurfaceHolder sh = getSurfaceHolder();
            final Rect frame = sh.getSurfaceFrame();
            final int dw = frame.width();
            final int dh = frame.height();
            final int availw = dw - mBackgroundWidth;
            final int availh = dh - mBackgroundHeight;
            int xPixels = availw < 0 ? (int)(availw * mXOffset + .5f) : (availw / 2);
            int yPixels = availh < 0 ? (int)(availh * mYOffset + .5f) : (availh / 2);

            mOffsetsChanged = false;
            if (!mRedrawNeeded && xPixels == mLastXTranslation && yPixels == mLastYTranslation) {
                if (DEBUG) {
                    Log.d(TAG, "Suppressed drawFrame since the image has not "
                            + "actually moved an integral number of pixels.");
                }
                return;
            }
            mRedrawNeeded = false;
            mLastXTranslation = xPixels;
            mLastYTranslation = yPixels;

            if (mBackground == null) {
                // If we somehow got to this point after we have last flushed
                // the wallpaper, well we really need it to draw again.  So
                // seems like we need to reload it.  Ouch.
                updateWallpaperLocked();
            }

            if (mIsHwAccelerated) {
                if (!drawWallpaperWithOpenGL(sh, availw, availh, xPixels, yPixels)) {
                    drawWallpaperWithCanvas(sh, availw, availh, xPixels, yPixels);
                }
            } else {
                drawWallpaperWithCanvas(sh, availw, availh, xPixels, yPixels);
            }

            if (FIXED_SIZED_SURFACE) {
                // If the surface is fixed-size, we should only need to
                // draw it once and then we'll let the window manager
                // position it appropriately.  As such, we no longer needed
                // the loaded bitmap.  Yay!
                mBackground = null;
                mWallpaperManager.forgetLoadedWallpaper();
            }
        }

        void updateWallpaperLocked() {
            Throwable exception = null;
            try {
                mBackground = mWallpaperManager.getBitmap();
            } catch (RuntimeException e) {
                exception = e;
            } catch (OutOfMemoryError e) {
                exception = e;
            }

            if (exception != null) {
                mBackground = null;
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
            }

            mBackgroundWidth = mBackground != null ? mBackground.getWidth() : 0;
            mBackgroundHeight = mBackground != null ? mBackground.getHeight() : 0;
        }

        private void drawWallpaperWithCanvas(SurfaceHolder sh, int w, int h, int x, int y) {
            Canvas c = sh.lockCanvas();
            if (c != null) {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "Redrawing: x=" + x + ", y=" + y);
                    }

                    c.translate(x, y);
                    if (w < 0 || h < 0) {
                        c.save(Canvas.CLIP_SAVE_FLAG);
                        c.clipRect(0, 0, mBackgroundWidth, mBackgroundHeight, Op.DIFFERENCE);
                        c.drawColor(0xff000000);
                        c.restore();
                    }
                    if (mBackground != null) {
                        c.drawBitmap(mBackground, 0, 0, null);
                    }
                } finally {
                    sh.unlockCanvasAndPost(c);
                }
            }
        }

        private boolean drawWallpaperWithOpenGL(SurfaceHolder sh, int w, int h, int left, int top) {
            if (!initGL(sh)) return false;

            final float right = left + mBackgroundWidth;
            final float bottom = top + mBackgroundHeight;

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

            if (w < 0 || h < 0) {
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
    
            if (!mEgl.eglSwapBuffers(mEglDisplay, mEglSurface)) {
                throw new RuntimeException("Cannot swap buffers");
            }
            checkEglError();
    
            finishGL();

            return true;
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

            bitmap.recycle();
    
            return texture;
        }
        
        private int buildProgram(String vertex, String fragment) {
            int vertexShader = buildShader(vertex, GL_VERTEX_SHADER);
            if (vertexShader == 0) return 0;
    
            int fragmentShader = buildShader(fragment, GL_FRAGMENT_SHADER);
            if (fragmentShader == 0) return 0;
    
            int program = glCreateProgram();
            glAttachShader(program, vertexShader);
            checkGlError();
    
            glAttachShader(program, fragmentShader);
            checkGlError();
    
            glLinkProgram(program);
            checkGlError();
    
            int[] status = new int[1];
            glGetProgramiv(program, GL_LINK_STATUS, status, 0);
            if (status[0] != GL_TRUE) {
                String error = glGetProgramInfoLog(program);
                Log.d(GL_LOG_TAG, "Error while linking program:\n" + error);
                glDeleteShader(vertexShader);
                glDeleteShader(fragmentShader);
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
    
        private void finishGL() {
            mEgl.eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
            mEgl.eglDestroyContext(mEglDisplay, mEglContext);
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
    
            mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay, mEglConfig, surfaceHolder, null);
    
            if (mEglSurface == null || mEglSurface == EGL_NO_SURFACE) {
                int error = mEgl.eglGetError();
                if (error == EGL_BAD_NATIVE_WINDOW) {
                    Log.e(GL_LOG_TAG, "createWindowSurface returned EGL_BAD_NATIVE_WINDOW.");
                    return false;
                }
                throw new RuntimeException("createWindowSurface failed " +
                        GLUtils.getEGLErrorString(error));
            }
    
            if (!mEgl.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                throw new RuntimeException("eglMakeCurrent failed " +
                        GLUtils.getEGLErrorString(mEgl.eglGetError()));
            }
    
            mGL = mEglContext.getGL();

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
                    EGL_NONE
            };
        }
    }
}
