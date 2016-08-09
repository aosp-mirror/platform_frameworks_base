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

import android.app.ActivityManagerNative;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.util.Log;
import android.view.Surface.OutOfResourcesException;
import android.view.View.AttachInfo;

import com.android.internal.R;
import com.android.internal.util.VirtualRefBasePtr;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;

/**
 * Hardware renderer that proxies the rendering to a render thread. Most calls
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
public final class ThreadedRenderer {
    private static final String LOG_TAG = "ThreadedRenderer";

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

    public static boolean sTrimForeground = false;

    /**
     * Controls whether or not the hardware renderer should aggressively
     * trim memory. Note that this must not be set for any process that
     * uses WebView! This should be only used by system_process or similar
     * that do not go into the background.
     */
    public static void enableForegroundTrimming() {
        sTrimForeground = true;
    }

    /**
     * Indicates whether hardware acceleration is available under any form for
     * the view hierarchy.
     *
     * @return True if the view hierarchy can potentially be hardware accelerated,
     *         false otherwise
     */
    public static boolean isAvailable() {
        return DisplayListCanvas.isAvailable();
    }

    /**
     * Sets the directory to use as a persistent storage for hardware rendering
     * resources.
     *
     * @param cacheDir A directory the current process can write to
     *
     * @hide
     */
    public static void setupDiskCache(File cacheDir) {
        ThreadedRenderer.setupShadersDiskCache(new File(cacheDir, CACHE_PATH_SHADERS).getAbsolutePath());
    }

    /**
     * Creates a hardware renderer using OpenGL.
     *
     * @param translucent True if the surface is translucent, false otherwise
     *
     * @return A hardware renderer backed by OpenGL.
     */
    public static ThreadedRenderer create(Context context, boolean translucent) {
        ThreadedRenderer renderer = null;
        if (DisplayListCanvas.isAvailable()) {
            renderer = new ThreadedRenderer(context, translucent);
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
    public static void trimMemory(int level) {
        nTrimMemory(level);
    }

    public static void overrideProperty(@NonNull String name, @NonNull String value) {
        if (name == null || value == null) {
            throw new IllegalArgumentException("name and value must be non-null");
        }
        nOverrideProperty(name, value);
    }

    public static void dumpProfileData(byte[] data, FileDescriptor fd) {
        nDumpProfileData(data, fd);
    }

    // Keep in sync with DrawFrameTask.h SYNC_* flags
    // Nothing interesting to report
    private static final int SYNC_OK = 0;
    // Needs a ViewRoot invalidate
    private static final int SYNC_INVALIDATE_REQUIRED = 1 << 0;
    // Spoiler: the reward is GPU-accelerated drawing, better find that Surface!
    private static final int SYNC_LOST_SURFACE_REWARD_IF_FOUND = 1 << 1;
    // setStopped is true, drawing is false
    // TODO: Remove this and SYNC_LOST_SURFACE_REWARD_IF_FOUND?
    // This flag isn't really used as there's nothing that we care to do
    // in response, so it really just exists to differentiate from LOST_SURFACE
    // but possibly both can just be deleted.
    private static final int SYNC_CONTEXT_IS_STOPPED = 1 << 2;

    private static final String[] VISUALIZERS = {
        PROFILE_PROPERTY_VISUALIZE_BARS,
    };

    private static final int FLAG_DUMP_FRAMESTATS   = 1 << 0;
    private static final int FLAG_DUMP_RESET        = 1 << 1;

    @IntDef(flag = true, value = {
            FLAG_DUMP_FRAMESTATS, FLAG_DUMP_RESET })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DumpFlags {}

    // Size of the rendered content.
    private int mWidth, mHeight;

    // Actual size of the drawing surface.
    private int mSurfaceWidth, mSurfaceHeight;

    // Insets between the drawing surface and rendered content. These are
    // applied as translation when updating the root render node.
    private int mInsetTop, mInsetLeft;

    // Whether the surface has insets. Used to protect opacity.
    private boolean mHasInsets;

    // Light and shadow properties specified by the theme.
    private final float mLightY;
    private final float mLightZ;
    private final float mLightRadius;
    private final int mAmbientShadowAlpha;
    private final int mSpotShadowAlpha;

    private long mNativeProxy;
    private boolean mInitialized = false;
    private RenderNode mRootNode;
    private Choreographer mChoreographer;
    private boolean mRootNodeNeedsUpdate;

    private boolean mEnabled;
    private boolean mRequested = true;

    ThreadedRenderer(Context context, boolean translucent) {
        final TypedArray a = context.obtainStyledAttributes(null, R.styleable.Lighting, 0, 0);
        mLightY = a.getDimension(R.styleable.Lighting_lightY, 0);
        mLightZ = a.getDimension(R.styleable.Lighting_lightZ, 0);
        mLightRadius = a.getDimension(R.styleable.Lighting_lightRadius, 0);
        mAmbientShadowAlpha =
                (int) (255 * a.getFloat(R.styleable.Lighting_ambientShadowAlpha, 0) + 0.5f);
        mSpotShadowAlpha = (int) (255 * a.getFloat(R.styleable.Lighting_spotShadowAlpha, 0) + 0.5f);
        a.recycle();

        long rootNodePtr = nCreateRootRenderNode();
        mRootNode = RenderNode.adopt(rootNodePtr);
        mRootNode.setClipToBounds(false);
        mNativeProxy = nCreateProxy(translucent, rootNodePtr);

        ProcessInitializer.sInstance.init(context, mNativeProxy);

        loadSystemProperties();
    }

    /**
     * Destroys the hardware rendering context.
     */
    void destroy() {
        mInitialized = false;
        updateEnabledState(null);
        nDestroy(mNativeProxy, mRootNode.mNativeRenderNode);
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

    private void updateEnabledState(Surface surface) {
        if (surface == null || !surface.isValid()) {
            setEnabled(false);
        } else {
            setEnabled(mInitialized);
        }
    }

    /**
     * Initializes the hardware renderer for the specified surface.
     *
     * @param surface The surface to hardware accelerate
     *
     * @return True if the initialization was successful, false otherwise.
     */
    boolean initialize(Surface surface) throws OutOfResourcesException {
        boolean status = !mInitialized;
        mInitialized = true;
        updateEnabledState(surface);
        nInitialize(mNativeProxy, surface);
        return status;
    }

    /**
     * Initializes the hardware renderer for the specified surface and setup the
     * renderer for drawing, if needed. This is invoked when the ViewAncestor has
     * potentially lost the hardware renderer. The hardware renderer should be
     * reinitialized and setup when the render {@link #isRequested()} and
     * {@link #isEnabled()}.
     *
     * @param width The width of the drawing surface.
     * @param height The height of the drawing surface.
     * @param attachInfo Information about the window.
     * @param surface The surface to hardware accelerate
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
     * Updates the hardware renderer for the specified surface.
     *
     * @param surface The surface to hardware accelerate
     */
    void updateSurface(Surface surface) throws OutOfResourcesException {
        updateEnabledState(surface);
        nUpdateSurface(mNativeProxy, surface);
    }

    /**
     * Halts any current rendering into the surface. Use this if it is unclear whether
     * or not the surface used by the HardwareRenderer will be changing. It
     * Suspends any rendering into the surface, but will not do any destruction.
     *
     * Any subsequent draws will override the pause, resuming normal operation.
     */
    boolean pauseSurface(Surface surface) {
        return nPauseSurface(mNativeProxy, surface);
    }

    /**
     * Hard stops or resumes rendering into the surface. This flag is used to
     * determine whether or not it is safe to use the given surface *at all*
     */
    void setStopped(boolean stopped) {
        nSetStopped(mNativeProxy, stopped);
    }

    /**
     * Destroys all hardware rendering resources associated with the specified
     * view hierarchy.
     *
     * @param view The root of the view hierarchy
     */
    void destroyHardwareResources(View view) {
        destroyResources(view);
        nDestroyHardwareResources(mNativeProxy);
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

    /**
     * Detaches the layer's surface texture from the GL context and releases
     * the texture id
     */
    void detachSurfaceTexture(long hardwareLayer) {
        nDetachSurfaceTexture(mNativeProxy, hardwareLayer);
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
        nSetup(mNativeProxy, mSurfaceWidth, mSurfaceHeight, mLightRadius,
                mAmbientShadowAlpha, mSpotShadowAlpha);

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

        nSetLightCenter(mNativeProxy, lightX, lightY, mLightZ);
    }

    /**
     * Change the HardwareRenderer's opacity
     */
    void setOpaque(boolean opaque) {
        nSetOpaque(mNativeProxy, opaque && !mHasInsets);
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
        int flags = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "framestats":
                    flags |= FLAG_DUMP_FRAMESTATS;
                    break;
                case "reset":
                    flags |= FLAG_DUMP_RESET;
                    break;
            }
        }
        nDumpProfileInfo(mNativeProxy, fd, flags);
    }

    /**
     * Loads system properties used by the renderer. This method is invoked
     * whenever system properties are modified. Implementations can use this
     * to trigger live updates of the renderer based on properties.
     *
     * @return True if a property has changed.
     */
    boolean loadSystemProperties() {
        boolean changed = nLoadSystemProperties(mNativeProxy);
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

    private void updateRootDisplayList(View view, HardwareDrawCallbacks callbacks) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "Record View#draw()");
        updateViewTreeDisplayList(view);

        if (mRootNodeNeedsUpdate || !mRootNode.isValid()) {
            DisplayListCanvas canvas = mRootNode.start(mSurfaceWidth, mSurfaceHeight);
            try {
                final int saveCount = canvas.save();
                canvas.translate(mInsetLeft, mInsetTop);
                callbacks.onHardwarePreDraw(canvas);

                canvas.insertReorderBarrier();
                canvas.drawRenderNode(view.updateDisplayListIfDirty());
                canvas.insertInorderBarrier();

                callbacks.onHardwarePostDraw(canvas);
                canvas.restoreToCount(saveCount);
                mRootNodeNeedsUpdate = false;
            } finally {
                mRootNode.end(canvas);
            }
        }
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
    }

    /**
     * Adds a rendernode to the renderer which can be drawn and changed asynchronously to the
     * rendernode of the UI thread.
     * @param node The node to add.
     * @param placeFront If true, the render node will be placed in front of the content node,
     *                   otherwise behind the content node.
     */
    public void addRenderNode(RenderNode node, boolean placeFront) {
        nAddRenderNode(mNativeProxy, node.mNativeRenderNode, placeFront);
    }

    /**
     * Only especially added render nodes can be removed.
     * @param node The node which was added via addRenderNode which should get removed again.
     */
    public void removeRenderNode(RenderNode node) {
        nRemoveRenderNode(mNativeProxy, node.mNativeRenderNode);
    }

    /**
     * Draws a particular render node. If the node is not the content node, only the additional
     * nodes will get drawn and the content remains untouched.
     * @param node The node to be drawn.
     */
    public void drawRenderNode(RenderNode node) {
        nDrawRenderNode(mNativeProxy, node.mNativeRenderNode);
    }

    /**
     * To avoid unnecessary overdrawing of the main content all additionally passed render nodes
     * will be prevented to overdraw this area. It will be synchronized with the draw call.
     * This should be updated in the content view's draw call.
     * @param left The left side of the protected bounds.
     * @param top The top side of the protected bounds.
     * @param right The right side of the protected bounds.
     * @param bottom The bottom side of the protected bounds.
     */
    public void setContentDrawBounds(int left, int top, int right, int bottom) {
        nSetContentDrawBounds(mNativeProxy, left, top, right, bottom);
    }

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
        void onHardwarePreDraw(DisplayListCanvas canvas);

        /**
         * Invoked after a view is drawn by a hardware renderer.
         * It is safe to invoke drawing commands from this method.
         *
         * @param canvas The Canvas used to render the view.
         */
        void onHardwarePostDraw(DisplayListCanvas canvas);
    }

    /**
     *  Indicates that the content drawn by HardwareDrawCallbacks needs to
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
     * @param callbacks Callbacks invoked when drawing happens.
     */
    void draw(View view, AttachInfo attachInfo, HardwareDrawCallbacks callbacks) {
        attachInfo.mIgnoreDirtyState = true;

        final Choreographer choreographer = attachInfo.mViewRootImpl.mChoreographer;
        choreographer.mFrameInfo.markDrawStart();

        updateRootDisplayList(view, callbacks);

        attachInfo.mIgnoreDirtyState = false;

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

        final long[] frameInfo = choreographer.mFrameInfo.mFrameInfo;
        int syncResult = nSyncAndDrawFrame(mNativeProxy, frameInfo, frameInfo.length);
        if ((syncResult & SYNC_LOST_SURFACE_REWARD_IF_FOUND) != 0) {
            setEnabled(false);
            attachInfo.mViewRootImpl.mSurface.release();
            // Invalidate since we failed to draw. This should fetch a Surface
            // if it is still needed or do nothing if we are no longer drawing
            attachInfo.mViewRootImpl.invalidate();
        }
        if ((syncResult & SYNC_INVALIDATE_REQUIRED) != 0) {
            attachInfo.mViewRootImpl.invalidate();
        }
    }

    static void invokeFunctor(long functor, boolean waitForCompletion) {
        nInvokeFunctor(functor, waitForCompletion);
    }

    /**
     * Creates a new hardware layer. A hardware layer built by calling this
     * method will be treated as a texture layer, instead of as a render target.
     *
     * @return A hardware layer
     */
    HardwareLayer createTextureLayer() {
        long layer = nCreateTextureLayer(mNativeProxy);
        return HardwareLayer.adoptTextureLayer(this, layer);
    }


    void buildLayer(RenderNode node) {
        nBuildLayer(mNativeProxy, node.getNativeDisplayList());
    }


    boolean copyLayerInto(final HardwareLayer layer, final Bitmap bitmap) {
        return nCopyLayerInto(mNativeProxy,
                layer.getDeferredLayerUpdater(), bitmap);
    }

    /**
     * Indicates that the specified hardware layer needs to be updated
     * as soon as possible.
     *
     * @param layer The hardware layer that needs an update
     */
    void pushLayerUpdate(HardwareLayer layer) {
        nPushLayerUpdate(mNativeProxy, layer.getDeferredLayerUpdater());
    }

    /**
     * Tells the HardwareRenderer that the layer is destroyed. The renderer
     * should remove the layer from any update queues.
     */
    void onLayerDestroyed(HardwareLayer layer) {
        nCancelLayerUpdate(mNativeProxy, layer.getDeferredLayerUpdater());
    }

    /**
     * Optional, sets the name of the renderer. Useful for debugging purposes.
     *
     * @param name The name of this renderer, can be null
     */
    void setName(String name) {
        nSetName(mNativeProxy, name);
    }

    /**
     * Blocks until all previously queued work has completed.
     */
    void fence() {
        nFence(mNativeProxy);
    }

    /**
     * Prevents any further drawing until draw() is called. This is a signal
     * that the contents of the RenderNode tree are no longer safe to play back.
     * In practice this usually means that there are Functor pointers in the
     * display list that are no longer valid.
     */
    void stopDrawing() {
        nStopDrawing(mNativeProxy);
    }

    /**
     * Called by {@link ViewRootImpl} when a new performTraverals is scheduled.
     */
    public void notifyFramePending() {
        nNotifyFramePending(mNativeProxy);
    }


    void registerAnimatingRenderNode(RenderNode animator) {
        nRegisterAnimatingRenderNode(mRootNode.mNativeRenderNode, animator.mNativeRenderNode);
    }

    void registerVectorDrawableAnimator(
        AnimatedVectorDrawable.VectorDrawableAnimatorRT animator) {
        nRegisterVectorDrawableAnimator(mRootNode.mNativeRenderNode,
                animator.getAnimatorNativePtr());
    }

    public void serializeDisplayListTree() {
        nSerializeDisplayListTree(mNativeProxy);
    }

    public static int copySurfaceInto(Surface surface, Bitmap bitmap) {
        return nCopySurfaceInto(surface, bitmap);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            nDeleteProxy(mNativeProxy);
            mNativeProxy = 0;
        } finally {
            super.finalize();
        }
    }

    private static class ProcessInitializer {
        static ProcessInitializer sInstance = new ProcessInitializer();
        private static IBinder sProcToken;

        private boolean mInitialized = false;

        private ProcessInitializer() {}

        synchronized void init(Context context, long renderProxy) {
            if (mInitialized) return;
            mInitialized = true;
            initSched(context, renderProxy);
            initGraphicsStats(context, renderProxy);
            initAssetAtlas(context, renderProxy);
        }

        private static void initSched(Context context, long renderProxy) {
            try {
                int tid = nGetRenderThreadTid(renderProxy);
                ActivityManagerNative.getDefault().setRenderThread(tid);
            } catch (Throwable t) {
                Log.w(LOG_TAG, "Failed to set scheduler for RenderThread", t);
            }
        }

        private static void initGraphicsStats(Context context, long renderProxy) {
            try {
                IBinder binder = ServiceManager.getService("graphicsstats");
                if (binder == null) return;
                IGraphicsStats graphicsStatsService = IGraphicsStats.Stub
                        .asInterface(binder);
                sProcToken = new Binder();
                final String pkg = context.getApplicationInfo().packageName;
                ParcelFileDescriptor pfd = graphicsStatsService.
                        requestBufferForProcess(pkg, sProcToken);
                nSetProcessStatsBuffer(renderProxy, pfd.getFd());
                pfd.close();
            } catch (Throwable t) {
                Log.w(LOG_TAG, "Could not acquire gfx stats buffer", t);
            }
        }

        private static void initAssetAtlas(Context context, long renderProxy) {
            IBinder binder = ServiceManager.getService("assetatlas");
            if (binder == null) return;

            IAssetAtlas atlas = IAssetAtlas.Stub.asInterface(binder);
            try {
                if (atlas.isCompatible(android.os.Process.myPpid())) {
                    GraphicBuffer buffer = atlas.getBuffer();
                    if (buffer != null) {
                        long[] map = atlas.getMap();
                        if (map != null) {
                            nSetAtlas(renderProxy, buffer, map);
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
    }

    void addFrameMetricsObserver(FrameMetricsObserver observer) {
        long nativeObserver = nAddFrameMetricsObserver(mNativeProxy, observer);
        observer.mNative = new VirtualRefBasePtr(nativeObserver);
    }

    void removeFrameMetricsObserver(FrameMetricsObserver observer) {
        nRemoveFrameMetricsObserver(mNativeProxy, observer.mNative.get());
        observer.mNative = null;
    }

    static native void setupShadersDiskCache(String cacheFile);

    private static native void nSetAtlas(long nativeProxy, GraphicBuffer buffer, long[] map);
    private static native void nSetProcessStatsBuffer(long nativeProxy, int fd);
    private static native int nGetRenderThreadTid(long nativeProxy);

    private static native long nCreateRootRenderNode();
    private static native long nCreateProxy(boolean translucent, long rootRenderNode);
    private static native void nDeleteProxy(long nativeProxy);

    private static native boolean nLoadSystemProperties(long nativeProxy);
    private static native void nSetName(long nativeProxy, String name);

    private static native void nInitialize(long nativeProxy, Surface window);
    private static native void nUpdateSurface(long nativeProxy, Surface window);
    private static native boolean nPauseSurface(long nativeProxy, Surface window);
    private static native void nSetStopped(long nativeProxy, boolean stopped);
    private static native void nSetup(long nativeProxy, int width, int height,
            float lightRadius, int ambientShadowAlpha, int spotShadowAlpha);
    private static native void nSetLightCenter(long nativeProxy,
            float lightX, float lightY, float lightZ);
    private static native void nSetOpaque(long nativeProxy, boolean opaque);
    private static native int nSyncAndDrawFrame(long nativeProxy, long[] frameInfo, int size);
    private static native void nDestroy(long nativeProxy, long rootRenderNode);
    private static native void nRegisterAnimatingRenderNode(long rootRenderNode, long animatingNode);
    private static native void nRegisterVectorDrawableAnimator(long rootRenderNode, long animator);

    private static native void nInvokeFunctor(long functor, boolean waitForCompletion);

    private static native long nCreateTextureLayer(long nativeProxy);
    private static native void nBuildLayer(long nativeProxy, long node);
    private static native boolean nCopyLayerInto(long nativeProxy, long layer, Bitmap bitmap);
    private static native void nPushLayerUpdate(long nativeProxy, long layer);
    private static native void nCancelLayerUpdate(long nativeProxy, long layer);
    private static native void nDetachSurfaceTexture(long nativeProxy, long layer);

    private static native void nDestroyHardwareResources(long nativeProxy);
    private static native void nTrimMemory(int level);
    private static native void nOverrideProperty(String name, String value);

    private static native void nFence(long nativeProxy);
    private static native void nStopDrawing(long nativeProxy);
    private static native void nNotifyFramePending(long nativeProxy);

    private static native void nSerializeDisplayListTree(long nativeProxy);

    private static native void nDumpProfileInfo(long nativeProxy, FileDescriptor fd,
            @DumpFlags int dumpFlags);
    private static native void nDumpProfileData(byte[] data, FileDescriptor fd);

    private static native void nAddRenderNode(long nativeProxy, long rootRenderNode,
             boolean placeFront);
    private static native void nRemoveRenderNode(long nativeProxy, long rootRenderNode);
    private static native void nDrawRenderNode(long nativeProxy, long rootRenderNode);
    private static native void nSetContentDrawBounds(long nativeProxy, int left,
             int top, int right, int bottom);

    private static native long nAddFrameMetricsObserver(long nativeProxy, FrameMetricsObserver observer);
    private static native void nRemoveFrameMetricsObserver(long nativeProxy, long nativeObserver);

    private static native int nCopySurfaceInto(Surface surface, Bitmap bitmap);
}
