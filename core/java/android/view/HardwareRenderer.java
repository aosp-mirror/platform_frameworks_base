/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLUtils;
import android.opengl.ManagedEGLContext;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface.OutOfResourcesException;

import com.google.android.gles_jni.EGLImpl;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

import java.io.File;
import java.io.PrintWriter;
import java.util.concurrent.locks.ReentrantLock;

import static javax.microedition.khronos.egl.EGL10.*;

/**
 * Interface for rendering a view hierarchy using hardware acceleration.
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
    static final boolean RENDER_DIRTY_REGIONS = true;

    /**
     * System property used to enable or disable dirty regions invalidation.
     * This property is only queried if {@link #RENDER_DIRTY_REGIONS} is true.
     * The default value of this property is assumed to be true.
     *
     * Possible values:
     * "true", to enable partial invalidates
     * "false", to disable partial invalidates
     */
    static final String RENDER_DIRTY_REGIONS_PROPERTY = "debug.hwui.render_dirty_regions";

    /**
     * System property used to enable or disable hardware rendering profiling.
     * The default value of this property is assumed to be false.
     *
     * When profiling is enabled, the adb shell dumpsys gfxinfo command will
     * output extra information about the time taken to execute by the last
     * frames.
     *
     * Possible values:
     * "true", to enable profiling
     * "visual_bars", to enable profiling and visualize the results on screen
     * "visual_lines", to enable profiling and visualize the results on screen
     * "false", to disable profiling
     *
     * @see #PROFILE_PROPERTY_VISUALIZE_BARS
     * @see #PROFILE_PROPERTY_VISUALIZE_LINES
     *
     * @hide
     */
    public static final String PROFILE_PROPERTY = "debug.hwui.profile";

    /**
     * Value for {@link #PROFILE_PROPERTY}. When the property is set to this
     * value, profiling data will be visualized on screen as a bar chart.
     *
     * @hide
     */
    public static final String PROFILE_PROPERTY_VISUALIZE_BARS = "visual_bars";

    /**
     * Value for {@link #PROFILE_PROPERTY}. When the property is set to this
     * value, profiling data will be visualized on screen as a line chart.
     *
     * @hide
     */
    public static final String PROFILE_PROPERTY_VISUALIZE_LINES = "visual_lines";

    /**
     * System property used to specify the number of frames to be used
     * when doing hardware rendering profiling.
     * The default value of this property is #PROFILE_MAX_FRAMES.
     *
     * When profiling is enabled, the adb shell dumpsys gfxinfo command will
     * output extra information about the time taken to execute by the last
     * frames.
     *
     * Possible values:
     * "60", to set the limit of frames to 60
     */
    static final String PROFILE_MAXFRAMES_PROPERTY = "debug.hwui.profile.maxframes";

    /**
     * System property used to debug EGL configuration choice.
     *
     * Possible values:
     * "choice", print the chosen configuration only
     * "all", print all possible configurations
     */
    static final String PRINT_CONFIG_PROPERTY = "debug.hwui.print_config";

    /**
     * Turn on to draw dirty regions every other frame.
     *
     * Possible values:
     * "true", to enable dirty regions debugging
     * "false", to disable dirty regions debugging
     *
     * @hide
     */
    public static final String DEBUG_DIRTY_REGIONS_PROPERTY = "debug.hwui.show_dirty_regions";

    /**
     * Turn on to flash hardware layers when they update.
     *
     * Possible values:
     * "true", to enable hardware layers updates debugging
     * "false", to disable hardware layers updates debugging
     *
     * @hide
     */
    public static final String DEBUG_SHOW_LAYERS_UPDATES_PROPERTY =
            "debug.hwui.show_layers_updates";

    /**
     * Controls overdraw debugging.
     *
     * Possible values:
     * "false", to disable overdraw debugging
     * "show", to show overdraw areas on screen
     * "count", to display an overdraw counter
     *
     * @hide
     */
    public static final String DEBUG_OVERDRAW_PROPERTY = "debug.hwui.overdraw";

    /**
     * Value for {@link #DEBUG_OVERDRAW_PROPERTY}. When the property is set to this
     * value, overdraw will be shown on screen by coloring pixels.
     *
     * @hide
     */
    public static final String OVERDRAW_PROPERTY_SHOW = "show";

    /**
     * Value for {@link #DEBUG_OVERDRAW_PROPERTY}. When the property is set to this
     * value, an overdraw counter will be shown on screen.
     *
     * @hide
     */
    public static final String OVERDRAW_PROPERTY_COUNT = "count";

    /**
     * Turn on to debug non-rectangular clip operations.
     *
     * Possible values:
     * "hide", to disable this debug mode
     * "highlight", highlight drawing commands tested against a non-rectangular clip
     * "stencil", renders the clip region on screen when set
     *
     * @hide
     */
    public static final String DEBUG_SHOW_NON_RECTANGULAR_CLIP_PROPERTY =
            "debug.hwui.show_non_rect_clip";

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

    /**
     * Number of frames to profile.
     */
    private static final int PROFILE_MAX_FRAMES = 128;

    /**
     * Number of floats per profiled frame.
     */
    private static final int PROFILE_FRAME_DATA_COUNT = 3;

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
     * @param surface The surface to hardware accelerate
     *
     * @return True if the initialization was successful, false otherwise.
     */
    abstract boolean initialize(Surface surface) throws OutOfResourcesException;

    /**
     * Updates the hardware renderer for the specified surface.
     *
     * @param surface The surface to hardware accelerate
     */
    abstract void updateSurface(Surface surface) throws OutOfResourcesException;

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
     * @param surface The surface to hardware accelerate
     */
    abstract void invalidate(Surface surface);

    /**
     * This method should be invoked to ensure the hardware renderer is in
     * valid state (for instance, to ensure the correct EGL context is bound
     * to the current thread.)
     *
     * @return true if the renderer is now valid, false otherwise
     */
    abstract boolean validate();

    /**
     * This method ensures the hardware renderer is in a valid state
     * before executing the specified action.
     *
     * This method will attempt to set a valid state even if the window
     * the renderer is attached to was destroyed.
     *
     * @return true if the action was run
     */
    abstract boolean safelyRun(Runnable action);

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
     * Gets the current canvas associated with this HardwareRenderer.
     *
     * @return the current HardwareCanvas
     */
    abstract HardwareCanvas getCanvas();

    /**
     * Outputs extra debugging information in the specified file descriptor.
     * @param pw
     */
    abstract void dumpGfxInfo(PrintWriter pw);

    /**
     * Outputs the total number of frames rendered (used for fps calculations)
     *
     * @return the number of frames rendered
     */
    abstract long getFrameCount();

    /**
     * Loads system properties used by the renderer. This method is invoked
     * whenever system properties are modified. Implementations can use this
     * to trigger live updates of the renderer based on properties.
     *
     * @param surface The surface to update with the new properties.
     *                Can be null.
     *
     * @return True if a property has changed.
     */
    abstract boolean loadSystemProperties(Surface surface);

    private static native boolean nLoadProperties();

    /**
     * Sets the directory to use as a persistent storage for hardware rendering
     * resources.
     *
     * @param cacheDir A directory the current process can write to
     *
     * @hide
     */
    public static void setupDiskCache(File cacheDir) {
        nSetupShadersDiskCache(new File(cacheDir, CACHE_PATH_SHADERS).getAbsolutePath());
    }

    private static native void nSetupShadersDiskCache(String cacheFile);

    /**
     * Notifies EGL that the frame is about to be rendered.
     * @param size
     */
    static void beginFrame(int[] size) {
        nBeginFrame(size);
    }

    private static native void nBeginFrame(int[] size);

    /**
     * Returns the current system time according to the renderer.
     * This method is used for debugging only and should not be used
     * as a clock.
     */
    static long getSystemTime() {
        return nGetSystemTime();
    }

    private static native long nGetSystemTime();

    /**
     * Preserves the back buffer of the current surface after a buffer swap.
     * Calling this method sets the EGL_SWAP_BEHAVIOR attribute of the current
     * surface to EGL_BUFFER_PRESERVED. Calling this method requires an EGL
     * config that supports EGL_SWAP_BEHAVIOR_PRESERVED_BIT.
     *
     * @return True if the swap behavior was successfully changed,
     *         false otherwise.
     */
    static boolean preserveBackBuffer() {
        return nPreserveBackBuffer();
    }

    private static native boolean nPreserveBackBuffer();

    /**
     * Indicates whether the current surface preserves its back buffer
     * after a buffer swap.
     *
     * @return True, if the surface's EGL_SWAP_BEHAVIOR is EGL_BUFFER_PRESERVED,
     *         false otherwise
     */
    static boolean isBackBufferPreserved() {
        return nIsBackBufferPreserved();
    }

    private static native boolean nIsBackBufferPreserved();

    /**
     * Indicates that the specified hardware layer needs to be updated
     * as soon as possible.
     *
     * @param layer The hardware layer that needs an update
     *
     * @see #flushLayerUpdates()
     * @see #cancelLayerUpdate(HardwareLayer)
     */
    abstract void pushLayerUpdate(HardwareLayer layer);

    /**
     * Cancels a queued layer update. If the specified layer was not
     * queued for update, this method has no effect.
     *
     * @param layer The layer whose update to cancel
     *
     * @see #pushLayerUpdate(HardwareLayer)
     */
    abstract void cancelLayerUpdate(HardwareLayer layer);

    /**
     * Forces all enqueued layer updates to be executed immediately.
     *
     * @see #pushLayerUpdate(HardwareLayer)
     */
    abstract void flushLayerUpdates();

    /**
     * Interface used to receive callbacks whenever a view is drawn by
     * a hardware renderer instance.
     */
    interface HardwareDrawCallbacks {
        /**
         * Invoked before a view is drawn by a hardware renderer.
         * This method can be used to apply transformations to the
         * canvas but no drawing command should be issued.
         *
         * @param canvas The Canvas used to render the view.
         */
        void onHardwarePreDraw(HardwareCanvas canvas);

        /**
         * Invoked after a view is drawn by a hardware renderer.
         * It is safe to invoke drawing commands from this method.
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
     */
    abstract void draw(View view, View.AttachInfo attachInfo, HardwareDrawCallbacks callbacks,
            Rect dirty);

    /**
     * Creates a new display list that can be used to record batches of
     * drawing operations.
     *
     * @param name The name of the display list, used for debugging purpose. May be null.
     *
     * @return A new display list.
     *
     * @hide
     */
    public abstract DisplayList createDisplayList(String name);

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
     * @param layer The layer to render into using a {@link android.graphics.SurfaceTexture}
     *
     * @return A {@link SurfaceTexture}
     */
    abstract SurfaceTexture createSurfaceTexture(HardwareLayer layer);

    /**
     * Sets the {@link android.graphics.SurfaceTexture} that will be used to
     * render into the specified hardware layer.
     *
     * @param layer The layer to render into using a {@link android.graphics.SurfaceTexture}
     * @param surfaceTexture The {@link android.graphics.SurfaceTexture} to use for the layer
     */
    abstract void setSurfaceTexture(HardwareLayer layer, SurfaceTexture surfaceTexture);

    /**
     * Detaches the specified functor from the current functor execution queue.
     *
     * @param functor The native functor to remove from the execution queue.
     *
     * @see HardwareCanvas#callDrawGLFunction(int)
     * @see #attachFunctor(android.view.View.AttachInfo, int)
     */
    abstract void detachFunctor(int functor);

    /**
     * Schedules the specified functor in the functors execution queue.
     *
     * @param attachInfo AttachInfo tied to this renderer.
     * @param functor The native functor to insert in the execution queue.
     *
     * @see HardwareCanvas#callDrawGLFunction(int)
     * @see #detachFunctor(int)
     *
     * @return true if the functor was attached successfully
     */
    abstract boolean attachFunctor(View.AttachInfo attachInfo, int functor);

    /**
     * Initializes the hardware renderer for the specified surface and setup the
     * renderer for drawing, if needed. This is invoked when the ViewAncestor has
     * potentially lost the hardware renderer. The hardware renderer should be
     * reinitialized and setup when the render {@link #isRequested()} and
     * {@link #isEnabled()}.
     *
     * @param width The width of the drawing surface.
     * @param height The height of the drawing surface.
     * @param surface The surface to hardware accelerate
     *
     * @return true if the surface was initialized, false otherwise. Returning
     *         false might mean that the surface was already initialized.
     */
    boolean initializeIfNeeded(int width, int height, Surface surface)
            throws OutOfResourcesException {
        if (isRequested()) {
            // We lost the gl context, so recreate it.
            if (!isEnabled()) {
                if (initialize(surface)) {
                    setup(width, height);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Optional, sets the name of the renderer. Useful for debugging purposes.
     *
     * @param name The name of this renderer, can be null
     */
    abstract void setName(String name);

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
        startTrimMemory(level);
        endTrimMemory();
    }

    /**
     * Starts the process of trimming memory. Usually this call will setup
     * hardware rendering context and reclaim memory.Extra cleanup might
     * be required by calling {@link #endTrimMemory()}.
     *
     * @param level Hint about the amount of memory that should be trimmed,
     *              see {@link android.content.ComponentCallbacks}
     */
    static void startTrimMemory(int level) {
        Gl20Renderer.startTrimMemory(level);
    }

    /**
     * Finishes the process of trimming memory. This method will usually
     * cleanup special resources used by the memory trimming process.
     */
    static void endTrimMemory() {
        Gl20Renderer.endTrimMemory();
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

    /**
     * Describes a series of frames that should be drawn on screen as a graph.
     * Each frame is composed of 1 or more elements.
     */
    abstract class GraphDataProvider {
        /**
         * Draws the graph as bars. Frame elements are stacked on top of
         * each other.
         */
        public static final int GRAPH_TYPE_BARS = 0;
        /**
         * Draws the graph as lines. The number of series drawn corresponds
         * to the number of elements.
         */
        public static final int GRAPH_TYPE_LINES = 1;

        /**
         * Returns the type of graph to render.
         *
         * @return {@link #GRAPH_TYPE_BARS} or {@link #GRAPH_TYPE_LINES}
         */
        abstract int getGraphType();

        /**
         * This method is invoked before the graph is drawn. This method
         * can be used to compute sizes, etc.
         *
         * @param metrics The display metrics
         */
        abstract void prepare(DisplayMetrics metrics);

        /**
         * @return The size in pixels of a vertical unit.
         */
        abstract int getVerticalUnitSize();

        /**
         * @return The size in pixels of a horizontal unit.
         */
        abstract int getHorizontalUnitSize();

        /**
         * @return The size in pixels of the margin between horizontal units.
         */
        abstract int getHorizontaUnitMargin();

        /**
         * An optional threshold value.
         *
         * @return A value >= 0 to draw the threshold, a negative value
         *         to ignore it.
         */
        abstract float getThreshold();

        /**
         * The data to draw in the graph. The number of elements in the
         * array must be at least {@link #getFrameCount()} * {@link #getElementCount()}.
         * If a value is negative the following values will be ignored.
         */
        abstract float[] getData();

        /**
         * Returns the number of frames to render in the graph.
         */
        abstract int getFrameCount();

        /**
         * Returns the number of elements in each frame. This directly affects
         * the number of series drawn in the graph.
         */
        abstract int getElementCount();

        /**
         * Returns the current frame, if any. If the returned value is negative
         * the current frame is ignored.
         */
        abstract int getCurrentFrame();

        /**
         * Prepares the paint to draw the specified element (or series.)
         */
        abstract void setupGraphPaint(Paint paint, int elementIndex);

        /**
         * Prepares the paint to draw the threshold.
         */
        abstract void setupThresholdPaint(Paint paint);

        /**
         * Prepares the paint to draw the current frame indicator.
         */
        abstract void setupCurrentFramePaint(Paint paint);
    }

    @SuppressWarnings({"deprecation"})
    static abstract class GlRenderer extends HardwareRenderer {
        static final int SURFACE_STATE_ERROR = 0;
        static final int SURFACE_STATE_SUCCESS = 1;
        static final int SURFACE_STATE_UPDATED = 2;

        static final int FUNCTOR_PROCESS_DELAY = 4;

        private static final int PROFILE_DRAW_MARGIN = 0;
        private static final int PROFILE_DRAW_WIDTH = 3;
        private static final int[] PROFILE_DRAW_COLORS = { 0xcf3e66cc, 0xcfdc3912, 0xcfe69800 };
        private static final int PROFILE_DRAW_CURRENT_FRAME_COLOR = 0xcf5faa4d;
        private static final int PROFILE_DRAW_THRESHOLD_COLOR = 0xff5faa4d;
        private static final int PROFILE_DRAW_THRESHOLD_STROKE_WIDTH = 2;
        private static final int PROFILE_DRAW_DP_PER_MS = 7;

        private static final String[] VISUALIZERS = {
                PROFILE_PROPERTY_VISUALIZE_BARS,
                PROFILE_PROPERTY_VISUALIZE_LINES
        };

        private static final String[] OVERDRAW = {
                OVERDRAW_PROPERTY_SHOW,
                OVERDRAW_PROPERTY_COUNT
        };
        private static final int OVERDRAW_TYPE_COUNT = 1;

        static EGL10 sEgl;
        static EGLDisplay sEglDisplay;
        static EGLConfig sEglConfig;
        static final Object[] sEglLock = new Object[0];
        int mWidth = -1, mHeight = -1;

        static final ThreadLocal<ManagedEGLContext> sEglContextStorage
                = new ThreadLocal<ManagedEGLContext>();

        EGLContext mEglContext;
        Thread mEglThread;

        EGLSurface mEglSurface;

        GL mGl;
        HardwareCanvas mCanvas;

        String mName;

        long mFrameCount;
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
        boolean mUpdateDirtyRegions;

        boolean mProfileEnabled;
        int mProfileVisualizerType = -1;
        float[] mProfileData;
        ReentrantLock mProfileLock;
        int mProfileCurrentFrame = -PROFILE_FRAME_DATA_COUNT;

        GraphDataProvider mDebugDataProvider;
        float[][] mProfileShapes;
        Paint mProfilePaint;

        boolean mDebugDirtyRegions;
        int mDebugOverdraw = -1;
        HardwareLayer mDebugOverdrawLayer;
        Paint mDebugOverdrawPaint;

        final int mGlVersion;
        final boolean mTranslucent;

        private boolean mDestroyed;

        private final Rect mRedrawClip = new Rect();

        private final int[] mSurfaceSize = new int[2];
        private final FunctorsRunnable mFunctorsRunnable = new FunctorsRunnable();

        private long mDrawDelta = Long.MAX_VALUE;

        GlRenderer(int glVersion, boolean translucent) {
            mGlVersion = glVersion;
            mTranslucent = translucent;

            loadSystemProperties(null);
        }

        @Override
        boolean loadSystemProperties(Surface surface) {
            boolean value;
            boolean changed = false;

            String profiling = SystemProperties.get(PROFILE_PROPERTY);
            int graphType = search(VISUALIZERS, profiling);
            value = graphType >= 0;

            if (graphType != mProfileVisualizerType) {
                changed = true;
                mProfileVisualizerType = graphType;

                mProfileShapes = null;
                mProfilePaint = null;

                if (value) {
                    mDebugDataProvider = new DrawPerformanceDataProvider(graphType);
                } else {
                    mDebugDataProvider = null;
                }
            }

            // If on-screen profiling is not enabled, we need to check whether
            // console profiling only is enabled
            if (!value) {
                value = Boolean.parseBoolean(profiling);
            }

            if (value != mProfileEnabled) {
                changed = true;
                mProfileEnabled = value;

                if (mProfileEnabled) {
                    Log.d(LOG_TAG, "Profiling hardware renderer");

                    int maxProfileFrames = SystemProperties.getInt(PROFILE_MAXFRAMES_PROPERTY,
                            PROFILE_MAX_FRAMES);
                    mProfileData = new float[maxProfileFrames * PROFILE_FRAME_DATA_COUNT];
                    for (int i = 0; i < mProfileData.length; i += PROFILE_FRAME_DATA_COUNT) {
                        mProfileData[i] = mProfileData[i + 1] = mProfileData[i + 2] = -1;
                    }

                    mProfileLock = new ReentrantLock();
                } else {
                    mProfileData = null;
                    mProfileLock = null;
                    mProfileVisualizerType = -1;
                }

                mProfileCurrentFrame = -PROFILE_FRAME_DATA_COUNT;
            }

            value = SystemProperties.getBoolean(DEBUG_DIRTY_REGIONS_PROPERTY, false);
            if (value != mDebugDirtyRegions) {
                changed = true;
                mDebugDirtyRegions = value;

                if (mDebugDirtyRegions) {
                    Log.d(LOG_TAG, "Debugging dirty regions");
                }
            }

            String overdraw = SystemProperties.get(HardwareRenderer.DEBUG_OVERDRAW_PROPERTY);
            int debugOverdraw = search(OVERDRAW, overdraw);
            if (debugOverdraw != mDebugOverdraw) {
                changed = true;
                mDebugOverdraw = debugOverdraw;

                if (mDebugOverdraw != OVERDRAW_TYPE_COUNT) {
                    if (mDebugOverdrawLayer != null) {
                        mDebugOverdrawLayer.destroy();
                        mDebugOverdrawLayer = null;
                        mDebugOverdrawPaint = null;
                    }
                }
            }

            if (nLoadProperties()) {
                changed = true;
            }

            return changed;
        }

        private static int search(String[] values, String value) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) return i;
            }
            return -1;
        }

        @Override
        void dumpGfxInfo(PrintWriter pw) {
            if (mProfileEnabled) {
                pw.printf("\n\tDraw\tProcess\tExecute\n");

                mProfileLock.lock();
                try {
                    for (int i = 0; i < mProfileData.length; i += PROFILE_FRAME_DATA_COUNT) {
                        if (mProfileData[i] < 0) {
                            break;
                        }
                        pw.printf("\t%3.2f\t%3.2f\t%3.2f\n", mProfileData[i], mProfileData[i + 1],
                                mProfileData[i + 2]);
                        mProfileData[i] = mProfileData[i + 1] = mProfileData[i + 2] = -1;
                    }
                    mProfileCurrentFrame = mProfileData.length;
                } finally {
                    mProfileLock.unlock();
                }
            }
        }

        @Override
        long getFrameCount() {
            return mFrameCount;
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
                checkEglErrorsForced();
            }
        }

        private void checkEglErrorsForced() {
            int error = sEgl.eglGetError();
            if (error != EGL_SUCCESS) {
                // something bad has happened revert to
                // normal rendering.
                Log.w(LOG_TAG, "EGL error: " + GLUtils.getEGLErrorString(error));
                fallback(error != EGL11.EGL_CONTEXT_LOST);
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
        boolean initialize(Surface surface) throws OutOfResourcesException {
            if (isRequested() && !isEnabled()) {
                boolean contextCreated = initializeEgl();
                mGl = createEglSurface(surface);
                mDestroyed = false;

                if (mGl != null) {
                    int err = sEgl.eglGetError();
                    if (err != EGL_SUCCESS) {
                        destroy(true);
                        setRequested(false);
                    } else {
                        if (mCanvas == null) {
                            mCanvas = createCanvas();
                            mCanvas.setName(mName);
                        }
                        setEnabled(true);

                        if (contextCreated) {
                            initAtlas();
                        }
                    }

                    return mCanvas != null;
                }
            }
            return false;
        }

        @Override
        void updateSurface(Surface surface) throws OutOfResourcesException {
            if (isRequested() && isEnabled()) {
                createEglSurface(surface);
            }
        }

        abstract HardwareCanvas createCanvas();

        abstract int[] getConfig(boolean dirtyRegions);

        boolean initializeEgl() {
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

                    checkEglErrorsForced();

                    sEglConfig = loadEglConfig();
                }
            }

            ManagedEGLContext managedContext = sEglContextStorage.get();
            mEglContext = managedContext != null ? managedContext.getContext() : null;
            mEglThread = Thread.currentThread();

            if (mEglContext == null) {
                mEglContext = createContext(sEgl, sEglDisplay, sEglConfig);
                sEglContextStorage.set(createManagedContext(mEglContext));
                return true;
            }

            return false;
        }

        private EGLConfig loadEglConfig() {
            EGLConfig eglConfig = chooseEglConfig();
            if (eglConfig == null) {
                // We tried to use EGL_SWAP_BEHAVIOR_PRESERVED_BIT, try again without
                if (sDirtyRegions) {
                    sDirtyRegions = false;
                    eglConfig = chooseEglConfig();
                    if (eglConfig == null) {
                        throw new RuntimeException("eglConfig not initialized");
                    }
                } else {
                    throw new RuntimeException("eglConfig not initialized");
                }
            }
            return eglConfig;
        }

        abstract ManagedEGLContext createManagedContext(EGLContext eglContext);

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

        private static void printConfig(EGLConfig config) {
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

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_SAMPLE_BUFFERS, value);
            Log.d(LOG_TAG, "  SAMPLE_BUFFERS = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_SAMPLES, value);
            Log.d(LOG_TAG, "  SAMPLES = " + value[0]);

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_SURFACE_TYPE, value);
            Log.d(LOG_TAG, "  SURFACE_TYPE = 0x" + Integer.toHexString(value[0]));

            sEgl.eglGetConfigAttrib(sEglDisplay, config, EGL_CONFIG_CAVEAT, value);
            Log.d(LOG_TAG, "  CONFIG_CAVEAT = 0x" + Integer.toHexString(value[0]));
        }

        GL createEglSurface(Surface surface) throws OutOfResourcesException {
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
            if (!createSurface(surface)) {
                return null;
            }

            initCaches();

            return mEglContext.getGL();
        }

        private void enableDirtyRegions() {
            // If mDirtyRegions is set, this means we have an EGL configuration
            // with EGL_SWAP_BEHAVIOR_PRESERVED_BIT set
            if (sDirtyRegions) {
                if (!(mDirtyRegionsEnabled = preserveBackBuffer())) {
                    Log.w(LOG_TAG, "Backbuffer cannot be preserved");
                }
            } else if (sDirtyRegionsRequested) {
                // If mDirtyRegions is not set, our EGL configuration does not
                // have EGL_SWAP_BEHAVIOR_PRESERVED_BIT; however, the default
                // swap behavior might be EGL_BUFFER_PRESERVED, which means we
                // want to set mDirtyRegions. We try to do this only if dirty
                // regions were initially requested as part of the device
                // configuration (see RENDER_DIRTY_REGIONS)
                mDirtyRegionsEnabled = isBackBufferPreserved();
            }
        }

        abstract void initCaches();
        abstract void initAtlas();

        EGLContext createContext(EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig) {
            int[] attribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, mGlVersion, EGL_NONE };

            EGLContext context = egl.eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT,
                    mGlVersion != 0 ? attribs : null);
            if (context == null || context == EGL_NO_CONTEXT) {
                //noinspection ConstantConditions
                throw new IllegalStateException(
                        "Could not create an EGL context. eglCreateContext failed with error: " +
                        GLUtils.getEGLErrorString(sEgl.eglGetError()));
            }

            return context;
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
                if (mEglSurface.equals(sEgl.eglGetCurrentSurface(EGL_DRAW))) {
                    sEgl.eglMakeCurrent(sEglDisplay,
                            EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                }
                sEgl.eglDestroySurface(sEglDisplay, mEglSurface);
                mEglSurface = null;
            }
        }

        @Override
        void invalidate(Surface surface) {
            // Cancels any existing buffer to ensure we'll get a buffer
            // of the right size before we call eglSwapBuffers
            sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

            if (mEglSurface != null && mEglSurface != EGL_NO_SURFACE) {
                sEgl.eglDestroySurface(sEglDisplay, mEglSurface);
                mEglSurface = null;
                setEnabled(false);
            }

            if (surface.isValid()) {
                if (!createSurface(surface)) {
                    return;
                }

                mUpdateDirtyRegions = true;

                if (mCanvas != null) {
                    setEnabled(true);
                }
            }
        }

        private boolean createSurface(Surface surface) {
            mEglSurface = sEgl.eglCreateWindowSurface(sEglDisplay, sEglConfig, surface, null);

            if (mEglSurface == null || mEglSurface == EGL_NO_SURFACE) {
                int error = sEgl.eglGetError();
                if (error == EGL_BAD_NATIVE_WINDOW) {
                    Log.e(LOG_TAG, "createWindowSurface returned EGL_BAD_NATIVE_WINDOW.");
                    return false;
                }
                throw new RuntimeException("createWindowSurface failed "
                        + GLUtils.getEGLErrorString(error));
            }

            if (!sEgl.eglMakeCurrent(sEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                throw new IllegalStateException("eglMakeCurrent failed " +
                        GLUtils.getEGLErrorString(sEgl.eglGetError()));
            }

            enableDirtyRegions();

            return true;
        }

        @Override
        boolean validate() {
            return checkRenderContext() != SURFACE_STATE_ERROR;
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

        @Override
        HardwareCanvas getCanvas() {
            return mCanvas;
        }

        @Override
        void setName(String name) {
            mName = name;
        }

        boolean canDraw() {
            return mGl != null && mCanvas != null;
        }

        int onPreDraw(Rect dirty) {
            return DisplayList.STATUS_DONE;
        }

        void onPostDraw() {
        }

        class FunctorsRunnable implements Runnable {
            View.AttachInfo attachInfo;

            @Override
            public void run() {
                final HardwareRenderer renderer = attachInfo.mHardwareRenderer;
                if (renderer == null || !renderer.isEnabled() || renderer != GlRenderer.this) {
                    return;
                }

                if (checkRenderContext() != SURFACE_STATE_ERROR) {
                    int status = mCanvas.invokeFunctors(mRedrawClip);
                    handleFunctorStatus(attachInfo, status);
                }
            }
        }

        @Override
        void draw(View view, View.AttachInfo attachInfo, HardwareDrawCallbacks callbacks,
                Rect dirty) {
            if (canDraw()) {
                if (!hasDirtyRegions()) {
                    dirty = null;
                }
                attachInfo.mIgnoreDirtyState = true;
                attachInfo.mDrawingTime = SystemClock.uptimeMillis();

                view.mPrivateFlags |= View.PFLAG_DRAWN;

                // We are already on the correct thread
                final int surfaceState = checkRenderContextUnsafe();
                if (surfaceState != SURFACE_STATE_ERROR) {
                    HardwareCanvas canvas = mCanvas;
                    attachInfo.mHardwareCanvas = canvas;

                    if (mProfileEnabled) {
                        mProfileLock.lock();
                    }

                    dirty = beginFrame(canvas, dirty, surfaceState);

                    DisplayList displayList = buildDisplayList(view, canvas);

                    // buildDisplayList() calls into user code which can cause
                    // an eglMakeCurrent to happen with a different surface/context.
                    // We must therefore check again here.
                    if (checkRenderContextUnsafe() == SURFACE_STATE_ERROR) {
                        return;
                    }

                    int saveCount = 0;
                    int status = DisplayList.STATUS_DONE;

                    long start = getSystemTime();
                    try {
                        status = prepareFrame(dirty);

                        saveCount = canvas.save();
                        callbacks.onHardwarePreDraw(canvas);

                        if (displayList != null) {
                            status |= drawDisplayList(attachInfo, canvas, displayList, status);
                        } else {
                            // Shouldn't reach here
                            view.draw(canvas);
                        }
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "An error has occurred while drawing:", e);
                    } finally {
                        callbacks.onHardwarePostDraw(canvas);
                        canvas.restoreToCount(saveCount);
                        view.mRecreateDisplayList = false;

                        mDrawDelta = getSystemTime() - start;

                        if (mDrawDelta > 0) {
                            mFrameCount++;

                            debugOverdraw(attachInfo, dirty, canvas, displayList);
                            debugDirtyRegions(dirty, canvas);
                            drawProfileData(attachInfo);
                        }
                    }

                    onPostDraw();

                    swapBuffers(status);

                    if (mProfileEnabled) {
                        mProfileLock.unlock();
                    }

                    attachInfo.mIgnoreDirtyState = false;
                }
            }
        }

        abstract void countOverdraw(HardwareCanvas canvas);
        abstract float getOverdraw(HardwareCanvas canvas);

        private void debugOverdraw(View.AttachInfo attachInfo, Rect dirty,
                HardwareCanvas canvas, DisplayList displayList) {

            if (mDebugOverdraw == OVERDRAW_TYPE_COUNT) {
                if (mDebugOverdrawLayer == null) {
                    mDebugOverdrawLayer = createHardwareLayer(mWidth, mHeight, true);
                } else if (mDebugOverdrawLayer.getWidth() != mWidth ||
                        mDebugOverdrawLayer.getHeight() != mHeight) {
                    mDebugOverdrawLayer.resize(mWidth, mHeight);
                }

                if (!mDebugOverdrawLayer.isValid()) {
                    mDebugOverdraw = -1;
                    return;
                }

                HardwareCanvas layerCanvas = mDebugOverdrawLayer.start(canvas, dirty);
                countOverdraw(layerCanvas);
                final int restoreCount = layerCanvas.save();
                layerCanvas.drawDisplayList(displayList, null, DisplayList.FLAG_CLIP_CHILDREN);
                layerCanvas.restoreToCount(restoreCount);
                mDebugOverdrawLayer.end(canvas);

                float overdraw = getOverdraw(layerCanvas);
                DisplayMetrics metrics = attachInfo.mRootView.getResources().getDisplayMetrics();

                drawOverdrawCounter(canvas, overdraw, metrics.density);
            }
        }

        private void drawOverdrawCounter(HardwareCanvas canvas, float overdraw, float density) {
            final String text = String.format("%.2fx", overdraw);
            final Paint paint = setupPaint(density);
            // HSBtoColor will clamp the values in the 0..1 range
            paint.setColor(Color.HSBtoColor(0.28f - 0.28f * overdraw / 3.5f, 0.8f, 1.0f));

            canvas.drawText(text, density * 4.0f, mHeight - paint.getFontMetrics().bottom, paint);
        }

        private Paint setupPaint(float density) {
            if (mDebugOverdrawPaint == null) {
                mDebugOverdrawPaint = new Paint();
                mDebugOverdrawPaint.setAntiAlias(true);
                mDebugOverdrawPaint.setShadowLayer(density * 3.0f, 0.0f, 0.0f, 0xff000000);
                mDebugOverdrawPaint.setTextSize(density * 20.0f);
            }
            return mDebugOverdrawPaint;
        }

        private DisplayList buildDisplayList(View view, HardwareCanvas canvas) {
            if (mDrawDelta <= 0) {
                return view.mDisplayList;
            }

            view.mRecreateDisplayList = (view.mPrivateFlags & View.PFLAG_INVALIDATED)
                    == View.PFLAG_INVALIDATED;
            view.mPrivateFlags &= ~View.PFLAG_INVALIDATED;

            long buildDisplayListStartTime = startBuildDisplayListProfiling();
            canvas.clearLayerUpdates();

            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "getDisplayList");
            DisplayList displayList = view.getDisplayList();
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);

            endBuildDisplayListProfiling(buildDisplayListStartTime);

            return displayList;
        }

        abstract void drawProfileData(View.AttachInfo attachInfo);

        private Rect beginFrame(HardwareCanvas canvas, Rect dirty, int surfaceState) {
            // We had to change the current surface and/or context, redraw everything
            if (surfaceState == SURFACE_STATE_UPDATED) {
                dirty = null;
                beginFrame(null);
            } else {
                int[] size = mSurfaceSize;
                beginFrame(size);

                if (size[1] != mHeight || size[0] != mWidth) {
                    mWidth = size[0];
                    mHeight = size[1];

                    canvas.setViewport(mWidth, mHeight);

                    dirty = null;
                }
            }

            if (mDebugDataProvider != null) dirty = null;

            return dirty;
        }

        private long startBuildDisplayListProfiling() {
            if (mProfileEnabled) {
                mProfileCurrentFrame += PROFILE_FRAME_DATA_COUNT;
                if (mProfileCurrentFrame >= mProfileData.length) {
                    mProfileCurrentFrame = 0;
                }

                return System.nanoTime();
            }
            return 0;
        }

        private void endBuildDisplayListProfiling(long getDisplayListStartTime) {
            if (mProfileEnabled) {
                long now = System.nanoTime();
                float total = (now - getDisplayListStartTime) * 0.000001f;
                //noinspection PointlessArithmeticExpression
                mProfileData[mProfileCurrentFrame] = total;
            }
        }

        private int prepareFrame(Rect dirty) {
            int status;
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "prepareFrame");
            try {
                status = onPreDraw(dirty);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
            return status;
        }

        private int drawDisplayList(View.AttachInfo attachInfo, HardwareCanvas canvas,
                DisplayList displayList, int status) {

            long drawDisplayListStartTime = 0;
            if (mProfileEnabled) {
                drawDisplayListStartTime = System.nanoTime();
            }

            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "drawDisplayList");
            try {
                status |= canvas.drawDisplayList(displayList, mRedrawClip,
                        DisplayList.FLAG_CLIP_CHILDREN);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }

            if (mProfileEnabled) {
                long now = System.nanoTime();
                float total = (now - drawDisplayListStartTime) * 0.000001f;
                mProfileData[mProfileCurrentFrame + 1] = total;
            }

            handleFunctorStatus(attachInfo, status);
            return status;
        }

        private void swapBuffers(int status) {
            if ((status & DisplayList.STATUS_DREW) == DisplayList.STATUS_DREW) {
                long eglSwapBuffersStartTime = 0;
                if (mProfileEnabled) {
                    eglSwapBuffersStartTime = System.nanoTime();
                }

                sEgl.eglSwapBuffers(sEglDisplay, mEglSurface);

                if (mProfileEnabled) {
                    long now = System.nanoTime();
                    float total = (now - eglSwapBuffersStartTime) * 0.000001f;
                    mProfileData[mProfileCurrentFrame + 2] = total;
                }

                checkEglErrors();
            }
        }

        private void debugDirtyRegions(Rect dirty, HardwareCanvas canvas) {
            if (mDebugDirtyRegions) {
                if (mDebugPaint == null) {
                    mDebugPaint = new Paint();
                    mDebugPaint.setColor(0x7fff0000);
                }

                if (dirty != null && (mFrameCount & 1) == 0) {
                    canvas.drawRect(dirty, mDebugPaint);
                }
            }
        }

        private void handleFunctorStatus(View.AttachInfo attachInfo, int status) {
            // If the draw flag is set, functors will be invoked while executing
            // the tree of display lists
            if ((status & DisplayList.STATUS_DRAW) != 0) {
                if (mRedrawClip.isEmpty()) {
                    attachInfo.mViewRootImpl.invalidate();
                } else {
                    attachInfo.mViewRootImpl.invalidateChildInParent(null, mRedrawClip);
                    mRedrawClip.setEmpty();
                }
            }

            if ((status & DisplayList.STATUS_INVOKE) != 0 ||
                    attachInfo.mHandler.hasCallbacks(mFunctorsRunnable)) {
                attachInfo.mHandler.removeCallbacks(mFunctorsRunnable);
                mFunctorsRunnable.attachInfo = attachInfo;
                attachInfo.mHandler.postDelayed(mFunctorsRunnable, FUNCTOR_PROCESS_DELAY);
            }
        }

        @Override
        void detachFunctor(int functor) {
            if (mCanvas != null) {
                mCanvas.detachFunctor(functor);
            }
        }

        @Override
        boolean attachFunctor(View.AttachInfo attachInfo, int functor) {
            if (mCanvas != null) {
                mCanvas.attachFunctor(functor);
                mFunctorsRunnable.attachInfo = attachInfo;
                attachInfo.mHandler.removeCallbacks(mFunctorsRunnable);
                attachInfo.mHandler.postDelayed(mFunctorsRunnable,  0);
                return true;
            }
            return false;
        }

        /**
         * Ensures the current EGL context and surface are the ones we expect.
         * This method throws an IllegalStateException if invoked from a thread
         * that did not initialize EGL.
         *
         * @return {@link #SURFACE_STATE_ERROR} if the correct EGL context cannot be made current,
         *         {@link #SURFACE_STATE_UPDATED} if the EGL context was changed or
         *         {@link #SURFACE_STATE_SUCCESS} if the EGL context was the correct one
         *
         * @see #checkRenderContextUnsafe()
         */
        int checkRenderContext() {
            if (mEglThread != Thread.currentThread()) {
                throw new IllegalStateException("Hardware acceleration can only be used with a " +
                        "single UI thread.\nOriginal thread: " + mEglThread + "\n" +
                        "Current thread: " + Thread.currentThread());
            }

            return checkRenderContextUnsafe();
        }

        /**
         * Ensures the current EGL context and surface are the ones we expect.
         * This method does not check the current thread.
         *
         * @return {@link #SURFACE_STATE_ERROR} if the correct EGL context cannot be made current,
         *         {@link #SURFACE_STATE_UPDATED} if the EGL context was changed or
         *         {@link #SURFACE_STATE_SUCCESS} if the EGL context was the correct one
         *
         * @see #checkRenderContext()
         */
        private int checkRenderContextUnsafe() {
            if (!mEglSurface.equals(sEgl.eglGetCurrentSurface(EGL_DRAW)) ||
                    !mEglContext.equals(sEgl.eglGetCurrentContext())) {
                if (!sEgl.eglMakeCurrent(sEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                    Log.e(LOG_TAG, "eglMakeCurrent failed " +
                            GLUtils.getEGLErrorString(sEgl.eglGetError()));
                    fallback(true);
                    return SURFACE_STATE_ERROR;
                } else {
                    if (mUpdateDirtyRegions) {
                        enableDirtyRegions();
                        mUpdateDirtyRegions = false;
                    }
                    return SURFACE_STATE_UPDATED;
                }
            }
            return SURFACE_STATE_SUCCESS;
        }

        private static int dpToPx(int dp, float density) {
            return (int) (dp * density + 0.5f);
        }

        class DrawPerformanceDataProvider extends GraphDataProvider {
            private final int mGraphType;

            private int mVerticalUnit;
            private int mHorizontalUnit;
            private int mHorizontalMargin;
            private int mThresholdStroke;

            DrawPerformanceDataProvider(int graphType) {
                mGraphType = graphType;
            }

            @Override
            void prepare(DisplayMetrics metrics) {
                final float density = metrics.density;

                mVerticalUnit = dpToPx(PROFILE_DRAW_DP_PER_MS, density);
                mHorizontalUnit = dpToPx(PROFILE_DRAW_WIDTH, density);
                mHorizontalMargin = dpToPx(PROFILE_DRAW_MARGIN, density);
                mThresholdStroke = dpToPx(PROFILE_DRAW_THRESHOLD_STROKE_WIDTH, density);
            }

            @Override
            int getGraphType() {
                return mGraphType;
            }

            @Override
            int getVerticalUnitSize() {
                return mVerticalUnit;
            }

            @Override
            int getHorizontalUnitSize() {
                return mHorizontalUnit;
            }

            @Override
            int getHorizontaUnitMargin() {
                return mHorizontalMargin;
            }

            @Override
            float[] getData() {
                return mProfileData;
            }

            @Override
            float getThreshold() {
                return 16;
            }

            @Override
            int getFrameCount() {
                return mProfileData.length / PROFILE_FRAME_DATA_COUNT;
            }

            @Override
            int getElementCount() {
                return PROFILE_FRAME_DATA_COUNT;
            }

            @Override
            int getCurrentFrame() {
                return mProfileCurrentFrame / PROFILE_FRAME_DATA_COUNT;
            }

            @Override
            void setupGraphPaint(Paint paint, int elementIndex) {
                paint.setColor(PROFILE_DRAW_COLORS[elementIndex]);
                if (mGraphType == GRAPH_TYPE_LINES) paint.setStrokeWidth(mThresholdStroke);
            }

            @Override
            void setupThresholdPaint(Paint paint) {
                paint.setColor(PROFILE_DRAW_THRESHOLD_COLOR);
                paint.setStrokeWidth(mThresholdStroke);
            }

            @Override
            void setupCurrentFramePaint(Paint paint) {
                paint.setColor(PROFILE_DRAW_CURRENT_FRAME_COLOR);
                if (mGraphType == GRAPH_TYPE_LINES) paint.setStrokeWidth(mThresholdStroke);
            }
        }
    }

    /**
     * Hardware renderer using OpenGL ES 2.0.
     */
    static class Gl20Renderer extends GlRenderer {
        private GLES20Canvas mGlCanvas;

        private DisplayMetrics mDisplayMetrics;

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
                        @Override
                        public void run() {
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
                        sEglContextStorage.set(null);
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
                    }
                }
            }
        }

        Gl20Renderer(boolean translucent) {
            super(2, translucent);
        }

        @Override
        HardwareCanvas createCanvas() {
            return mGlCanvas = new GLES20Canvas(mTranslucent);
        }

        @Override
        ManagedEGLContext createManagedContext(EGLContext eglContext) {
            return new Gl20Renderer.Gl20RendererEglContext(mEglContext);
        }

        @Override
        int[] getConfig(boolean dirtyRegions) {
            //noinspection PointlessBooleanExpression,ConstantConditions
            final int stencilSize = GLES20Canvas.getStencilSize();
            final int swapBehavior = dirtyRegions ? EGL14.EGL_SWAP_BEHAVIOR_PRESERVED_BIT : 0;

            return new int[] {
                    EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_ALPHA_SIZE, 8,
                    EGL_DEPTH_SIZE, 0,
                    EGL_CONFIG_CAVEAT, EGL_NONE,
                    EGL_STENCIL_SIZE, stencilSize,
                    EGL_SURFACE_TYPE, EGL_WINDOW_BIT | swapBehavior,
                    EGL_NONE
            };
        }

        @Override
        void initCaches() {
            if (GLES20Canvas.initCaches()) {
                // Caches were (re)initialized, rebind atlas
                initAtlas();
            }
        }

        @Override
        void initAtlas() {
            IBinder binder = ServiceManager.getService("assetatlas");
            if (binder == null) return;

            IAssetAtlas atlas = IAssetAtlas.Stub.asInterface(binder);
            try {
                if (atlas.isCompatible(android.os.Process.myPpid())) {
                    GraphicBuffer buffer = atlas.getBuffer();
                    if (buffer != null) {
                        int[] map = atlas.getMap();
                        if (map != null) {
                            GLES20Canvas.initAtlas(buffer, map);
                        }
                        // If IAssetAtlas is not the same class as the IBinder
                        // we are using a remote service and we can safely
                        // destroy the graphic buffer
                        if (atlas.getClass() != binder.getClass()) {
                            buffer.destroy();
                        }
                    }
                }
            } catch (RemoteException e) {
                Log.w(LOG_TAG, "Could not acquire atlas", e);
            }
        }

        @Override
        boolean canDraw() {
            return super.canDraw() && mGlCanvas != null;
        }

        @Override
        int onPreDraw(Rect dirty) {
            return mGlCanvas.onPreDraw(dirty);
        }

        @Override
        void onPostDraw() {
            mGlCanvas.onPostDraw();
        }

        @Override
        void drawProfileData(View.AttachInfo attachInfo) {
            if (mDebugDataProvider != null) {
                final GraphDataProvider provider = mDebugDataProvider;
                initProfileDrawData(attachInfo, provider);

                final int height = provider.getVerticalUnitSize();
                final int margin = provider.getHorizontaUnitMargin();
                final int width = provider.getHorizontalUnitSize();

                int x = 0;
                int count = 0;
                int current = 0;

                final float[] data = provider.getData();
                final int elementCount = provider.getElementCount();
                final int graphType = provider.getGraphType();

                int totalCount = provider.getFrameCount() * elementCount;
                if (graphType == GraphDataProvider.GRAPH_TYPE_LINES) {
                    totalCount -= elementCount;
                }

                for (int i = 0; i < totalCount; i += elementCount) {
                    if (data[i] < 0.0f) break;

                    int index = count * 4;
                    if (i == provider.getCurrentFrame() * elementCount) current = index;

                    x += margin;
                    int x2 = x + width;

                    int y2 = mHeight;
                    int y1 = (int) (y2 - data[i] * height);

                    switch (graphType) {
                        case GraphDataProvider.GRAPH_TYPE_BARS: {
                            for (int j = 0; j < elementCount; j++) {
                                //noinspection MismatchedReadAndWriteOfArray
                                final float[] r = mProfileShapes[j];
                                r[index] = x;
                                r[index + 1] = y1;
                                r[index + 2] = x2;
                                r[index + 3] = y2;

                                y2 = y1;
                                if (j < elementCount - 1) {
                                    y1 = (int) (y2 - data[i + j + 1] * height);
                                }
                            }
                        } break;
                        case GraphDataProvider.GRAPH_TYPE_LINES: {
                            for (int j = 0; j < elementCount; j++) {
                                //noinspection MismatchedReadAndWriteOfArray
                                final float[] r = mProfileShapes[j];
                                r[index] = (x + x2) * 0.5f;
                                r[index + 1] = index == 0 ? y1 : r[index - 1];
                                r[index + 2] = r[index] + width;
                                r[index + 3] = y1;

                                y2 = y1;
                                if (j < elementCount - 1) {
                                    y1 = (int) (y2 - data[i + j + 1] * height);
                                }
                            }
                        } break;
                    }


                    x += width;
                    count++;
                }

                x += margin;

                drawGraph(graphType, count);
                drawCurrentFrame(graphType, current);
                drawThreshold(x, height);
            }
        }

        private void drawGraph(int graphType, int count) {
            for (int i = 0; i < mProfileShapes.length; i++) {
                mDebugDataProvider.setupGraphPaint(mProfilePaint, i);
                switch (graphType) {
                    case GraphDataProvider.GRAPH_TYPE_BARS:
                        mGlCanvas.drawRects(mProfileShapes[i], count * 4, mProfilePaint);
                        break;
                    case GraphDataProvider.GRAPH_TYPE_LINES:
                        mGlCanvas.drawLines(mProfileShapes[i], 0, count * 4, mProfilePaint);
                        break;
                }
            }
        }

        private void drawCurrentFrame(int graphType, int index) {
            if (index >= 0) {
                mDebugDataProvider.setupCurrentFramePaint(mProfilePaint);
                switch (graphType) {
                    case GraphDataProvider.GRAPH_TYPE_BARS:
                        mGlCanvas.drawRect(mProfileShapes[2][index], mProfileShapes[2][index + 1],
                                mProfileShapes[2][index + 2], mProfileShapes[0][index + 3],
                                mProfilePaint);
                        break;
                    case GraphDataProvider.GRAPH_TYPE_LINES:
                        mGlCanvas.drawLine(mProfileShapes[2][index], mProfileShapes[2][index + 1],
                                mProfileShapes[2][index], mHeight, mProfilePaint);
                        break;
                }
            }
        }

        private void drawThreshold(int x, int height) {
            float threshold = mDebugDataProvider.getThreshold();
            if (threshold > 0.0f) {
                mDebugDataProvider.setupThresholdPaint(mProfilePaint);
                int y = (int) (mHeight - threshold * height);
                mGlCanvas.drawLine(0.0f, y, x, y, mProfilePaint);
            }
        }

        private void initProfileDrawData(View.AttachInfo attachInfo, GraphDataProvider provider) {
            if (mProfileShapes == null) {
                final int elementCount = provider.getElementCount();
                final int frameCount = provider.getFrameCount();

                mProfileShapes = new float[elementCount][];
                for (int i = 0; i < elementCount; i++) {
                    mProfileShapes[i] = new float[frameCount * 4];
                }

                mProfilePaint = new Paint();
            }

            mProfilePaint.reset();
            if (provider.getGraphType() == GraphDataProvider.GRAPH_TYPE_LINES) {
                mProfilePaint.setAntiAlias(true);
            }

            if (mDisplayMetrics == null) {
                mDisplayMetrics = new DisplayMetrics();
            }

            attachInfo.mDisplay.getMetrics(mDisplayMetrics);
            provider.prepare(mDisplayMetrics);
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
        void pushLayerUpdate(HardwareLayer layer) {
            mGlCanvas.pushLayerUpdate(layer);
        }

        @Override
        void cancelLayerUpdate(HardwareLayer layer) {
            mGlCanvas.cancelLayerUpdate(layer);
        }

        @Override
        void flushLayerUpdates() {
            mGlCanvas.flushLayerUpdates();
        }

        @Override
        public DisplayList createDisplayList(String name) {
            return new GLES20DisplayList(name);
        }

        @Override
        HardwareLayer createHardwareLayer(boolean isOpaque) {
            return new GLES20TextureLayer(isOpaque);
        }

        @Override
        public HardwareLayer createHardwareLayer(int width, int height, boolean isOpaque) {
            return new GLES20RenderLayer(width, height, isOpaque);
        }

        @Override
        void countOverdraw(HardwareCanvas canvas) {
            ((GLES20Canvas) canvas).setCountOverdrawEnabled(true);
        }

        @Override
        float getOverdraw(HardwareCanvas canvas) {
            return ((GLES20Canvas) canvas).getOverdraw();
        }

        @Override
        public SurfaceTexture createSurfaceTexture(HardwareLayer layer) {
            return ((GLES20TextureLayer) layer).getSurfaceTexture();
        }

        @Override
        void setSurfaceTexture(HardwareLayer layer, SurfaceTexture surfaceTexture) {
            ((GLES20TextureLayer) layer).setSurfaceTexture(surfaceTexture);
        }

        @Override
        boolean safelyRun(Runnable action) {
            boolean needsContext = !isEnabled() || checkRenderContext() == SURFACE_STATE_ERROR;

            if (needsContext) {
                Gl20RendererEglContext managedContext =
                        (Gl20RendererEglContext) sEglContextStorage.get();
                if (managedContext == null) return false;
                usePbufferSurface(managedContext.getContext());
            }

            try {
                action.run();
            } finally {
                if (needsContext) {
                    sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE,
                            EGL_NO_SURFACE, EGL_NO_CONTEXT);
                }
            }

            return true;
        }

        @Override
        void destroyLayers(final View view) {
            if (view != null) {
                safelyRun(new Runnable() {
                    @Override
                    public void run() {
                        if (mCanvas != null) {
                            mCanvas.clearLayerUpdates();
                        }
                        destroyHardwareLayer(view);
                        GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_LAYERS);
                    }
                });
            }
        }

        private static void destroyHardwareLayer(View view) {
            view.destroyLayer(true);

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;

                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    destroyHardwareLayer(group.getChildAt(i));
                }
            }
        }

        @Override
        void destroyHardwareResources(final View view) {
            if (view != null) {
                safelyRun(new Runnable() {
                    @Override
                    public void run() {
                        if (mCanvas != null) {
                            mCanvas.clearLayerUpdates();
                        }
                        destroyResources(view);
                        GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_LAYERS);
                    }
                });
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

        static void startTrimMemory(int level) {
            if (sEgl == null || sEglConfig == null) return;

            Gl20RendererEglContext managedContext =
                    (Gl20RendererEglContext) sEglContextStorage.get();
            // We do not have OpenGL objects
            if (managedContext == null) {
                return;
            } else {
                usePbufferSurface(managedContext.getContext());
            }

            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_FULL);
            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                GLES20Canvas.flushCaches(GLES20Canvas.FLUSH_CACHES_MODERATE);
            }
        }

        static void endTrimMemory() {
            if (sEgl != null && sEglDisplay != null) {
                sEgl.eglMakeCurrent(sEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
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
