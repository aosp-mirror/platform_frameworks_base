/*
 * Copyright (C) 2010 The Android Open Source Project
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


package android.view;

import android.content.res.CompatibilityInfo;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL11;

import static javax.microedition.khronos.opengles.GL10.GL_COLOR_BUFFER_BIT;
import static javax.microedition.khronos.opengles.GL10.GL_SCISSOR_TEST;

/**
 * Interface for rendering a ViewRoot using hardware acceleration.
 * 
 * @hide
 */
abstract class HardwareRenderer {
    private boolean mEnabled;
    private boolean mRequested = true;

    /**
     * Destroys the hardware rendering context.
     */
    abstract void destroy();

    /**
     * Initializes the hardware renderer for the specified surface.
     * 
     * @param holder The holder for the surface to hardware accelerate.
     * 
     * @return True if the initialization was successful, false otherwise.
     */
    abstract boolean initialize(SurfaceHolder holder);

    /**
     * Setup the hardware renderer for drawing. This is called for every
     * frame to draw.
     * 
     * @param width Width of the drawing surface.
     * @param height Height of the drawing surface.
     * @param attachInfo The AttachInfo used to render the ViewRoot. 
     */
    abstract void setup(int width, int height, View.AttachInfo attachInfo);

    /**
     * Draws the specified view.
     * 
     * @param view The view to draw.
     * @param attachInfo AttachInfo tied to the specified view.
     * @param translator Translator used to draw applications in compatibility mode.
     * @param yoff The vertical offset for the drawing.
     * @param scalingRequired Whether drawing should be scaled.
     */
    abstract void draw(View view, View.AttachInfo attachInfo,
            CompatibilityInfo.Translator translator, int yoff, boolean scalingRequired);

    /**
     * Initializes the hardware renderer for the specified surface and setup the
     * renderer for drawing, if needed. This is invoked when the ViewRoot has
     * potentially lost the hardware renderer. The hardware renderer should be
     * reinitialized and setup when the render {@link #isRequested()} and
     * {@link #isEnabled()}.
     * 
     * @param width The width of the drawing surface.
     * @param height The height of the drawing surface.
     * @param attachInfo The 
     * @param holder
     */
    void initializeIfNeeded(int width, int height, View.AttachInfo attachInfo,
            SurfaceHolder holder) {

        if (isRequested()) {
            // We lost the gl context, so recreate it.
            if (!isEnabled()) {
                if (initialize(holder)) {
                    setup(width, height, attachInfo);
                }
            }
        }        
    }

    /**
     * Creates a hardware renderer using OpenGL.
     * 
     * @param glVersion The version of OpenGL to use (1 for OpenGL 1, 11 for OpenGL 1.1, etc.)
     * 
     * @return A hardware renderer backed by OpenGL.
     */
    static HardwareRenderer createGlRenderer(int glVersion) {
        switch (glVersion) {
            case 1:
                return new Gl10Renderer();
        }
        throw new IllegalArgumentException("Unknown GL version: " + glVersion);
    }

    /**
     * Indicates whether hardware acceleration is currently enabled.
     * 
     * @return True if hardware acceleration is in use, false otherwise.
     */
    boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Indicates whether hardware acceleration is currently enabled.
     * 
     * @param enabled True if the hardware renderer is in use, false otherwise.
     */
    void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    /**
     * Indicates whether hardware acceleration is currently request but not
     * necessarily enabled yet.
     * 
     * @return True if requested, false otherwise.
     */
    boolean isRequested() {
        return mRequested;
    }

    /**
     * Indicates whether hardware acceleration is currently request but not
     * necessarily enabled yet.
     * 
     * @return True to request hardware acceleration, false otherwise.
     */
    void setRequested(boolean requested) {
        mRequested = requested;
    }

    /**
     * Hardware renderer using OpenGL ES 1.0.
     */
    @SuppressWarnings({"deprecation"})
    static class Gl10Renderer extends HardwareRenderer {
        private EGL10 mEgl;
        private EGLDisplay mEglDisplay;
        private EGLContext mEglContext;
        private EGLSurface mEglSurface;
        private GL11 mGL;

        private Canvas mGlCanvas;

        private Gl10Renderer() {
        }

        private void initializeGL(SurfaceHolder holder) {
            initializeGLInner(holder);
            int err = mEgl.eglGetError();
            if (err != EGL10.EGL_SUCCESS) {
                destroy();
                setRequested(false);
            }
        }

        private void initializeGLInner(SurfaceHolder holder) {
            final EGL10 egl = (EGL10) EGLContext.getEGL();
            mEgl = egl;
    
            final EGLDisplay eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            mEglDisplay = eglDisplay;
    
            int[] version = new int[2];
            egl.eglInitialize(eglDisplay, version);
    
            final int[] configSpec = {
                    EGL10.EGL_RED_SIZE,      8,
                    EGL10.EGL_GREEN_SIZE,    8,
                    EGL10.EGL_BLUE_SIZE,     8,
                    EGL10.EGL_DEPTH_SIZE,    0,
                    EGL10.EGL_NONE
            };
            final EGLConfig[] configs = new EGLConfig[1];
            final int[] numConfig = new int[1];
            egl.eglChooseConfig(eglDisplay, configSpec, configs, 1, numConfig);
            final EGLConfig config = configs[0];

            /*
             * Create an OpenGL ES context. This must be done only once, an
             * OpenGL context is a somewhat heavy object.
             */
            final EGLContext context = egl.eglCreateContext(eglDisplay, config,
                    EGL10.EGL_NO_CONTEXT, null);
            mEglContext = context;
    
            /*
             * Create an EGL surface we can render into.
             */
            EGLSurface surface = egl.eglCreateWindowSurface(eglDisplay, config, holder, null);
            mEglSurface = surface;
    
            /*
             * Before we can issue GL commands, we need to make sure
             * the context is current and bound to a surface.
             */
            egl.eglMakeCurrent(eglDisplay, surface, surface, context);
    
            /*
             * Get to the appropriate GL interface.
             * This is simply done by casting the GL context to either
             * GL10 or GL11.
             */
            final GL11 gl = (GL11) context.getGL();
            mGL = gl;
            mGlCanvas = new Canvas(gl);
            setEnabled(true);
        }

        @Override
        void destroy() {
            if (!isEnabled()) return;
            
            // inform skia that the context is gone
            nativeAbandonGlCaches();
    
            mEgl.eglMakeCurrent(mEglDisplay, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            mEgl.eglDestroyContext(mEglDisplay, mEglContext);
            mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
            mEgl.eglTerminate(mEglDisplay);

            mEglContext = null;
            mEglSurface = null;
            mEglDisplay = null;
            mEgl = null;
            mGlCanvas = null;
            mGL = null;

            setEnabled(false);
        }
    
        private void checkErrors() {
            if (isEnabled()) {
                int err = mEgl.eglGetError();
                if (err != EGL10.EGL_SUCCESS) {
                    // something bad has happened revert to
                    // normal rendering.
                    destroy();
                    if (err != EGL11.EGL_CONTEXT_LOST) {
                        // we'll try again if it was context lost
                        setRequested(false);
                    }
                }
            }
        }

        @Override
        boolean initialize(SurfaceHolder holder) {
            if (isRequested() && !isEnabled()) {
                initializeGL(holder);
                return mGlCanvas != null;
            }
            return false;
        }

        @Override
        void setup(int width, int height, View.AttachInfo attachInfo) {
            final float scale = attachInfo.mApplicationScale;
            mGlCanvas.setViewport((int) (width * scale + 0.5f), (int) (height * scale + 0.5f));
        }

        @Override
        void draw(View view, View.AttachInfo attachInfo, CompatibilityInfo.Translator translator,
                int yoff, boolean scalingRequired) {

            Canvas canvas = mGlCanvas;
            if (mGL != null && canvas != null) {
                mGL.glDisable(GL_SCISSOR_TEST);
                mGL.glClearColor(0, 0, 0, 0);
                mGL.glClear(GL_COLOR_BUFFER_BIT);
                mGL.glEnable(GL_SCISSOR_TEST);
    
                attachInfo.mDrawingTime = SystemClock.uptimeMillis();
                attachInfo.mIgnoreDirtyState = true;
                view.mPrivateFlags |= View.DRAWN;
    
                int saveCount = canvas.save(Canvas.MATRIX_SAVE_FLAG);
                try {
                    canvas.translate(0, -yoff);
                    if (translator != null) {
                        translator.translateCanvas(canvas);
                    }
                    canvas.setScreenDensity(scalingRequired ?
                            DisplayMetrics.DENSITY_DEVICE : 0);
    
                    view.draw(canvas);
    
                } finally {
                    canvas.restoreToCount(saveCount);
                }
    
                attachInfo.mIgnoreDirtyState = false;
    
                mEgl.eglSwapBuffers(mEglDisplay, mEglSurface);
                checkErrors();
            }
        }

        @Override
        void initializeIfNeeded(int width, int height, View.AttachInfo attachInfo,
                SurfaceHolder holder) {

            if (isRequested()) {
                checkErrors();
                super.initializeIfNeeded(width, height, attachInfo, holder);
            }
        }
    }

    // inform Skia to just abandon its texture cache IDs
    // doesn't call glDeleteTextures
    private static native void nativeAbandonGlCaches();    
}
