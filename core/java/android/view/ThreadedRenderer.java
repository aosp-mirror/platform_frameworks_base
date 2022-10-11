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
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.BLASTBufferQueue;
import android.graphics.FrameInfo;
import android.graphics.HardwareRenderer;
import android.graphics.Picture;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Log;
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

    public static int EGL_CONTEXT_PRIORITY_REALTIME_NV = 0x3357;
    public static int EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101;
    public static int EGL_CONTEXT_PRIORITY_MEDIUM_IMG = 0x3102;
    public static int EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103;

    /**
     * Further threaded renderer disabling for the system process.
     *
     * @hide
     */
    public static boolean sRendererEnabled = true;

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

    /**
     * Initialize HWUI for being in a system process like system_server
     * Should not be called in non-system processes
     */
    public static void initForSystemProcess() {
        // The system process on low-memory devices do not get to use hardware
        // accelerated drawing, since this can add too much overhead to the
        // process.
        if (!ActivityManager.isHighEndGfx()) {
            sRendererEnabled = false;
        } else {
            enableForegroundTrimming();
        }
    }

    /**
     * Creates a threaded renderer using OpenGL.
     *
     * @param translucent True if the surface is translucent, false otherwise
     *
     * @return A threaded renderer backed by OpenGL.
     */
    public static ThreadedRenderer create(Context context, boolean translucent, String name) {
        return new ThreadedRenderer(context, translucent, name);
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

    // Light properties specified by the theme.
    private final float mLightY;
    private final float mLightZ;
    private final float mLightRadius;

    private boolean mInitialized = false;
    private boolean mRootNodeNeedsUpdate;

    private boolean mEnabled;
    private boolean mRequested = true;

    /**
     * This child class exists to break ownership cycles. ViewRootImpl owns a ThreadedRenderer
     * which owns a WebViewOverlayProvider. WebViewOverlayProvider will in turn be set as
     * the listener for HardwareRenderer callbacks. By keeping this a child class, there are
     * no cycles in the chain. The ThreadedRenderer will remain GC-able if any callbacks are
     * still outstanding, which will in turn release any JNI references to WebViewOverlayProvider.
     */
    private static final class WebViewOverlayProvider implements
            PrepareSurfaceControlForWebviewCallback, ASurfaceTransactionCallback {
        private static final boolean sOverlaysAreEnabled =
                HardwareRenderer.isWebViewOverlaysEnabled();
        private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
        private boolean mHasWebViewOverlays = false;
        private BLASTBufferQueue mBLASTBufferQueue;
        private SurfaceControl mSurfaceControl;

        public boolean setSurfaceControlOpaque(boolean opaque) {
            synchronized (this) {
                if (mHasWebViewOverlays) return false;
                mTransaction.setOpaque(mSurfaceControl, opaque).apply();
            }
            return opaque;
        }

        public boolean shouldEnableOverlaySupport() {
            return sOverlaysAreEnabled && mSurfaceControl != null && mBLASTBufferQueue != null;
        }

        public void setSurfaceControl(SurfaceControl surfaceControl) {
            synchronized (this) {
                mSurfaceControl = surfaceControl;
                if (mSurfaceControl != null && mHasWebViewOverlays) {
                    mTransaction.setOpaque(surfaceControl, false).apply();
                }
            }
        }

        public void setBLASTBufferQueue(BLASTBufferQueue bufferQueue) {
            synchronized (this) {
                mBLASTBufferQueue = bufferQueue;
            }
        }

        @Override
        public void prepare() {
            synchronized (this) {
                mHasWebViewOverlays = true;
                if (mSurfaceControl != null) {
                    mTransaction.setOpaque(mSurfaceControl, false).apply();
                }
            }
        }

        @Override
        public boolean onMergeTransaction(long nativeTransactionObj,
                long aSurfaceControlNativeObj, long frameNr) {
            synchronized (this) {
                if (mBLASTBufferQueue == null) {
                    return false;
                } else {
                    mBLASTBufferQueue.mergeWithNextTransaction(nativeTransactionObj, frameNr);
                    return true;
                }
            }
        }
    }

    private final WebViewOverlayProvider mWebViewOverlayProvider = new WebViewOverlayProvider();
    private boolean mWebViewOverlaysEnabled = false;

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
     * Remove a frame drawing callback that was added via
     * {@link #registerRtFrameCallback(FrameDrawingCallback)}
     *
     * @param callback The callback to unregister.
     */
    void unregisterRtFrameCallback(@NonNull FrameDrawingCallback callback) {
        if (mNextRtFrameCallbacks == null) {
            return;
        }
        mNextRtFrameCallbacks.remove(callback);
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
            mInsetLeft = surfaceInsets.left;
            mInsetTop = surfaceInsets.top;
            mSurfaceWidth = width + mInsetLeft + surfaceInsets.right;
            mSurfaceHeight = height + mInsetTop + surfaceInsets.bottom;

            // If the surface has insets, it can't be opaque.
            setOpaque(false);
        } else {
            mInsetLeft = 0;
            mInsetTop = 0;
            mSurfaceWidth = width;
            mSurfaceHeight = height;
        }

        mRootNode.setLeftTopRightBottom(-mInsetLeft, -mInsetTop, mSurfaceWidth, mSurfaceHeight);

        setLightCenter(attachInfo);
    }

    /**
     * Whether or not the renderer owns the SurfaceControl's opacity. If true, use
     * {@link #setSurfaceControlOpaque(boolean)} to update the opacity
     */
    public boolean rendererOwnsSurfaceControlOpacity() {
        return mWebViewOverlayProvider.mSurfaceControl != null;
    }

    /**
     * Sets the SurfaceControl's opacity that this HardwareRenderer is rendering onto. The renderer
     * may opt to override the opacity, and will return the value that is ultimately set
     *
     * @return true if the surface is opaque, false otherwise
     *
     * @hide
     */
    public boolean setSurfaceControlOpaque(boolean opaque) {
        return mWebViewOverlayProvider.setSurfaceControlOpaque(opaque);
    }

    private void updateWebViewOverlayCallbacks() {
        boolean shouldEnable = mWebViewOverlayProvider.shouldEnableOverlaySupport();
        if (shouldEnable != mWebViewOverlaysEnabled) {
            mWebViewOverlaysEnabled = shouldEnable;
            if (shouldEnable) {
                setASurfaceTransactionCallback(mWebViewOverlayProvider);
                setPrepareSurfaceControlForWebviewCallback(mWebViewOverlayProvider);
            } else {
                setASurfaceTransactionCallback(null);
                setPrepareSurfaceControlForWebviewCallback(null);
            }
        }
    }

    @Override
    public void setSurfaceControl(@Nullable SurfaceControl surfaceControl) {
        super.setSurfaceControl(surfaceControl);
        mWebViewOverlayProvider.setSurfaceControl(surfaceControl);
        updateWebViewOverlayCallbacks();
    }

    /**
     * Sets the BLASTBufferQueue being used for rendering. This is required to be specified
     * for WebView overlay support
     */
    public void setBlastBufferQueue(@Nullable BLASTBufferQueue blastBufferQueue) {
        mWebViewOverlayProvider.setBLASTBufferQueue(blastBufferQueue);
        updateWebViewOverlayCallbacks();
    }

    /**
     * Updates the light position based on the position of the window.
     *
     * @param attachInfo Information about the window.
     */
    void setLightCenter(AttachInfo attachInfo) {
        // Adjust light position for window offsets.
        DisplayMetrics displayMetrics = new DisplayMetrics();
        attachInfo.mDisplay.getRealMetrics(displayMetrics);
        final float lightX = displayMetrics.widthPixels / 2f - attachInfo.mWindowLeft;
        final float lightY = mLightY - attachInfo.mWindowTop;
        // To prevent shadow distortion on larger screens, scale the z position of the light source
        // relative to the smallest screen dimension.
        final float zRatio = Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels)
                / (450f * displayMetrics.density);
        final float zWeightedAdjustment = (zRatio + 2) / 3f;
        final float lightZ = mLightZ * zWeightedAdjustment;

        setLightSourceGeometry(lightX, lightY, lightZ, mLightRadius);
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

    private static int dumpArgsToFlags(String[] args) {
        // If there's no arguments, eg 'dumpsys gfxinfo', then dump everything.
        // If there's a targetted package, eg 'dumpsys gfxinfo com.android.systemui', then only
        // dump the summary information
        if (args == null || args.length == 0) {
            return FLAG_DUMP_ALL;
        }
        int flags = 0;
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
        return flags;
    }

    /** @hide */
    public static void handleDumpGfxInfo(FileDescriptor fd, String[] args) {
        dumpGlobalProfileInfo(fd, dumpArgsToFlags(args));
        WindowManagerGlobal.getInstance().dumpGfxInfo(fd, args);
    }

    /**
     * Outputs extra debugging information in the specified file descriptor.
     */
    void dumpGfxInfo(PrintWriter pw, FileDescriptor fd, String[] args) {
        pw.flush();
        dumpProfileInfo(fd, dumpArgsToFlags(args));
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
            setFrameCallback(new FrameDrawingCallback() {
                @Override
                public void onFrameDraw(long frame) {
                }

                @Override
                public FrameCommitCallback onFrameDraw(int syncResult, long frame) {
                    ArrayList<FrameCommitCallback> frameCommitCallbacks = new ArrayList<>();
                    for (int i = 0; i < frameCallbacks.size(); ++i) {
                        FrameCommitCallback frameCommitCallback = frameCallbacks.get(i)
                                .onFrameDraw(syncResult, frame);
                        if (frameCommitCallback != null) {
                            frameCommitCallbacks.add(frameCommitCallback);
                        }
                    }

                    if (frameCommitCallbacks.isEmpty()) {
                        return null;
                    }

                    return didProduceBuffer -> {
                        for (int i = 0; i < frameCommitCallbacks.size(); ++i) {
                            frameCommitCallbacks.get(i).onFrameCommit(didProduceBuffer);
                        }
                    };
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
        attachInfo.mViewRootImpl.mViewFrameInfo.markDrawStart();

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

        final FrameInfo frameInfo = attachInfo.mViewRootImpl.getUpdatedFrameInfo();

        int syncResult = syncAndDrawFrame(frameInfo);
        if ((syncResult & SYNC_LOST_SURFACE_REWARD_IF_FOUND) != 0) {
            Log.w("OpenGLRenderer", "Surface lost, forcing relayout");
            // We lost our surface. For a relayout next frame which should give us a new
            // surface from WindowManager, which hopefully will work.
            attachInfo.mViewRootImpl.mForceNextWindowRelayout = true;
            attachInfo.mViewRootImpl.requestLayout();
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
            DisplayMetrics displayMetrics = new DisplayMetrics();
            display.getRealMetrics(displayMetrics);
            final float lightX = displayMetrics.widthPixels / 2f - windowLeft;
            final float lightY = mLightY - windowTop;
            // To prevent shadow distortion on larger screens, scale the z position of the light
            // source relative to the smallest screen dimension.
            final float zRatio = Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels)
                    / (450f * displayMetrics.density);
            final float zWeightedAdjustment = (zRatio + 2) / 3f;
            final float lightZ = mLightZ * zWeightedAdjustment;

            setLightSourceGeometry(lightX, lightY, lightZ, mLightRadius);
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
