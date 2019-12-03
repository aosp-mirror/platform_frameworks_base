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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.HardwareRenderer;
import android.graphics.Picture;
import android.graphics.Point;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.SystemProperties;
import android.os.Trace;
import android.view.Surface.OutOfResourcesException;
import android.view.View.AttachInfo;
import android.view.animation.AnimationUtils;

import com.android.internal.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Threaded renderer that proxies the rendering to a render thread. Most calls
 * are currently synchronous.
 *
 * The UI thread can block on the RenderThread, but RenderThread must never
 * block on the UI thread.
 *
 * ThreadedRenderer creates an instance of RenderProxy. RenderProxy in turn creates
 * and manages a CanvasContext on the RenderThread. The CanvasContext is fully managed
 * by the lifecycle of the RenderProxy.
 *
 * Note that although currently the EGL context & surfaces are created & managed
 * by the render thread, the goal is to move that into a shared structure that can
 * be managed by both threads. EGLSurface creation & deletion should ideally be
 * done on the UI thread and not the RenderThread to avoid stalling the
 * RenderThread with surface buffer allocation.
 *
 * @hide
 */
public final class ThreadedRenderer extends HardwareRenderer {
    /**
     * System property used to enable or disable threaded rendering profiling.
     * The default value of this property is assumed to be false.
     *
     * When profiling is enabled, the adb shell dumpsys gfxinfo command will
     * output extra information about the time taken to execute by the last
     * frames.
     *
     * Possible values:
     * "true", to enable profiling
     * "visual_bars", to enable profiling and visualize the results on screen
     * "false", to disable profiling
     *
     * @see #PROFILE_PROPERTY_VISUALIZE_BARS
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
     * System property used to specify the number of frames to be used
     * when doing threaded rendering profiling.
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
     * Sets the FPS devisor to lower the FPS.
     *
     * Sets a positive integer as a divisor. 1 (the default value) menas the full FPS, and 2
     * means half the full FPS.
     *
     *
     * @hide
     */
    public static final String DEBUG_FPS_DIVISOR = "debug.hwui.fps_divisor";

    /**
     * Forces smart-dark to be always on.
     * @hide
     */
    public static final String DEBUG_FORCE_DARK = "debug.hwui.force_dark";

    public static int EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101;
    public static int EGL_CONTEXT_PRIORITY_MEDIUM_IMG = 0x3102;
    public static int EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103;

    static {
        // Try to check OpenGL support early if possible.
        isAvailable();
    }

    /**
     * A process can set this flag to false to prevent the use of threaded
     * rendering.
     *
     * @hide
     */
    public static boolean sRendererDisabled = false;

    /**
     * Further threaded renderer disabling for the system process.
     *
     * @hide
     */
    public static boolean sSystemRendererDisabled = false;

    /**
     * Invoke this method to disable threaded rendering in the current process.
     *
     * @hide
     */
    public static void disable(boolean system) {
        sRendererDisabled = true;
        if (system) {
            sSystemRendererDisabled = true;
        }
    }

    public static boolean sTrimForeground = false;

    /**
     * Controls whether or not the renderer should aggressively trim
     * memory. Note that this must not be set for any process that uses
     * WebView! This should be only used by system_process or similar
     * that do not go into the background.
     */
    public static void enableForegroundTrimming() {
        sTrimForeground = true;
    }

    private static Boolean sSupportsOpenGL;

    /**
     * Indicates whether threaded rendering is available under any form for
     * the view hierarchy.
     *
     * @return True if the view hierarchy can potentially be defer rendered,
     *         false otherwise
     */
    public static boolean isAvailable() {
        if (sSupportsOpenGL != null) {
            return sSupportsOpenGL.booleanValue();
        }
        if (SystemProperties.getInt("ro.kernel.qemu", 0) == 0) {
            // Device is not an emulator.
            sSupportsOpenGL = true;
            return true;
        }
        int qemu_gles = SystemProperties.getInt("qemu.gles", -1);
        if (qemu_gles == -1) {
            // In this case, the value of the qemu.gles property is not ready
            // because the SurfaceFlinger service may not start at this point.
            return false;
        }
        // In the emulator this property will be set > 0 when OpenGL ES 2.0 is
        // enabled, 0 otherwise. On old emulator versions it will be undefined.
        sSupportsOpenGL = qemu_gles > 0;
        return sSupportsOpenGL.booleanValue();
    }

    /**
     * Creates a threaded renderer using OpenGL.
     *
     * @param translucent True if the surface is translucent, false otherwise
     *
     * @return A threaded renderer backed by OpenGL.
     */
    public static ThreadedRenderer create(Context context, boolean translucent, String name) {
        ThreadedRenderer renderer = null;
        if (isAvailable()) {
            renderer = new ThreadedRenderer(context, translucent, name);
        }
        return renderer;
    }

    private static final String[] VISUALIZERS = {
        PROFILE_PROPERTY_VISUALIZE_BARS,
    };

    // Size of the rendered content.
    private int mWidth, mHeight;

    // Actual size of the drawing surface.
    private int mSurfaceWidth, mSurfaceHeight;

    // Insets between the drawing surface and rendered content. These are
    // applied as translation when updating the root render node.
    private int mInsetTop, mInsetLeft;

    // Whether the surface has insets. Used to protect opacity.
    private boolean mHasInsets;

    // Light properties specified by the theme.
    private final float mLightY;
    private final float mLightZ;
    private final float mLightRadius;

    private boolean mInitialized = false;
    private boolean mRootNodeNeedsUpdate;

    private boolean mEnabled;
    private boolean mRequested = true;

    @Nullable
    private ArrayList<FrameDrawingCallback> mNextRtFrameCallbacks;

    ThreadedRenderer(Context context, boolean translucent, String name) {
        super();
        setName(name);
        setOpaque(!translucent);

        final TypedArray a = context.obtainStyledAttributes(null, R.styleable.Lighting, 0, 0);
        mLightY = a.getDimension(R.styleable.Lighting_lightY, 0);
        mLightZ = a.getDimension(R.styleable.Lighting_lightZ, 0);
        mLightRadius = a.getDimension(R.styleable.Lighting_lightRadius, 0);
        float ambientShadowAlpha = a.getFloat(R.styleable.Lighting_ambientShadowAlpha, 0);
        float spotShadowAlpha = a.getFloat(R.styleable.Lighting_spotShadowAlpha, 0);
        a.recycle();
        setLightSourceAlpha(ambientShadowAlpha, spotShadowAlpha);
    }

    @Override
    public void destroy() {
        mInitialized = false;
        updateEnabledState(null);
        super.destroy();
    }

    /**
     * Indicates whether threaded rendering is currently enabled.
     *
     * @return True if threaded rendering  is in use, false otherwise.
     */
    boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Indicates whether threaded rendering  is currently enabled.
     *
     * @param enabled True if the threaded renderer is in use, false otherwise.
     */
    void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    /**
     * Indicates whether threaded rendering is currently request but not
     * necessarily enabled yet.
     *
     * @return True if requested, false otherwise.
     */
    boolean isRequested() {
        return mRequested;
    }

    /**
     * Indicates whether threaded rendering is currently requested but not
     * necessarily enabled yet.
     */
    void setRequested(boolean requested) {
        mRequested = requested;
    }

    private void updateEnabledState(Surface surface) {
        if (surface == null || !surface.isValid()) {
            setEnabled(false);
        } else {
            setEnabled(mInitialized);
        }
    }

    /**
     * Initializes the threaded renderer for the specified surface.
     *
     * @param surface The surface to render
     *
     * @return True if the initialization was successful, false otherwise.
     */
    boolean initialize(Surface surface) throws OutOfResourcesException {
        boolean status = !mInitialized;
        mInitialized = true;
        updateEnabledState(surface);
        setSurface(surface);
        return status;
    }

    /**
     * Initializes the threaded renderer for the specified surface and setup the
     * renderer for drawing, if needed. This is invoked when the ViewAncestor has
     * potentially lost the threaded renderer. The threaded renderer should be
     * reinitialized and setup when the render {@link #isRequested()} and
     * {@link #isEnabled()}.
     *
     * @param width The width of the drawing surface.
     * @param height The height of the drawing surface.
     * @param attachInfo Information about the window.
     * @param surface The surface to render
     * @param surfaceInsets The drawing surface insets to apply
     *
     * @return true if the surface was initialized, false otherwise. Returning
     *         false might mean that the surface was already initialized.
     */
    boolean initializeIfNeeded(int width, int height, View.AttachInfo attachInfo,
            Surface surface, Rect surfaceInsets) throws OutOfResourcesException {
        if (isRequested()) {
            // We lost the gl context, so recreate it.
            if (!isEnabled()) {
                if (initialize(surface)) {
                    setup(width, height, attachInfo, surfaceInsets);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Updates the threaded renderer for the specified surface.
     *
     * @param surface The surface to render
     */
    void updateSurface(Surface surface) throws OutOfResourcesException {
        updateEnabledState(surface);
        setSurface(surface);
    }

    @Override
    public void setSurface(Surface surface) {
        // TODO: Do we ever pass a non-null but isValid() = false surface?
        // This is here to be super conservative for ViewRootImpl
        if (surface != null && surface.isValid()) {
            super.setSurface(surface);
        } else {
            super.setSurface(null);
        }
    }

    /**
     * Registers a callback to be executed when the next frame is being drawn on RenderThread. This
     * callback will be executed on a RenderThread worker thread, and only used for the next frame
     * and thus it will only fire once.
     *
     * @param callback The callback to register.
     */
    void registerRtFrameCallback(@NonNull FrameDrawingCallback callback) {
        if (mNextRtFrameCallbacks == null) {
            mNextRtFrameCallbacks = new ArrayList<>();
        }
        mNextRtFrameCallbacks.add(callback);
    }

    /**
     * Destroys all hardware rendering resources associated with the specified
     * view hierarchy.
     *
     * @param view The root of the view hierarchy
     */
    void destroyHardwareResources(View view) {
        destroyResources(view);
        clearContent();
    }

    private static void destroyResources(View view) {
        view.destroyHardwareResources();
    }

    /**
     * Sets up the renderer for drawing.
     *
     * @param width The width of the drawing surface.
     * @param height The height of the drawing surface.
     * @param attachInfo Information about the window.
     * @param surfaceInsets The drawing surface insets to apply
     */
    void setup(int width, int height, AttachInfo attachInfo, Rect surfaceInsets) {
        mWidth = width;
        mHeight = height;

        if (surfaceInsets != null && (surfaceInsets.left != 0 || surfaceInsets.right != 0
                || surfaceInsets.top != 0 || surfaceInsets.bottom != 0)) {
            mHasInsets = true;
            mInsetLeft = surfaceInsets.left;
            mInsetTop = surfaceInsets.top;
            mSurfaceWidth = width + mInsetLeft + surfaceInsets.right;
            mSurfaceHeight = height + mInsetTop + surfaceInsets.bottom;

            // If the surface has insets, it can't be opaque.
            setOpaque(false);
        } else {
            mHasInsets = false;
            mInsetLeft = 0;
            mInsetTop = 0;
            mSurfaceWidth = width;
            mSurfaceHeight = height;
        }

        mRootNode.setLeftTopRightBottom(-mInsetLeft, -mInsetTop, mSurfaceWidth, mSurfaceHeight);

        setLightCenter(attachInfo);
    }

    /**
     * Updates the light position based on the position of the window.
     *
     * @param attachInfo Information about the window.
     */
    void setLightCenter(AttachInfo attachInfo) {
        // Adjust light position for window offsets.
        final Point displaySize = attachInfo.mPoint;
        attachInfo.mDisplay.getRealSize(displaySize);
        final float lightX = displaySize.x / 2f - attachInfo.mWindowLeft;
        final float lightY = mLightY - attachInfo.mWindowTop;
        setLightSourceGeometry(lightX, lightY, mLightZ, mLightRadius);
    }

    /**
     * Gets the current width of the surface. This is the width that the surface
     * was last set to in a call to {@link #setup(int, int, View.AttachInfo, Rect)}.
     *
     * @return the current width of the surface
     */
    int getWidth() {
        return mWidth;
    }

    /**
     * Gets the current height of the surface. This is the height that the surface
     * was last set to in a call to {@link #setup(int, int, View.AttachInfo, Rect)}.
     *
     * @return the current width of the surface
     */
    int getHeight() {
        return mHeight;
    }

    /**
     * Outputs extra debugging information in the specified file descriptor.
     */
    void dumpGfxInfo(PrintWriter pw, FileDescriptor fd, String[] args) {
        pw.flush();
        // If there's no arguments, eg 'dumpsys gfxinfo', then dump everything.
        // If there's a targetted package, eg 'dumpsys gfxinfo com.android.systemui', then only
        // dump the summary information
        int flags = (args == null || args.length == 0) ? FLAG_DUMP_ALL : 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "framestats":
                    flags |= FLAG_DUMP_FRAMESTATS;
                    break;
                case "reset":
                    flags |= FLAG_DUMP_RESET;
                    break;
                case "-a": // magic option passed when dumping a bugreport.
                    flags = FLAG_DUMP_ALL;
                    break;
            }
        }
        dumpProfileInfo(fd, flags);
    }

    Picture captureRenderingCommands() {
        return null;
    }

    @Override
    public boolean loadSystemProperties() {
        boolean changed = super.loadSystemProperties();
        if (changed) {
            invalidateRoot();
        }
        return changed;
    }

    private void updateViewTreeDisplayList(View view) {
        view.mPrivateFlags |= View.PFLAG_DRAWN;
        view.mRecreateDisplayList = (view.mPrivateFlags & View.PFLAG_INVALIDATED)
                == View.PFLAG_INVALIDATED;
        view.mPrivateFlags &= ~View.PFLAG_INVALIDATED;
        view.updateDisplayListIfDirty();
        view.mRecreateDisplayList = false;
    }

    private void updateRootDisplayList(View view, DrawCallbacks callbacks) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "Record View#draw()");
        updateViewTreeDisplayList(view);

        // Consume and set the frame callback after we dispatch draw to the view above, but before
        // onPostDraw below which may reset the callback for the next frame.  This ensures that
        // updates to the frame callback during scroll handling will also apply in this frame.
        if (mNextRtFrameCallbacks != null) {
            final ArrayList<FrameDrawingCallback> frameCallbacks = mNextRtFrameCallbacks;
            mNextRtFrameCallbacks = null;
            setFrameCallback(frame -> {
                for (int i = 0; i < frameCallbacks.size(); ++i) {
                    frameCallbacks.get(i).onFrameDraw(frame);
                }
            });
        }

        if (mRootNodeNeedsUpdate || !mRootNode.hasDisplayList()) {
            RecordingCanvas canvas = mRootNode.beginRecording(mSurfaceWidth, mSurfaceHeight);
            try {
                final int saveCount = canvas.save();
                canvas.translate(mInsetLeft, mInsetTop);
                callbacks.onPreDraw(canvas);

                canvas.enableZ();
                canvas.drawRenderNode(view.updateDisplayListIfDirty());
                canvas.disableZ();

                callbacks.onPostDraw(canvas);
                canvas.restoreToCount(saveCount);
                mRootNodeNeedsUpdate = false;
            } finally {
                mRootNode.endRecording();
            }
        }
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
    }

    /**
     * Interface used to receive callbacks whenever a view is drawn by
     * a threaded renderer instance.
     */
    interface DrawCallbacks {
        /**
         * Invoked before a view is drawn by a threaded renderer.
         * This method can be used to apply transformations to the
         * canvas but no drawing command should be issued.
         *
         * @param canvas The Canvas used to render the view.
         */
        void onPreDraw(RecordingCanvas canvas);

        /**
         * Invoked after a view is drawn by a threaded renderer.
         * It is safe to invoke drawing commands from this method.
         *
         * @param canvas The Canvas used to render the view.
         */
        void onPostDraw(RecordingCanvas canvas);
    }

    /**
     *  Indicates that the content drawn by DrawCallbacks needs to
     *  be updated, which will be done by the next call to draw()
     */
    void invalidateRoot() {
        mRootNodeNeedsUpdate = true;
    }

    /**
     * Draws the specified view.
     *
     * @param view The view to draw.
     * @param attachInfo AttachInfo tied to the specified view.
     */
    void draw(View view, AttachInfo attachInfo, DrawCallbacks callbacks) {
        final Choreographer choreographer = attachInfo.mViewRootImpl.mChoreographer;
        choreographer.mFrameInfo.markDrawStart();

        updateRootDisplayList(view, callbacks);

        // register animating rendernodes which started animating prior to renderer
        // creation, which is typical for animators started prior to first draw
        if (attachInfo.mPendingAnimatingRenderNodes != null) {
            final int count = attachInfo.mPendingAnimatingRenderNodes.size();
            for (int i = 0; i < count; i++) {
                registerAnimatingRenderNode(
                        attachInfo.mPendingAnimatingRenderNodes.get(i));
            }
            attachInfo.mPendingAnimatingRenderNodes.clear();
            // We don't need this anymore as subsequent calls to
            // ViewRootImpl#attachRenderNodeAnimator will go directly to us.
            attachInfo.mPendingAnimatingRenderNodes = null;
        }

        int syncResult = syncAndDrawFrame(choreographer.mFrameInfo);
        if ((syncResult & SYNC_LOST_SURFACE_REWARD_IF_FOUND) != 0) {
            setEnabled(false);
            attachInfo.mViewRootImpl.mSurface.release();
            // Invalidate since we failed to draw. This should fetch a Surface
            // if it is still needed or do nothing if we are no longer drawing
            attachInfo.mViewRootImpl.invalidate();
        }
        if ((syncResult & SYNC_REDRAW_REQUESTED) != 0) {
            attachInfo.mViewRootImpl.invalidate();
        }
    }

    /** The root of everything */
    public @NonNull RenderNode getRootNode() {
        return mRootNode;
    }

    /**
     * Basic synchronous renderer. Currently only used to render the Magnifier, so use with care.
     * TODO: deduplicate against ThreadedRenderer.
     *
     * @hide
     */
    public static class SimpleRenderer extends HardwareRenderer {
        private final float mLightY, mLightZ, mLightRadius;

        public SimpleRenderer(final Context context, final String name, final Surface surface) {
            super();
            setName(name);
            setOpaque(false);
            setSurface(surface);
            final TypedArray a = context.obtainStyledAttributes(null, R.styleable.Lighting, 0, 0);
            mLightY = a.getDimension(R.styleable.Lighting_lightY, 0);
            mLightZ = a.getDimension(R.styleable.Lighting_lightZ, 0);
            mLightRadius = a.getDimension(R.styleable.Lighting_lightRadius, 0);
            final float ambientShadowAlpha = a.getFloat(R.styleable.Lighting_ambientShadowAlpha, 0);
            final float spotShadowAlpha = a.getFloat(R.styleable.Lighting_spotShadowAlpha, 0);
            a.recycle();
            setLightSourceAlpha(ambientShadowAlpha, spotShadowAlpha);
        }

        /**
         * Set the light center.
         */
        public void setLightCenter(final Display display,
                final int windowLeft, final int windowTop) {
            // Adjust light position for window offsets.
            final Point displaySize = new Point();
            display.getRealSize(displaySize);
            final float lightX = displaySize.x / 2f - windowLeft;
            final float lightY = mLightY - windowTop;

            setLightSourceGeometry(lightX, lightY, mLightZ, mLightRadius);
        }

        public RenderNode getRootNode() {
            return mRootNode;
        }

        /**
         * Draw the surface.
         */
        public void draw(final FrameDrawingCallback callback) {
            final long vsync = AnimationUtils.currentAnimationTimeMillis() * 1000000L;
            if (callback != null) {
                setFrameCallback(callback);
            }
            createRenderRequest()
                    .setVsyncTime(vsync)
                    .syncAndDraw();
        }
    }
}
