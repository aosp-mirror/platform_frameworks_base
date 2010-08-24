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

import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

/**
 * Interface for rendering a ViewRoot using hardware acceleration.
 * 
 * @hide
 */
public abstract class HardwareRenderer {
    private static final String LOG_TAG = "HardwareRenderer";

    private boolean mEnabled;
    private boolean mRequested = true;

    /**
     * Indicates whether hardware acceleration is available under any form for
     * the view hierarchy.
     * 
     * @return True if the view hierarchy can potentially be hardware accelerated,
     *         false otherwise
     */
    public static boolean isAvailable() {
        return GLES20Canvas.isAvailable();
    }

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
     */
    abstract void setup(int width, int height);

    /**
     * Draws the specified view.
     * 
     * @param view The view to draw.
     * @param attachInfo AttachInfo tied to the specified view.
     */
    abstract void draw(View view, View.AttachInfo attachInfo, int yOffset);

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
                    setup(width, height);
                }
            }
        }        
    }

    /**
     * Creates a hardware renderer using OpenGL.
     * 
     * @param glVersion The version of OpenGL to use (1 for OpenGL 1, 11 for OpenGL 1.1, etc.)
     * @param translucent True if the surface is translucent, false otherwise
     * 
     * @return A hardware renderer backed by OpenGL.
     */
    static HardwareRenderer createGlRenderer(int glVersion, boolean translucent) {
        switch (glVersion) {
            case 2:
                return Gl20Renderer.create(translucent);
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

    @SuppressWarnings({"deprecation"})
    static abstract class GlRenderer extends HardwareRenderer {
        private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

        static EGLContext sEglContext;
        static EGL10 sEgl;
        static EGLDisplay sEglDisplay;
        static EGLConfig sEglConfig;

        private static Thread sEglThread;        

        EGLSurface mEglSurface;
        
        GL mGl;
        GLES20Canvas mCanvas;

        final int mGlVersion;
        final boolean mTranslucent;

        private boolean mDestroyed;

        GlRenderer(int glVersion, boolean translucent) {
            mGlVersion = glVersion;
            mTranslucent = translucent;
        }

        /**
         * Checks for OpenGL errors. If an error has occured, {@link #destroy()}
         * is invoked and the requested flag is turned off. The error code is
         * also logged as a warning.
         */
        void checkErrors() {
            if (isEnabled()) {
                int error = sEgl.eglGetError();
                if (error != EGL10.EGL_SUCCESS) {
                    // something bad has happened revert to
                    // normal rendering.
                    destroy();
                    if (error != EGL11.EGL_CONTEXT_LOST) {
                        // we'll try again if it was context lost
                        setRequested(false);
                    }
                    Log.w(LOG_TAG, "OpenGL error: " + error);
                }
            }
        }
        
        @Override
        boolean initialize(SurfaceHolder holder) {
            if (isRequested() && !isEnabled()) {
                initializeEgl();
                mGl = createEglSurface(holder);
                mDestroyed = false;

                if (mGl != null) {
                    int err = sEgl.eglGetError();
                    if (err != EGL10.EGL_SUCCESS) {
                        destroy();
                        setRequested(false);
                    } else {
                        if (mCanvas != null) {
                            destroyCanvas();
                        }
                        mCanvas = createCanvas();
                        if (mCanvas != null) {
                            setEnabled(true);
                        } else {
                            Log.w(LOG_TAG, "Hardware accelerated Canvas could not be created");
                        }
                    }

                    return mCanvas != null;
                }
            }
            return false;
        }

        private void destroyCanvas() {
            mCanvas.destroy();
            mCanvas = null;
        }

        abstract GLES20Canvas createCanvas();

        void initializeEgl() {
            if (sEglContext != null) return;

            sEglThread = Thread.currentThread();
            sEgl = (EGL10) EGLContext.getEGL();
            
            // Get to the default display.
            sEglDisplay = sEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            
            if (sEglDisplay == EGL10.EGL_NO_DISPLAY) {
                throw new RuntimeException("eglGetDisplay failed");
            }
            
            // We can now initialize EGL for that display
            int[] version = new int[2];
            if (!sEgl.eglInitialize(sEglDisplay, version)) {
                throw new RuntimeException("eglInitialize failed");
            }
            sEglConfig = getConfigChooser(mGlVersion).chooseConfig(sEgl, sEglDisplay);
            
            /*
            * Create an EGL context. We want to do this as rarely as we can, because an
            * EGL context is a somewhat heavy object.
            */
            sEglContext = createContext(sEgl, sEglDisplay, sEglConfig);
        }

        GL createEglSurface(SurfaceHolder holder) {
            // Check preconditions.
            if (sEgl == null) {
                throw new RuntimeException("egl not initialized");
            }
            if (sEglDisplay == null) {
                throw new RuntimeException("eglDisplay not initialized");
            }
            if (sEglConfig == null) {
                throw new RuntimeException("mEglConfig not initialized");
            }
            if (Thread.currentThread() != sEglThread) {
                throw new IllegalStateException("HardwareRenderer cannot be used " 
                        + "from multiple threads");
            }

            /*
             *  The window size has changed, so we need to create a new
             *  surface.
             */
            if (mEglSurface != null && mEglSurface != EGL10.EGL_NO_SURFACE) {
                /*
                 * Unbind and destroy the old EGL surface, if
                 * there is one.
                 */
                sEgl.eglMakeCurrent(sEglDisplay, EGL10.EGL_NO_SURFACE,
                        EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                sEgl.eglDestroySurface(sEglDisplay, mEglSurface);
            }

            // Create an EGL surface we can render into.
            mEglSurface = sEgl.eglCreateWindowSurface(sEglDisplay, sEglConfig, holder, null);

            if (mEglSurface == null || mEglSurface == EGL10.EGL_NO_SURFACE) {
                int error = sEgl.eglGetError();
                if (error == EGL10.EGL_BAD_NATIVE_WINDOW) {
                    Log.e("EglHelper", "createWindowSurface returned EGL_BAD_NATIVE_WINDOW.");
                    return null;
                }
                throw new RuntimeException("createWindowSurface failed");
            }

            /*
             * Before we can issue GL commands, we need to make sure
             * the context is current and bound to a surface.
             */
            if (!sEgl.eglMakeCurrent(sEglDisplay, mEglSurface, mEglSurface, sEglContext)) {
                throw new RuntimeException("eglMakeCurrent failed");
            }

            return sEglContext.getGL();
        }

        EGLContext createContext(EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig) {
            int[] attrib_list = { EGL_CONTEXT_CLIENT_VERSION, mGlVersion, EGL10.EGL_NONE };

            return egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT,
                    mGlVersion != 0 ? attrib_list : null);            
        }

        @Override
        void initializeIfNeeded(int width, int height, View.AttachInfo attachInfo,
                SurfaceHolder holder) {
            if (isRequested()) {
                checkErrors();
                super.initializeIfNeeded(width, height, attachInfo, holder);
            }
        }
        
        @Override
        void destroy() {
            if (!isEnabled() || mDestroyed) return;

            mDestroyed = true;

            checkCurrent();
            // Destroy the Canvas first in case it needs to use a GL context
            // to perform its cleanup.
            destroyCanvas();

            sEgl.eglMakeCurrent(sEglDisplay, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            sEgl.eglDestroySurface(sEglDisplay, mEglSurface);

            mEglSurface = null;
            mGl = null;

            // mEgl.eglDestroyContext(mEglDisplay, mEglContext);
            // mEglContext = null;            
            // mEgl.eglTerminate(mEglDisplay);
            // mEgl = null;
            // mEglDisplay = null;

            setEnabled(false);
        }

        @Override
        void setup(int width, int height) {
            mCanvas.setViewport(width, height);
        }
        
        boolean canDraw() {
            return mGl != null && mCanvas != null;
        }        
        
        void onPreDraw() {
        }

        /**
         * Defines the EGL configuration for this renderer. The default configuration
         * is RGBX, no depth, no stencil.
         * 
         * @return An {@link android.view.HardwareRenderer.GlRenderer.EglConfigChooser}.
         * @param glVersion
         */
        EglConfigChooser getConfigChooser(int glVersion) {
            // TODO: Check for mTranslucent here, which means at least 2 EGL contexts
            return new ComponentSizeChooser(glVersion, 8, 8, 8, 8, 0, 0);
        }

        @Override
        void draw(View view, View.AttachInfo attachInfo, int yOffset) {
            if (canDraw()) {
                attachInfo.mDrawingTime = SystemClock.uptimeMillis();
                attachInfo.mIgnoreDirtyState = true;
                view.mPrivateFlags |= View.DRAWN;

                checkCurrent();

                onPreDraw();

                Canvas canvas = mCanvas;
                int saveCount = canvas.save();
                canvas.translate(0, -yOffset);

                try {
                    view.draw(canvas);
                } finally {
                    canvas.restoreToCount(saveCount);
                }

                attachInfo.mIgnoreDirtyState = false;

                sEgl.eglSwapBuffers(sEglDisplay, mEglSurface);
                checkErrors();
            }
        }

        private void checkCurrent() {
            // TODO: Don't check the current context when we have one per UI thread
            // TODO: Use a threadlocal flag to know whether the surface has changed
            if (sEgl.eglGetCurrentContext() != sEglContext ||
                    sEgl.eglGetCurrentSurface(EGL10.EGL_DRAW) != mEglSurface) {
                if (!sEgl.eglMakeCurrent(sEglDisplay, mEglSurface, mEglSurface, sEglContext)) {
                    throw new RuntimeException("eglMakeCurrent failed");
                }
            }
        }

        static abstract class EglConfigChooser {
            final int[] mConfigSpec;
            private final int mGlVersion;

            EglConfigChooser(int glVersion, int[] configSpec) {
                mGlVersion = glVersion;
                mConfigSpec = filterConfigSpec(configSpec);
            }

            EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
                int[] index = new int[1];
                if (!egl.eglChooseConfig(display, mConfigSpec, null, 0, index)) {
                    throw new IllegalArgumentException("eglChooseConfig failed");
                }

                int numConfigs = index[0];
                if (numConfigs <= 0) {
                    throw new IllegalArgumentException("No configs match configSpec");
                }

                EGLConfig[] configs = new EGLConfig[numConfigs];
                if (!egl.eglChooseConfig(display, mConfigSpec, configs, numConfigs, index)) {
                    throw new IllegalArgumentException("eglChooseConfig failed");
                }

                EGLConfig config = chooseConfig(egl, display, configs);
                if (config == null) {
                    throw new IllegalArgumentException("No config chosen");
                }

                return config;
            }

            abstract EGLConfig chooseConfig(EGL10 egl, EGLDisplay display, EGLConfig[] configs);

            private int[] filterConfigSpec(int[] configSpec) {
                if (mGlVersion != 2) {
                    return configSpec;
                }
                /* We know none of the subclasses define EGL_RENDERABLE_TYPE.
                 * And we know the configSpec is well formed.
                 */
                int len = configSpec.length;
                int[] newConfigSpec = new int[len + 2];
                System.arraycopy(configSpec, 0, newConfigSpec, 0, len - 1);
                newConfigSpec[len - 1] = EGL10.EGL_RENDERABLE_TYPE;
                newConfigSpec[len] = 4; /* EGL_OPENGL_ES2_BIT */
                newConfigSpec[len + 1] = EGL10.EGL_NONE;
                return newConfigSpec;
            }
        }

        /**
         * Choose a configuration with exactly the specified r,g,b,a sizes,
         * and at least the specified depth and stencil sizes.
         */
        static class ComponentSizeChooser extends EglConfigChooser {
            private int[] mValue;

            private int mRedSize;
            private int mGreenSize;
            private int mBlueSize;
            private int mAlphaSize;
            private int mDepthSize;
            private int mStencilSize;

            ComponentSizeChooser(int glVersion, int redSize, int greenSize, int blueSize,
                    int alphaSize, int depthSize, int stencilSize) {
                super(glVersion, new int[] {
                        EGL10.EGL_RED_SIZE, redSize,
                        EGL10.EGL_GREEN_SIZE, greenSize,
                        EGL10.EGL_BLUE_SIZE, blueSize,
                        EGL10.EGL_ALPHA_SIZE, alphaSize,
                        EGL10.EGL_DEPTH_SIZE, depthSize,
                        EGL10.EGL_STENCIL_SIZE, stencilSize,
                        EGL10.EGL_NONE });
                mValue = new int[1];
                mRedSize = redSize;
                mGreenSize = greenSize;
                mBlueSize = blueSize;
                mAlphaSize = alphaSize;
                mDepthSize = depthSize;
                mStencilSize = stencilSize;
           }

            @Override
            EGLConfig chooseConfig(EGL10 egl, EGLDisplay display, EGLConfig[] configs) {
                for (EGLConfig config : configs) {
                    int d = findConfigAttrib(egl, display, config, EGL10.EGL_DEPTH_SIZE, 0);
                    int s = findConfigAttrib(egl, display, config, EGL10.EGL_STENCIL_SIZE, 0);
                    if (d >= mDepthSize && s >= mStencilSize) {
                        int r = findConfigAttrib(egl, display, config, EGL10.EGL_RED_SIZE, 0);
                        int g = findConfigAttrib(egl, display, config, EGL10.EGL_GREEN_SIZE, 0);
                        int b = findConfigAttrib(egl, display, config, EGL10.EGL_BLUE_SIZE, 0);
                        int a = findConfigAttrib(egl, display, config, EGL10.EGL_ALPHA_SIZE, 0);
                        if (r >= mRedSize && g >= mGreenSize && b >= mBlueSize && a >= mAlphaSize) {
                            return config;
                        }
                    }
                }
                return null;
            }

            private int findConfigAttrib(EGL10 egl, EGLDisplay display, EGLConfig config,
                    int attribute, int defaultValue) {
                if (egl.eglGetConfigAttrib(display, config, attribute, mValue)) {
                    return mValue[0];
                }

                return defaultValue;
            }
        }
    }

    /**
     * Hardware renderer using OpenGL ES 2.0.
     */
    static class Gl20Renderer extends GlRenderer {
        private GLES20Canvas mGlCanvas;

        Gl20Renderer(boolean translucent) {
            super(2, translucent);
        }

        @Override
        GLES20Canvas createCanvas() {
            // TODO: Pass mTranslucent instead of true
            return mGlCanvas = new GLES20Canvas(mGl, true);
        }

        @Override
        void onPreDraw() {
            mGlCanvas.onPreDraw();
        }

        static HardwareRenderer create(boolean translucent) {
            if (GLES20Canvas.isAvailable()) {
                return new Gl20Renderer(translucent);
            }
            return null;
        }
    }
}
