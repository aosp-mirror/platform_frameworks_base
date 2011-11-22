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

import android.content.ComponentCallbacks2;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.GLUtils;
import android.opengl.ManagedEGLContext;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import com.google.android.gles_jni.EGLImpl;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

import java.io.File;

import static javax.microedition.khronos.egl.EGL10.*;

/**
 * Interface for rendering a ViewAncestor using hardware acceleration.
 * 
 * @hide
 */
public abstract class HardwareRenderer {
    static final String LOG_TAG = "HardwareRenderer";

    /**
     * Name of the file that holds the shaders cache.
     */
    private static final String CACHE_PATH_SHADERS = "com.android.opengl.shaders_cache";

    /**
     * Turn on to only refresh the parts of the screen that need updating.
     * When turned on the property defined by {@link #RENDER_DIRTY_REGIONS_PROPERTY}
     * must also have the value "true". 
     */
    public static final boolean RENDER_DIRTY_REGIONS = true;

    /**
     * System property used to enable or disable dirty regions invalidation.
     * This property is only queried if {@link #RENDER_DIRTY_REGIONS} is true.
     * The default value of this property is assumed to be true.
     * 
     * Possible values:
     * "true", to enable partial invalidates
     * "false", to disable partial invalidates
     */
    static final String RENDER_DIRTY_REGIONS_PROPERTY = "hwui.render_dirty_regions";
    
    /**
     * System property used to enable or disable vsync.
     * The default value of this property is assumed to be false.
     * 
     * Possible values:
     * "true", to disable vsync
     * "false", to enable vsync
     */
    static final String DISABLE_VSYNC_PROPERTY = "hwui.disable_vsync";

    /**
     * System property used to debug EGL configuration choice.
     * 
     * Possible values:
     * "choice", print the chosen configuration only
     * "all", print all possible configurations
     */
    static final String PRINT_CONFIG_PROPERTY = "hwui.print_config";    

    /**
     * Turn on to draw dirty regions every other frame.
     */
    private static final boolean DEBUG_DIRTY_REGION = false;
    
    /**
     * A process can set this flag to false to prevent the use of hardware
     * rendering.
     * 
     * @hide
     */
    public static boolean sRendererDisabled = false;

    /**
     * Further hardware renderer disabling for the system process.
     * 
     * @hide
     */
    public static boolean sSystemRendererDisabled = false;

    private boolean mEnabled;
    private boolean mRequested = true;

    /**
     * Invoke this method to disable hardware rendering in the current process.
     * 
     * @hide
     */
    public static void disable(boolean system) {
        sRendererDisabled = true;
        if (system) {
            sSystemRendererDisabled = true;
        }
    }

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
     * 
     * @param full If true, destroys all associated resources.
     */
    abstract void destroy(boolean full);

    /**
     * Initializes the hardware renderer for the specified surface.
     * 
     * @param holder The holder for the surface to hardware accelerate.
     * 
     * @return True if the initialization was successful, false otherwise.
     */
    abstract boolean initialize(SurfaceHolder holder) throws Surface.OutOfResourcesException;
    
    /**
     * Updates the hardware renderer for the specified surface.
     * 
     * @param holder The holder for the surface to hardware accelerate
     */
    abstract void updateSurface(SurfaceHolder holder) throws Surface.OutOfResourcesException;

    /**
     * Destroys the layers used by the specified view hierarchy.
     * 
     * @param view The root of the view hierarchy
     */
    abstract void destroyLayers(View view);

    /**
     * Destroys all hardware rendering resources associated with the specified
     * view hierarchy.
     * 
     * @param view The root of the view hierarchy
     */
    abstract void destroyHardwareResources(View view);
    
    /**
     * This method should be invoked whenever the current hardware renderer
     * context should be reset.
     * 
     * @param holder The holder for the surface to hardware accelerate
     */
    abstract void invalidate(SurfaceHolder holder);

    /**
     * This method should be invoked to ensure the hardware renderer is in
     * valid state (for instance, to ensure the correct EGL context is bound
     * to the current thread.)
     * 
     * @return true if the renderer is now valid, false otherwise
     */
    abstract boolean validate();

    /**
     * Setup the hardware renderer for drawing. This is called whenever the
     * size of the target surface changes or when the surface is first created.
     * 
     * @param width Width of the drawing surface.
     * @param height Height of the drawing surface.
     */
    abstract void setup(int width, int height);

    /**
     * Gets the current width of the surface. This is the width that the surface
     * was last set to in a call to {@link #setup(int, int)}.
     *
     * @return the current width of the surface
     */
    abstract int getWidth();

    /**
     * Gets the current height of the surface. This is the height that the surface
     * was last set to in a call to {@link #setup(int, int)}.
     *
     * @return the current width of the surface
     */
    abstract int getHeight();

    /**
     * Sets the directory to use as a persistent storage for hardware rendering
     * resources.
     * 
     * @param cacheDir A directory the current process can write to
     */
    public static void setupDiskCache(File cacheDir) {
        nSetupShadersDiskCache(new File(cacheDir, CACHE_PATH_SHADERS).getAbsolutePath());
    }

    private static native void nSetupShadersDiskCache(String cacheFile);

    /**
     * Interface used to receive callbacks whenever a view is drawn by
     * a hardware renderer instance.
     */
    interface HardwareDrawCallbacks {
        /**
         * Invoked before a view is drawn by a hardware renderer.
         * 
         * @param canvas The Canvas used to render the view.
         */
        void onHardwarePreDraw(HardwareCanvas canvas);

        /**
         * Invoked after a view is drawn by a hardware renderer.
         * 
         * @param canvas The Canvas used to render the view.
         */
        void onHardwarePostDraw(HardwareCanvas canvas);
    }

    /**
     * Draws the specified view.
     *
     * @param view The view to draw.
     * @param attachInfo AttachInfo tied to the specified view.
     * @param callbacks Callbacks invoked when drawing happens.
     * @param dirty The dirty rectangle to update, can be null.
     * 
     * @return true if the dirty rect was ignored, false otherwise
     */
    abstract boolean draw(View view, View.AttachInfo attachInfo, HardwareDrawCallbacks callbacks,
            Rect dirty);

    /**
     * Creates a new display list that can be used to record batches of
     * drawing operations.
     * 
     * @return A new display list.
     */
    abstract DisplayList createDisplayList();

    /**
     * Creates a new hardware layer. A hardware layer built by calling this
     * method will be treated as a texture layer, instead of as a render target.
     * 
     * @param isOpaque Whether the layer should be opaque or not
     * 
     * @return A hardware layer
     */    
    abstract HardwareLayer createHardwareLayer(boolean isOpaque);
    
    /**
     * Creates a new hardware layer.
     * 
     * @param width The minimum width of the layer
     * @param height The minimum height of the layer
     * @param isOpaque Whether the layer should be opaque or not
     * 
     * @return A hardware layer
     */
    abstract HardwareLayer createHardwareLayer(int width, int height, boolean isOpaque);

    /**
     * Creates a new {@link SurfaceTexture} that can be used to render into the
     * specified hardware layer.
     * 
     *
     * @param layer The layer to render into using a {@link android.graphics.SurfaceTexture}
     * 
     * @return A {@link SurfaceTexture}
     */
    abstract SurfaceTexture createSurfaceTexture(HardwareLayer layer);

    /**
     * Initializes the hardware renderer for the specified surface and setup the
     * renderer for drawing, if needed. This is invoked when the ViewAncestor has
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
            SurfaceHolder holder) throws Surface.OutOfResourcesException {
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
     * Invoke this method when the system is running out of memory. This
     * method will attempt to recover as much memory as possible, based on
     * the specified hint.
     * 
     * @param level Hint about the amount of memory that should be trimmed,
     *              see {@link android.content.ComponentCallbacks}
     */
    static void trimMemory(int level) {
        Gl20Renderer.trimMemory(level);
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
     * Indicates whether hardware acceleration is currently requested but not
     * necessarily enabled yet.
     * 
     * @return True to request hardware acceleration, false otherwise.
     */
    void setRequested(boolean requested) {
        mRequested = requested;
    }

    @SuppressWarnings({"deprecation"})
    static abstract class GlRenderer extends HardwareRenderer {
        // These values are not exposed in our EGL APIs
        static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        static final int EGL_OPENGL_ES2_BIT = 4;
        static final int EGL_SURFACE_TYPE = 0x3033;
        static final int EGL_SWAP_BEHAVIOR_PRESERVED_BIT = 0x0400;        

        static final int SURFACE_STATE_ERROR = 0;
        static final int SURFACE_STATE_SUCCESS = 1;
        static final int SURFACE_STATE_UPDATED = 2;
        
        static EGL10 sEgl;
        static EGLDisplay sEglDisplay;
        static EGLConfig sEglConfig;
        static final Object[] sEglLock = new Object[0];
        int mWidth = -1, mHeight = -1;

        static final ThreadLocal<Gl20Renderer.Gl20RendererEglContext> sEglContextStorage
                = new ThreadLocal<Gl20Renderer.Gl20RendererEglContext>();

        EGLContext mEglContext;
        Thread mEglThread;

        EGLSurface mEglSurface;
        
        GL mGl;
        HardwareCanvas mCanvas;
        int mFrameCount;
        Paint mDebugPaint;

        static boolean sDirtyRegions;
        static final boolean sDirtyRegionsRequested;
        static {
            String dirtyProperty = SystemProperties.get(RENDER_DIRTY_REGIONS_PROPERTY, "true");
            //noinspection PointlessBooleanExpression,ConstantConditions
            sDirtyRegions = RENDER_DIRTY_REGIONS && "true".equalsIgnoreCase(dirtyProperty);
            sDirtyRegionsRequested = sDirtyRegions;
        }

        boolean mDirtyRegionsEnabled;
        final boolean mVsyncDisabled;

        final int mGlVersion;
        final boolean mTranslucent;

        private boolean mDestroyed;

        private final Rect mRedrawClip = new Rect();

        GlRenderer(int glVersion, boolean translucent) {
            mGlVersion = glVersion;
            mTranslucent = translucent;

            final String vsyncProperty = SystemProperties.get(DISABLE_VSYNC_PROPERTY, "false");
            mVsyncDisabled = "true".equalsIgnoreCase(vsyncProperty);
            if (mVsyncDisabled) {
                Log.d(LOG_TAG, "Disabling v-sync");
            }
        }

        /**
         * Indicates whether this renderer instance can track and update dirty regions.
         */
        boolean hasDirtyRegions() {
            return mDirtyRegionsEnabled;
        }

        /**
         * Checks for OpenGL errors. If an error has occured, {@link #destroy(boolean)}
         * is invoked and the requested flag is turned off. The error code is
         * also logged as a warning.
         */
        void checkEglErrors() {
            if (isEnabled()) {
                int error = sEgl.eglGetError();
                if (error != EGL_SUCCESS) {
                    // something bad has happened revert to
                    // normal rendering.
                    Log.w(LOG_TAG, "EGL error: " + GLUtils.getEGLErrorString(error));
                    fallback(error != EGL11.EGL_CONTEXT_LOST);
                }
            }
        }

        private void fallback(boolean fallback) {
            destroy(true);
            if (fallback) {
                // we'll try again if it was context lost
                setRequested(false);
                Log.w(LOG_TAG, "Mountain View, we've had a problem here. " 
                        + "Switching back to software rendering.");
            }
        }

        @Override
        boolean initialize(SurfaceHolder holder) throws Surface.OutOfResourcesException {
            if (isRequested() && !isEnabled()) {
                initializeEgl();
                mGl = createEglSurface(holder);
                mDestroyed = false;

                if (mGl != null) {
                    int err = sEgl.eglGetError();
                    if (err != EGL_SUCCESS) {
                        destroy(true);
                        setRequested(false);
                    } else {
                        if (mCanvas == null) {
                            mCanvas = createCanvas();
                        }
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
        
        @Override
        void updateSurface(SurfaceHolder holder) throws Surface.OutOfResourcesException {
            if (isRequested() && isEnabled()) {
                createEglSurface(holder);
            }
        }

        abstract GLES20Canvas createCanvas();

        abstract int[] getConfig(boolean dirtyRegions);

        void initializeEgl() {
            synchronized (sEglLock) {
                if (sEgl == null && sEglConfig == null) {
                    sEgl = (EGL10) EGLContext.getEGL();
                    
                    // Get to the default display.
                    sEglDisplay = sEgl.eglGetDisplay(EGL_DEFAULT_DISPLAY);
                    
                    if (sEglDisplay == EGL_NO_DISPLAY) {
                        throw new RuntimeException("eglGetDisplay failed "
                                + GLUtils.getEGLErrorString(sEgl.eglGetError()));
                    }
                    
                    // We can now initialize EGL for that display
                    int[] version = new int[2];
                    if (!sEgl.eglInitialize(sEglDisplay, version)) {
                        throw new RuntimeException("eglInitialize failed " +
                                GLUtils.getEGLErrorString(sEgl.eglGetError()));
                    }
        
                    sEglConfig = chooseEglConfig();
                    if (sEglConfig == null) {
                        // We tried to use EGL_SWAP_BEHAVIOR_PRESERVED_BIT, try again without
                        if (sDirtyRegions) {
                            sDirtyRegions = false;
                            sEglConfig = chooseEglConfig();
                            if (sEglConfig == null) {
                                throw new RuntimeException("eglConfig not initialized");
                            }
                        } else {
                            throw new RuntimeException("eglConfig not initialized");
                        }
                    }
                }
            }

            Gl20Renderer.Gl20RendererEglContext managedContext = sEglContextStorage.get();
            mEglContext = managedContext != null ? managedContext.getContext() : null;
            mEglThread = Thread.currentThread();

            if (mEglContext == null) {
                mEglContext = createContext(sEgl, sEglDisplay, sEglConfig);
                sEglContextStorage.set(new Gl20Renderer.Gl20RendererEglContext(mEglContext));
            }
        }

        private EGLConfig chooseEglConfig() {
            EGLConfig[] configs = new EGLConfig[1];
            int[] configsCount = new int[1];
            int[] configSpec = getConfig(sDirtyRegions);

            // Debug
            final String debug = SystemProperties.get(PRINT_CONFIG_PROPERTY, "");
            if ("all".equalsIgnoreCase(debug)) {
                sEgl.eglChooseConfig(sEglDisplay, configSpec, null, 0, configsCount);

                EGLConfig[] debugConfigs = new EGLConfig[configsCount[0]];
                sEgl.eglChooseConfig(sEglDisplay, configSpec, debugConfigs,
                        configsCount[0], configsCount);

                for (EGLConfig config : debugConfigs) {
                    printConfig(config);
                }
            }

            if (!sEgl.eglChooseConfig(sEglDisplay, configSpec, configs, 1, configsCount)) {
                throw new IllegalArgumentException("eglChooseConfig failed " +
                        GLUtils.getEGLErrorString(sEgl.eglGetError()));
            } else if (configsCount[0] > 0) {
                if ("choice".equalsIgnoreCase(debug)) {
                    printConfig(configs[0]);
                }
                return configs[0];
            }

            return null;
        }

        private void printConfig(EGLConfig config) {
            int[] value = new int[1];

            Log.d(LOG_TAG, "EGL configuration " + config + ":");

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_RED_SIZE, value);
            Log.d(LOG_TAG, "  RED_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_GREEN_SIZE, value);
            Log.d(LOG_TAG, "  GREEN_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_BLUE_SIZE, value);
            Log.d(LOG_TAG, "  BLUE_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_ALPHA_SIZE, value);
            Log.d(LOG_TAG, "  ALPHA_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_DEPTH_SIZE, value);
            Log.d(LOG_TAG, "  DEPTH_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_STENCIL_SIZE, value);
            Log.d(LOG_TAG, "  STENCIL_SIZE = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_SURFACE_TYPE, value);
            Log.d(LOG_TAG, "  SURFACE_TYPE = 0x" + Integer.toHexString(value[0]));
        }

        GL createEglSurface(SurfaceHolder holder) throws Surface.OutOfResourcesException {
            // Check preconditions.
            if (sEgl == null) {
                throw new RuntimeException("egl not initialized");
            }
            if (sEglDisplay == null) {
                throw new RuntimeException("eglDisplay not initialized");
            }
            if (sEglConfig == null) {
                throw new RuntimeException("eglConfig not initialized");
            }
            if (Thread.currentThread() != mEglThread) {
                throw new IllegalStateException("HardwareRenderer cannot be used " 
                        + "from multiple threads");
            }

            // In case we need to destroy an existing surface
            destroySurface();

            // Create an EGL surface we can render into.
            if (!createSurface(holder)) {
                return null;
            }

            /*
             * Before we can issue GL commands, we need to make sure
             * the context is current and bound to a surface.
             */
            if (!sEgl.eglMakeCurrent(sEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                throw new Surface.OutOfResourcesException("eglMakeCurrent failed "
                        + GLUtils.getEGLErrorString(sEgl.eglGetError()));
            }
            
            initCaches();

            // If mDirtyRegions is set, this means we have an EGL configuration
            // with EGL_SWAP_BEHAVIOR_PRESERVED_BIT set
            if (sDirtyRegions) {
                if (!(mDirtyRegionsEnabled = GLES20Canvas.preserveBackBuffer())) {
                    Log.w(LOG_TAG, "Backbuffer cannot be preserved");
                }
            } else if (sDirtyRegionsRequested) {
                // If mDirtyRegions is not set, our EGL configuration does not
                // have EGL_SWAP_BEHAVIOR_PRESERVED_BIT; however, the default
                // swap behavior might be EGL_BUFFER_PRESERVED, which means we
                // want to set mDirtyRegions. We try to do this only if dirty
                // regions were initially requested as part of the device
                // configuration (see RENDER_DIRTY_REGIONS)
                mDirtyRegionsEnabled = GLES20Canvas.isBackBufferPreserved();
            }

            return mEglContext.getGL();
        }

        abstract void initCaches();

        EGLContext createContext(EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig) {
            int[] attribs = { EGL_CONTEXT_CLIENT_VERSION, mGlVersion, EGL_NONE };

            return egl.eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT,
                    mGlVersion != 0 ? attribs : null);            
        }

        @Override
        void destroy(boolean full) {
            if (full && mCanvas != null) {
                mCanvas = null;
            }

            if (!isEnabled() || mDestroyed) {
                setEnabled(false);
                return;
            }

            destroySurface();
            setEnabled(false);

            mDestroyed = true;
            mGl = null;
        }

        void destroySurface() {
            if (mEglSurface != null && mEglSurface != EGL_NO_SURFACE) {
                sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                sEgl.eglDestroySurface(sEglDisplay, mEglSurface);
                mEglSurface = null;
            }
        }

        @Override
        void invalidate(SurfaceHolder holder) {
            // Cancels any existing buffer to ensure we'll get a buffer
            // of the right size before we call eglSwapBuffers
            sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

            if (mEglSurface != null && mEglSurface != EGL_NO_SURFACE) {
                sEgl.eglDestroySurface(sEglDisplay, mEglSurface);
                mEglSurface = null;
                setEnabled(false);
            }

            if (holder.getSurface().isValid()) {
                if (!createSurface(holder)) {
                    return;
                }
                if (mCanvas != null) {
                    setEnabled(true);
                }
            }
        }

        private boolean createSurface(SurfaceHolder holder) {
            mEglSurface = sEgl.eglCreateWindowSurface(sEglDisplay, sEglConfig, holder, null);

            if (mEglSurface == null || mEglSurface == EGL_NO_SURFACE) {
                int error = sEgl.eglGetError();
                if (error == EGL_BAD_NATIVE_WINDOW) {
                    Log.e(LOG_TAG, "createWindowSurface returned EGL_BAD_NATIVE_WINDOW.");
                    return false;
                }
                throw new RuntimeException("createWindowSurface failed "
                        + GLUtils.getEGLErrorString(error));
            }
            return true;
        }

        @Override
        boolean validate() {
            return checkCurrent() != SURFACE_STATE_ERROR;
        }

        @Override
        void setup(int width, int height) {
            if (validate()) {
                mCanvas.setViewport(width, height);
                mWidth = width;
                mHeight = height;
            }
        }

        @Override
        int getWidth() {
            return mWidth;
        }

        @Override
        int getHeight() {
            return mHeight;
        }

        boolean canDraw() {
            return mGl != null && mCanvas != null;
        }        
        
        void onPreDraw(Rect dirty) {
        }

        void onPostDraw() {
        }

        @Override
        boolean draw(View view, View.AttachInfo attachInfo, HardwareDrawCallbacks callbacks,
                Rect dirty) {
            if (canDraw()) {
                if (!hasDirtyRegions()) {
                    dirty = null;
                }
                attachInfo.mIgnoreDirtyState = true;
                attachInfo.mDrawingTime = SystemClock.uptimeMillis();

                view.mPrivateFlags |= View.DRAWN;

                final int surfaceState = checkCurrent();
                if (surfaceState != SURFACE_STATE_ERROR) {
                    // We had to change the current surface and/or context, redraw everything
                    if (surfaceState == SURFACE_STATE_UPDATED) {
                        dirty = null;
                    }

                    onPreDraw(dirty);

                    HardwareCanvas canvas = mCanvas;
                    attachInfo.mHardwareCanvas = canvas;

                    int saveCount = canvas.save();
                    callbacks.onHardwarePreDraw(canvas);

                    try {
                        view.mRecreateDisplayList =
                                (view.mPrivateFlags & View.INVALIDATED) == View.INVALIDATED;
                        view.mPrivateFlags &= ~View.INVALIDATED;

                        DisplayList displayList = view.getDisplayList();
                        if (displayList != null) {
                            if (canvas.drawDisplayList(displayList, view.getWidth(),
                                    view.getHeight(), mRedrawClip)) {
                                if (mRedrawClip.isEmpty() || view.getParent() == null) {
                                    view.invalidate();
                                } else {
                                    view.getParent().invalidateChild(view, mRedrawClip);
                                }
                                mRedrawClip.setEmpty();
                            }
                        } else {
                            // Shouldn't reach here
                            view.draw(canvas);
                        }

                        if (DEBUG_DIRTY_REGION) {
                            if (mDebugPaint == null) {
                                mDebugPaint = new Paint();
                                mDebugPaint.setColor(0x7fff0000);
                            }
                            if (dirty != null && (mFrameCount++ & 1) == 0) {
                                canvas.drawRect(dirty, mDebugPaint);
                            }
                        }
                    } finally {
                        callbacks.onHardwarePostDraw(canvas);
                        canvas.restoreToCount(saveCount);
                        view.mRecreateDisplayList = false;
                    }

                    onPostDraw();

                    attachInfo.mIgnoreDirtyState = false;

                    sEgl.eglSwapBuffers(sEglDisplay, mEglSurface);
                    checkEglErrors();

                    return dirty == null;
                }
            }

            return false;
        }

        /**
         * Ensures the current EGL context is the one we expect.
         * 
         * @return {@link #SURFACE_STATE_ERROR} if the correct EGL context cannot be made current,
         *         {@link #SURFACE_STATE_UPDATED} if the EGL context was changed or
         *         {@link #SURFACE_STATE_SUCCESS} if the EGL context was the correct one
         */
        int checkCurrent() {
            if (mEglThread != Thread.currentThread()) {
                throw new IllegalStateException("Hardware acceleration can only be used with a " +
                        "single UI thread.\nOriginal thread: " + mEglThread + "\n" +
                        "Current thread: " + Thread.currentThread());
            }

            if (!mEglContext.equals(sEgl.eglGetCurrentContext()) ||
                    !mEglSurface.equals(sEgl.eglGetCurrentSurface(EGL_DRAW))) {
                if (!sEgl.eglMakeCurrent(sEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                    Log.e(LOG_TAG, "eglMakeCurrent failed " +
                            GLUtils.getEGLErrorString(sEgl.eglGetError()));
                    fallback(true);
                    return SURFACE_STATE_ERROR;
                } else {
                    return SURFACE_STATE_UPDATED;
                }
            }
            return SURFACE_STATE_SUCCESS;
        }
    }

    /**
     * Hardware renderer using OpenGL ES 2.0.
     */
    static class Gl20Renderer extends GlRenderer {
        private GLES20Canvas mGlCanvas;

        private static EGLSurface sPbuffer;
        private static final Object[] sPbufferLock = new Object[0];

        static class Gl20RendererEglContext extends ManagedEGLContext {
            final Handler mHandler = new Handler();

            public Gl20RendererEglContext(EGLContext context) {
                super(context);
            }

            @Override
            public void onTerminate(final EGLContext eglContext) {
                // Make sure we do this on the correct thread.
                if (mHandler.getLooper() != Looper.myLooper()) {
                    mHandler.post(new Runnable() {
                        @Override public void run() {
                            onTerminate(eglContext);
                        }
                    });
                    return;
                }

                synchronized (sEglLock) {
                    if (sEgl == null) return;

                    if (EGLImpl.getInitCount(sEglDisplay) == 1) {
                        usePbufferSurface(eglContext);
                        GLES20Canvas.terminateCaches();

                        sEgl.eglDestroyContext(sEglDisplay, eglContext);
                        sEglContextStorage.remove();

                        sEgl.eglDestroySurface(sEglDisplay, sPbuffer);
                        sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE,
                                EGL_NO_SURFACE, EGL_NO_CONTEXT);

                        sEgl.eglReleaseThread();
                        sEgl.eglTerminate(sEglDisplay);

                        sEgl = null;
                        sEglDisplay = null;
                        sEglConfig = null;
                        sPbuffer = null;
                        sEglContextStorage.set(null);
                    }
                }
            }
        }

        Gl20Renderer(boolean translucent) {
            super(2, translucent);
        }

        @Override
        GLES20Canvas createCanvas() {
            return mGlCanvas = new GLES20Canvas(mTranslucent);
        }

        @Override
        int[] getConfig(boolean dirtyRegions) {
            return new int[] {
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_ALPHA_SIZE, 8,
                    EGL_DEPTH_SIZE, 0,
                    EGL_STENCIL_SIZE, 0,
                    EGL_SURFACE_TYPE, EGL_WINDOW_BIT |
                            (dirtyRegions ? EGL_SWAP_BEHAVIOR_PRESERVED_BIT : 0),
                    EGL_NONE
            };
        }
        
        @Override
        void initCaches() {
            GLES20Canvas.initCaches();
        }

        @Override
        boolean canDraw() {
            return super.canDraw() && mGlCanvas != null;
        }                

        @Override
        void onPreDraw(Rect dirty) {
            mGlCanvas.onPreDraw(dirty);
        }

        @Override
        void onPostDraw() {
            mGlCanvas.onPostDraw();
        }

        @Override
        void destroy(boolean full) {
            try {
                super.destroy(full);
            } finally {
                if (full && mGlCanvas != null) {
                    mGlCanvas = null;
                }
            }
        }

        @Override
        void setup(int width, int height) {
            super.setup(width, height);
            if (mVsyncDisabled) {
                GLES20Canvas.disableVsync();
            }
        }

        @Override
        DisplayList createDisplayList() {
            return new GLES20DisplayList();
        }

        @Override
        HardwareLayer createHardwareLayer(boolean isOpaque) {
            return new GLES20TextureLayer(isOpaque);
        }

        @Override
        HardwareLayer createHardwareLayer(int width, int height, boolean isOpaque) {
            return new GLES20RenderLayer(width, height, isOpaque);
        }

        @Override
        SurfaceTexture createSurfaceTexture(HardwareLayer layer) {
            return ((GLES20TextureLayer) layer).getSurfaceTexture();
        }

        @Override
        void destroyLayers(View view) {
            if (view != null && isEnabled() && checkCurrent() != SURFACE_STATE_ERROR) {
                destroyHardwareLayer(view);
                GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_LAYERS);
            }
        }

        private static void destroyHardwareLayer(View view) {
            view.destroyLayer();

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;

                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    destroyHardwareLayer(group.getChildAt(i));
                }
            }
        }
        
        @Override
        void destroyHardwareResources(View view) {
            if (view != null) {
                boolean needsContext = true;
                if (isEnabled() && checkCurrent() != SURFACE_STATE_ERROR) needsContext = false;

                if (needsContext) {
                    Gl20RendererEglContext managedContext = sEglContextStorage.get();
                    if (managedContext == null) return;
                    usePbufferSurface(managedContext.getContext());
                }

                destroyResources(view);
                GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_LAYERS);
            }
        }
        
        private static void destroyResources(View view) {
            view.destroyHardwareResources();

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;

                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    destroyResources(group.getChildAt(i));
                }
            }
        }

        static HardwareRenderer create(boolean translucent) {
            if (GLES20Canvas.isAvailable()) {
                return new Gl20Renderer(translucent);
            }
            return null;
        }

        static void trimMemory(int level) {
            if (sEgl == null || sEglConfig == null) return;

            Gl20RendererEglContext managedContext = sEglContextStorage.get();
            // We do not have OpenGL objects
            if (managedContext == null) {
                return;
            } else {
                usePbufferSurface(managedContext.getContext());
            }

            switch (level) {
                case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                    GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_MODERATE);
                    break;
                case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                    GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_FULL);
                    break;
            }
        }

        private static void usePbufferSurface(EGLContext eglContext) {
            synchronized (sPbufferLock) {
                // Create a temporary 1x1 pbuffer so we have a context
                // to clear our OpenGL objects
                if (sPbuffer == null) {
                    sPbuffer = sEgl.eglCreatePbufferSurface(sEglDisplay, sEglConfig, new int[] {
                            EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE
                    });
                }
            }
            sEgl.eglMakeCurrent(sEglDisplay, sPbuffer, sPbuffer, eglContext);
        }
    }
}
