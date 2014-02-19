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

import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.util.DisplayMetrics;
import android.view.Surface.OutOfResourcesException;

import java.io.File;
import java.io.PrintWriter;

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

    /** @hide */
    public static boolean sUseRenderThread = false;

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
    abstract boolean loadSystemProperties();

    /**
     * Sets the directory to use as a persistent storage for hardware rendering
     * resources.
     *
     * @param cacheDir A directory the current process can write to
     *
     * @hide
     */
    public static void setupDiskCache(File cacheDir) {
        GLRenderer.setupShadersDiskCache(new File(cacheDir, CACHE_PATH_SHADERS).getAbsolutePath());
    }

    /**
     * Indicates that the specified hardware layer needs to be updated
     * as soon as possible.
     *
     * @param layer The hardware layer that needs an update
     *
     * @see #flushLayerUpdates()
     */
    abstract void pushLayerUpdate(HardwareLayer layer);

    /**
     * Tells the HardwareRenderer that a layer was created. The renderer should
     * make sure to apply any pending layer changes at the start of a new frame
     */
    abstract void onLayerCreated(HardwareLayer hardwareLayer);

    /**
     * Tells the HardwareRenderer that the layer is destroyed. The renderer
     * should remove the layer from any update queues.
     */
    abstract void onLayerDestroyed(HardwareLayer layer);

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
     * Creates a new hardware layer. A hardware layer built by calling this
     * method will be treated as a texture layer, instead of as a render target.
     *
     * @return A hardware layer
     */
    abstract HardwareLayer createTextureLayer();

    /**
     * Creates a new hardware layer.
     *
     * @param width The minimum width of the layer
     * @param height The minimum height of the layer
     *
     * @return A hardware layer
     */
    abstract HardwareLayer createDisplayListLayer(int width, int height);

    /**
     * Creates a new {@link SurfaceTexture} that can be used to render into the
     * specified hardware layer.
     *
     * @param layer The layer to render into using a {@link android.graphics.SurfaceTexture}
     *
     * @return A {@link SurfaceTexture}
     */
    abstract SurfaceTexture createSurfaceTexture(HardwareLayer layer);

    abstract boolean copyLayerInto(HardwareLayer layer, Bitmap bitmap);

    /**
     * Detaches the specified functor from the current functor execution queue.
     *
     * @param functor The native functor to remove from the execution queue.
     *
     * @see HardwareCanvas#callDrawGLFunction(int)
     * @see #attachFunctor(android.view.View.AttachInfo, long)
     */
    abstract void detachFunctor(long functor);

    /**
     * Schedules the specified functor in the functors execution queue.
     *
     * @param attachInfo AttachInfo tied to this renderer.
     * @param functor The native functor to insert in the execution queue.
     *
     * @see HardwareCanvas#callDrawGLFunction(int)
     * @see #detachFunctor(long)
     *
     */
    abstract void attachFunctor(View.AttachInfo attachInfo, long functor);

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
     * @param translucent True if the surface is translucent, false otherwise
     *
     * @return A hardware renderer backed by OpenGL.
     */
    static HardwareRenderer create(boolean translucent) {
        HardwareRenderer renderer = null;
        if (GLES20Canvas.isAvailable()) {
            if (sUseRenderThread) {
                renderer = new ThreadedRenderer(translucent);
            } else {
                renderer = new GLRenderer(translucent);
            }
        }
        return renderer;
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
        GLRenderer.startTrimMemory(level);
    }

    /**
     * Finishes the process of trimming memory. This method will usually
     * cleanup special resources used by the memory trimming process.
     */
    static void endTrimMemory() {
        GLRenderer.endTrimMemory();
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
}
