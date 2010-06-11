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
import android.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;
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
    private static final String LOG_TAG = "HardwareRenderer";

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
            case 2:
                return new Gl20Renderer();
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

        EGL10 mEgl;
        EGLDisplay mEglDisplay;
        EGLContext mEglContext;
        EGLSurface mEglSurface;
        EGLConfig mEglConfig;

        GL mGl;
        Canvas mCanvas;

        int mGlVersion;

        GlRenderer(int glVersion) {
            mGlVersion = glVersion;
        }

        /**
         * Checks for OpenGL errors. If an error has occured, {@link #destroy()}
         * is invoked and the requested flag is turned off. The error code is
         * also logged as a warning.
         */
        void checkErrors() {
            if (isEnabled()) {
                int error = mEgl.eglGetError();
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

                if (mGl != null) {
                    int err = mEgl.eglGetError();
                    if (err != EGL10.EGL_SUCCESS) {
                        destroy();
                        setRequested(false);
                    } else {
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

        abstract Canvas createCanvas();

        void initializeEgl() {
            mEgl = (EGL10) EGLContext.getEGL();
            
            // Get to the default display.
            mEglDisplay = mEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            
            if (mEglDisplay == EGL10.EGL_NO_DISPLAY) {
                throw new RuntimeException("eglGetDisplay failed");
            }
            
            // We can now initialize EGL for that display
            int[] version = new int[2];
            if (!mEgl.eglInitialize(mEglDisplay, version)) {
                throw new RuntimeException("eglInitialize failed");
            }
            mEglConfig = getConfigChooser(mGlVersion).chooseConfig(mEgl, mEglDisplay);
            
            /*
            * Create an EGL context. We want to do this as rarely as we can, because an
            * EGL context is a somewhat heavy object.
            */
            mEglContext = createContext(mEgl, mEglDisplay, mEglConfig);
        }

        GL createEglSurface(SurfaceHolder holder) {
            // Check preconditions.
            if (mEgl == null) {
                throw new RuntimeException("egl not initialized");
            }
            if (mEglDisplay == null) {
                throw new RuntimeException("eglDisplay not initialized");
            }
            if (mEglConfig == null) {
                throw new RuntimeException("mEglConfig not initialized");
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
                mEgl.eglMakeCurrent(mEglDisplay, EGL10.EGL_NO_SURFACE,
                        EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
            }

            // Create an EGL surface we can render into.
            mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay, mEglConfig, holder, null);

            if (mEglSurface == null || mEglSurface == EGL10.EGL_NO_SURFACE) {
                int error = mEgl.eglGetError();
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
            if (!mEgl.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                throw new RuntimeException("eglMakeCurrent failed");
                
            }

            return mEglContext.getGL();
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
            if (!isEnabled()) return;

            mEgl.eglMakeCurrent(mEglDisplay, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            mEgl.eglDestroyContext(mEglDisplay, mEglContext);
            mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
            mEgl.eglTerminate(mEglDisplay);

            mEglContext = null;
            mEglSurface = null;
            mEglDisplay = null;
            mEgl = null;
            mGl = null;
            mCanvas = null;

            setEnabled(false);
        }        
        
        @Override
        void setup(int width, int height, View.AttachInfo attachInfo) {
            final float scale = attachInfo.mApplicationScale;
            mCanvas.setViewport((int) (width * scale + 0.5f), (int) (height * scale + 0.5f));
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
            return new ComponentSizeChooser(glVersion, 8, 8, 8, 0, 0, 0);
        }

        @Override
        void draw(View view, View.AttachInfo attachInfo, CompatibilityInfo.Translator translator,
                int yoff, boolean scalingRequired) {

            if (canDraw()) {
                attachInfo.mDrawingTime = SystemClock.uptimeMillis();
                attachInfo.mIgnoreDirtyState = true;
                view.mPrivateFlags |= View.DRAWN;

                onPreDraw();

                Canvas canvas = mCanvas;
                int saveCount = canvas.save(Canvas.MATRIX_SAVE_FLAG);
                try {
                    canvas.translate(0, -yoff);
                    if (translator != null) {
                        translator.translateCanvas(canvas);
                    }
                    canvas.setScreenDensity(scalingRequired ? DisplayMetrics.DENSITY_DEVICE : 0);
    
                    view.draw(canvas);
                } finally {
                    canvas.restoreToCount(saveCount);
                }

                attachInfo.mIgnoreDirtyState = false;

                mEgl.eglSwapBuffers(mEglDisplay, mEglSurface);
                checkErrors();
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
                int[] num_config = new int[1];
                if (!egl.eglChooseConfig(display, mConfigSpec, null, 0,
                        num_config)) {
                    throw new IllegalArgumentException("eglChooseConfig failed");
                }

                int numConfigs = num_config[0];

                if (numConfigs <= 0) {
                    throw new IllegalArgumentException(
                            "No configs match configSpec");
                }

                EGLConfig[] configs = new EGLConfig[numConfigs];
                if (!egl.eglChooseConfig(display, mConfigSpec, configs, numConfigs,
                        num_config)) {
                    throw new IllegalArgumentException("eglChooseConfig#2 failed");
                }
                EGLConfig config = chooseConfig(egl, display, configs);
                if (config == null) {
                    throw new IllegalArgumentException("No config chosen");
                }
                return config;
            }

            abstract EGLConfig chooseConfig(EGL10 egl, EGLDisplay display,
                    EGLConfig[] configs);

            private int[] filterConfigSpec(int[] configSpec) {
                if (mGlVersion != 2) {
                    return configSpec;
                }
                /* We know none of the subclasses define EGL_RENDERABLE_TYPE.
                 * And we know the configSpec is well formed.
                 */
                int len = configSpec.length;
                int[] newConfigSpec = new int[len + 2];
                System.arraycopy(configSpec, 0, newConfigSpec, 0, len-1);
                newConfigSpec[len-1] = EGL10.EGL_RENDERABLE_TYPE;
                newConfigSpec[len] = 4; /* EGL_OPENGL_ES2_BIT */
                newConfigSpec[len+1] = EGL10.EGL_NONE;
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
                    if ((d >= mDepthSize) && (s >= mStencilSize)) {
                        int r = findConfigAttrib(egl, display, config, EGL10.EGL_RED_SIZE, 0);
                        int g = findConfigAttrib(egl, display, config, EGL10.EGL_GREEN_SIZE, 0);
                        int b = findConfigAttrib(egl, display, config, EGL10.EGL_BLUE_SIZE, 0);
                        int a = findConfigAttrib(egl, display, config, EGL10.EGL_ALPHA_SIZE, 0);
                        if ((r == mRedSize) && (g == mGreenSize) && (b == mBlueSize) &&
                                (a == mAlphaSize)) {
                            return config;
                        }
                    }
                }
                return null;
            }

            private int findConfigAttrib(EGL10 egl, EGLDisplay display,
                    EGLConfig config, int attribute, int defaultValue) {

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
        Gl20Renderer() {
            super(2);
        }

        @Override
        Canvas createCanvas() {
            return null;
        }        
    }

    /**
     * Hardware renderer using OpenGL ES 1.0.
     */
    @SuppressWarnings({"deprecation"})
    static class Gl10Renderer extends GlRenderer {
        Gl10Renderer() {
            super(1);
        }

        @Override
        Canvas createCanvas() {
            return new Canvas(mGl);
        }

        @Override
        void destroy() {
            if (isEnabled()) {
                nativeAbandonGlCaches();
            }

            super.destroy();
        }

        @Override
        void onPreDraw() {
            GL11 gl = (GL11) mGl;
            gl.glDisable(GL_SCISSOR_TEST);
            gl.glClearColor(0, 0, 0, 0);
            gl.glClear(GL_COLOR_BUFFER_BIT);
            gl.glEnable(GL_SCISSOR_TEST);
        }
    }

    // Inform Skia to just abandon its texture cache IDs doesn't call glDeleteTextures
    // Used only by the native Skia OpenGL ES 1.x implementation
    private static native void nativeAbandonGlCaches();
}
