/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.graphics;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.TimeUtils;
import android.view.Display;
import android.view.Display.Mode;
import android.view.IGraphicsStats;
import android.view.IGraphicsStatsCallback;
import android.view.NativeVectorDrawableAnimator;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.animation.AnimationUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import sun.misc.Cleaner;

/**
 * <p>Creates an instance of a hardware-accelerated renderer. This is used to render a scene built
 * from {@link RenderNode}'s to an output {@link android.view.Surface}. There can be as many
 * HardwareRenderer instances as desired.</p>
 *
 * <h3>Resources & lifecycle</h3>
 *
 * <p>All HardwareRenderer instances share a common render thread. The render thread contains
 * the GPU context & resources necessary to do GPU-accelerated rendering. As such, the first
 * HardwareRenderer created comes with the cost of also creating the associated GPU contexts,
 * however each incremental HardwareRenderer thereafter is fairly cheap. The expected usage
 * is to have a HardwareRenderer instance for every active {@link Surface}. For example
 * when an Activity shows a Dialog the system internally will use 2 hardware renderers, both
 * of which may be drawing at the same time.</p>
 *
 * <p>NOTE: Due to the shared, cooperative nature of the render thread it is critical that
 * any {@link Surface} used must have a prompt, reliable consuming side. System-provided
 * consumers such as {@link android.view.SurfaceView},
 * {@link android.view.Window#takeSurface(SurfaceHolder.Callback2)},
 * or {@link android.view.TextureView} all fit this requirement. However if custom consumers
 * are used such as when using {@link SurfaceTexture} or {@link android.media.ImageReader}
 * it is the app's responsibility to ensure that they consume updates promptly and rapidly.
 * Failure to do so will cause the render thread to stall on that surface, blocking all
 * HardwareRenderer instances.</p>
 */
public class HardwareRenderer {
    private static final String LOG_TAG = "HardwareRenderer";

    // Keep in sync with DrawFrameTask.h SYNC_* flags
    /**
     * Nothing interesting to report. Sync & draw kicked off
     */
    public static final int SYNC_OK = 0;

    /**
     * The renderer is requesting a redraw. This can occur if there's an animation that's running
     * in the RenderNode tree and the hardware renderer is unable to self-animate.
     *
     * <p>If this is returned from syncAndDraw the expectation is that syncAndDraw
     * will be called again on the next vsync signal.
     */
    public static final int SYNC_REDRAW_REQUESTED = 1 << 0;

    /**
     * The hardware renderer no longer has a valid {@link android.view.Surface} to render to.
     * This can happen if {@link Surface#release()} was called. The user should no longer
     * attempt to call syncAndDraw until a new surface has been provided by calling
     * setSurface.
     *
     * <p>Spoiler: the reward is GPU-accelerated drawing, better find that Surface!
     */
    public static final int SYNC_LOST_SURFACE_REWARD_IF_FOUND = 1 << 1;

    /**
     * The hardware renderer has been set to a "stopped" state. If this is returned then the
     * rendering content has been synced, however a frame was not produced.
     */
    public static final int SYNC_CONTEXT_IS_STOPPED = 1 << 2;

    /**
     * The content was synced but the renderer has declined to produce a frame in this vsync
     * interval. This can happen if a frame was already drawn in this vsync or if the renderer
     * is outrunning the frame consumer. The renderer will internally re-schedule itself
     * to render a frame in the next vsync signal, so the caller does not need to do anything
     * in response to this signal.
     */
    public static final int SYNC_FRAME_DROPPED = 1 << 3;

    /** @hide */
    @IntDef(value = {
            SYNC_OK, SYNC_REDRAW_REQUESTED, SYNC_LOST_SURFACE_REWARD_IF_FOUND,
            SYNC_CONTEXT_IS_STOPPED, SYNC_FRAME_DROPPED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SyncAndDrawResult {
    }

    /** @hide */
    public static final int FLAG_DUMP_FRAMESTATS = 1 << 0;
    /** @hide */
    public static final int FLAG_DUMP_RESET = 1 << 1;
    /** @hide */
    public static final int FLAG_DUMP_ALL = FLAG_DUMP_FRAMESTATS;

    /** @hide */
    @IntDef(flag = true, prefix = {"FLAG_DUMP_"}, value = {
            FLAG_DUMP_FRAMESTATS,
            FLAG_DUMP_RESET
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DumpFlags {
    }

    /**
     * Name of the file that holds the shaders cache.
     */
    private static final String CACHE_PATH_SHADERS = "com.android.opengl.shaders_cache";
    private static final String CACHE_PATH_SKIASHADERS = "com.android.skia.shaders_cache";

    private static int sDensityDpi = 0;

    private final long mNativeProxy;
    /** @hide */
    protected RenderNode mRootNode;
    private boolean mOpaque = true;
    private boolean mForceDark = false;
    private @ActivityInfo.ColorMode int mColorMode = ActivityInfo.COLOR_MODE_DEFAULT;

    /**
     * Creates a new instance of a HardwareRenderer. The HardwareRenderer will default
     * to opaque with no light source configured.
     */
    public HardwareRenderer() {
        ProcessInitializer.sInstance.initUsingContext();
        mRootNode = RenderNode.adopt(nCreateRootRenderNode());
        mRootNode.setClipToBounds(false);
        mNativeProxy = nCreateProxy(!mOpaque, mRootNode.mNativeRenderNode);
        if (mNativeProxy == 0) {
            throw new OutOfMemoryError("Unable to create hardware renderer");
        }
        Cleaner.create(this, new DestroyContextRunnable(mNativeProxy));
        ProcessInitializer.sInstance.init(mNativeProxy);
    }

    /**
     * Destroys the rendering context of this HardwareRenderer. This destroys the resources
     * associated with this renderer and releases the currently set {@link Surface}. This must
     * be called when this HardwareRenderer is no longer needed.
     *
     * <p>The renderer may be restored from this state by setting a new {@link Surface}, setting
     * new rendering content with {@link #setContentRoot(RenderNode)}, and resuming
     * rendering by issuing a new {@link FrameRenderRequest}.
     *
     * <p>It is recommended to call this in response to callbacks such as
     * {@link android.view.SurfaceHolder.Callback#surfaceDestroyed(SurfaceHolder)}.
     *
     * <p>Note that if there are any outstanding frame commit callbacks they may never being
     * invoked if the frame was deferred to a later vsync.
     */
    public void destroy() {
        nDestroy(mNativeProxy, mRootNode.mNativeRenderNode);
    }

    /**
     * Sets a name for this renderer. This is used to identify this renderer instance
     * when reporting debug information such as the per-window frame time metrics
     * reported by 'adb shell dumpsys gfxinfo [package] framestats'
     *
     * @param name The debug name to use for this HardwareRenderer instance
     */
    public void setName(@NonNull String name) {
        nSetName(mNativeProxy, name);
    }

    /**
     * Sets the center of the light source. The light source point controls the directionality
     * and shape of shadows rendered by RenderNode Z & elevation.
     *
     * <p>The platform's recommendation is to set lightX to 'displayWidth / 2f - windowLeft', set
     * lightY to 0 - windowTop, lightZ set to 600dp, and lightRadius to 800dp.
     *
     * <p>The light source should be setup both as part of initial configuration, and whenever
     * the window moves to ensure the light source stays anchored in display space instead
     * of in window space.
     *
     * <p>This must be set at least once along with {@link #setLightSourceAlpha(float, float)}
     * before shadows will work.
     *
     * @param lightX      The X position of the light source
     * @param lightY      The Y position of the light source
     * @param lightZ      The Z position of the light source. Must be >= 0.
     * @param lightRadius The radius of the light source. Smaller radius will have sharper edges,
     *                    larger radius will have softer shadows.
     */
    public void setLightSourceGeometry(float lightX, float lightY, float lightZ,
            float lightRadius) {
        validateFinite(lightX, "lightX");
        validateFinite(lightY, "lightY");
        validatePositive(lightZ, "lightZ");
        validatePositive(lightRadius, "lightRadius");
        nSetLightGeometry(mNativeProxy, lightX, lightY, lightZ, lightRadius);
    }

    /**
     * Configures the ambient & spot shadow alphas. This is the alpha used when the shadow
     * has max alpha, and ramps down from the values provided to zero.
     *
     * <p>These values are typically provided by the current theme, see
     * {@link android.R.attr#spotShadowAlpha} and {@link android.R.attr#ambientShadowAlpha}.
     *
     * <p>This must be set at least once along with
     * {@link #setLightSourceGeometry(float, float, float, float)} before shadows will work.
     *
     * @param ambientShadowAlpha The alpha for the ambient shadow. If unsure, a reasonable default
     *                           is 0.039f.
     * @param spotShadowAlpha    The alpha for the spot shadow. If unsure, a reasonable default is
     *                           0.19f.
     */
    public void setLightSourceAlpha(@FloatRange(from = 0.0f, to = 1.0f) float ambientShadowAlpha,
            @FloatRange(from = 0.0f, to = 1.0f) float spotShadowAlpha) {
        validateAlpha(ambientShadowAlpha, "ambientShadowAlpha");
        validateAlpha(spotShadowAlpha, "spotShadowAlpha");
        nSetLightAlpha(mNativeProxy, ambientShadowAlpha, spotShadowAlpha);
    }

    /**
     * Sets the content root to render. It is not necessary to call this whenever the content
     * recording changes. Any mutations to the RenderNode content, or any of the RenderNode's
     * contained within the content node, will be applied whenever a new {@link FrameRenderRequest}
     * is issued via {@link #createRenderRequest()} and {@link FrameRenderRequest#syncAndDraw()}.
     *
     * @param content The content to set as the root RenderNode. If null the content root is removed
     *                and the renderer will draw nothing.
     */
    public void setContentRoot(@Nullable RenderNode content) {
        RecordingCanvas canvas = mRootNode.beginRecording();
        if (content != null) {
            canvas.drawRenderNode(content);
        }
        mRootNode.endRecording();
    }

    /**
     * <p>The surface to render into. The surface is assumed to be associated with the display and
     * as such is still driven by vsync signals such as those from
     * {@link android.view.Choreographer} and that it has a native refresh rate matching that of
     * the display's (typically 60hz).</p>
     *
     * <p>NOTE: Due to the shared, cooperative nature of the render thread it is critical that
     * any {@link Surface} used must have a prompt, reliable consuming side. System-provided
     * consumers such as {@link android.view.SurfaceView},
     * {@link android.view.Window#takeSurface(SurfaceHolder.Callback2)},
     * or {@link android.view.TextureView} all fit this requirement. However if custom consumers
     * are used such as when using {@link SurfaceTexture} or {@link android.media.ImageReader}
     * it is the app's responsibility to ensure that they consume updates promptly and rapidly.
     * Failure to do so will cause the render thread to stall on that surface, blocking all
     * HardwareRenderer instances.</p>
     *
     * @param surface The surface to render into. If null then rendering will be stopped. If
     *                non-null then {@link Surface#isValid()} must be true.
     */
    public void setSurface(@Nullable Surface surface) {
        setSurface(surface, false);
    }

    /**
     * See {@link #setSurface(Surface)}
     *
     * @hide
     * @param discardBuffer determines whether the surface will attempt to preserve its contents
     *                      between frames.  If set to true the renderer will attempt to preserve
     *                      the contents of the buffer between frames if the implementation allows
     *                      it.  If set to false no attempt will be made to preserve the buffer's
     *                      contents between frames.
     */
    public void setSurface(@Nullable Surface surface, boolean discardBuffer) {
        if (surface != null && !surface.isValid()) {
            throw new IllegalArgumentException("Surface is invalid. surface.isValid() == false.");
        }
        nSetSurface(mNativeProxy, surface, discardBuffer);
    }

    /**
     * Sets the SurfaceControl to be used internally inside render thread
     * @hide
     * @param surfaceControl The surface control to pass to render thread in hwui.
     *        If null, any previous references held in render thread will be discarded.
    */
    public void setSurfaceControl(@Nullable SurfaceControl surfaceControl) {
        nSetSurfaceControl(mNativeProxy, surfaceControl != null ? surfaceControl.mNativeObject : 0);
    }

    /**
     * Sets the parameters that can be used to control a render request for a
     * {@link HardwareRenderer}. This is not thread-safe and must not be held on to for longer
     * than a single frame request.
     */
    public final class FrameRenderRequest {
        private FrameInfo mFrameInfo = new FrameInfo();
        private boolean mWaitForPresent;

        private FrameRenderRequest() { }

        private void reset() {
            mWaitForPresent = false;
            // Default to the animation time which, if choreographer is in play, will default to the
            // current vsync time. Otherwise it will be 'now'.
            mRenderRequest.setVsyncTime(
                    AnimationUtils.currentAnimationTimeMillis() * TimeUtils.NANOS_PER_MS);
        }

        /** @hide */
        public void setFrameInfo(FrameInfo info) {
            System.arraycopy(info.frameInfo, 0, mFrameInfo.frameInfo, 0, info.frameInfo.length);
        }

        /**
         * Sets the vsync time that represents the start point of this frame. Typically this
         * comes from {@link android.view.Choreographer.FrameCallback}. Other compatible time
         * sources include {@link System#nanoTime()}, however if the result is being displayed
         * on-screen then using {@link android.view.Choreographer} is strongly recommended to
         * ensure smooth animations.
         *
         * <p>If the clock source is not from a CLOCK_MONOTONIC source then any animations driven
         * directly by RenderThread will not be synchronized properly with the current frame.
         *
         * @param vsyncTime The vsync timestamp for this frame. The timestamp is in nanoseconds
         *                  and should come from a CLOCK_MONOTONIC source.
         *
         * @return this instance
         */
        public @NonNull FrameRenderRequest setVsyncTime(long vsyncTime) {
            // TODO(b/168552873): populate vsync Id once available to Choreographer public API
            mFrameInfo.setVsync(vsyncTime, vsyncTime, FrameInfo.INVALID_VSYNC_ID, Long.MAX_VALUE,
                    vsyncTime, -1);
            mFrameInfo.addFlags(FrameInfo.FLAG_SURFACE_CANVAS);
            return this;
        }

        /**
         * Adds a frame commit callback. This callback will be invoked when the current rendering
         * content has been rendered into a frame and submitted to the swap chain. The frame may
         * not currently be visible on the display when this is invoked, but it has been submitted.
         * This callback is useful in combination with {@link PixelCopy} to capture the current
         * rendered content of the UI reliably.
         *
         * @param executor The executor to run the callback on. It is strongly recommended that
         *                 this executor post to a different thread, as the calling thread is
         *                 highly sensitive to being blocked.
         * @param frameCommitCallback The callback to invoke when the frame content has been drawn.
         *                            Will be invoked on the given {@link Executor}.
         *
         * @return this instance
         */
        public @NonNull FrameRenderRequest setFrameCommitCallback(@NonNull Executor executor,
                @NonNull Runnable frameCommitCallback) {
            setFrameCompleteCallback(frameNr -> executor.execute(frameCommitCallback));
            return this;
        }

        /**
         * Sets whether or not {@link #syncAndDraw()} should block until the frame has been
         * presented. If this is true and {@link #syncAndDraw()} does not return
         * {@link #SYNC_FRAME_DROPPED} or an error then when {@link #syncAndDraw()} has returned
         * the frame has been submitted to the {@link Surface}. The default and typically
         * recommended value is false, as blocking for present will prevent pipelining from
         * happening, reducing overall throughput. This is useful for situations such as
         * {@link SurfaceHolder.Callback2#surfaceRedrawNeeded(SurfaceHolder)} where it is desired
         * to block until a frame has been presented to ensure first-frame consistency with
         * other Surfaces.
         *
         * @param shouldWait If true the next call to {@link #syncAndDraw()} will block until
         *                   completion.
         * @return this instance
         */
        public @NonNull FrameRenderRequest setWaitForPresent(boolean shouldWait) {
            mWaitForPresent = shouldWait;
            return this;
        }

        /**
         * Syncs the RenderNode tree to the render thread and requests a frame to be drawn. This
         * {@link FrameRenderRequest} instance should no longer be used after calling this method.
         * The system internally may reuse instances of {@link FrameRenderRequest} to reduce
         * allocation churn.
         *
         * @return The result of the sync operation.
         */
        @SyncAndDrawResult
        public int syncAndDraw() {
            int syncResult = syncAndDrawFrame(mFrameInfo);
            if (mWaitForPresent && (syncResult & SYNC_FRAME_DROPPED) == 0) {
                fence();
            }
            return syncResult;
        }
    }

    private FrameRenderRequest mRenderRequest = new FrameRenderRequest();

    /**
     * Returns a {@link FrameRenderRequest} that can be used to render a new frame. This is used
     * to synchronize the RenderNode content provided by {@link #setContentRoot(RenderNode)} with
     * the RenderThread and then renders a single frame to the Surface set with
     * {@link #setSurface(Surface)}.
     *
     * @return An instance of {@link FrameRenderRequest}. The instance may be reused for every
     * frame, so the caller should not hold onto it for longer than a single render request.
     */
    public @NonNull FrameRenderRequest createRenderRequest() {
        mRenderRequest.reset();
        return mRenderRequest;
    }

    /**
     * Syncs the RenderNode tree to the render thread and requests a frame to be drawn.
     *
     * @hide
     */
    @SyncAndDrawResult
    public int syncAndDrawFrame(@NonNull FrameInfo frameInfo) {
        return nSyncAndDrawFrame(mNativeProxy, frameInfo.frameInfo, frameInfo.frameInfo.length);
    }

    /**
     * Suspends any current rendering into the surface but do not do any destruction. This
     * is useful to temporarily suspend using the active Surface in order to do any Surface
     * mutations necessary.
     *
     * <p>Any subsequent draws will override the pause, resuming normal operation.
     *
     * @return true if there was an outstanding render request, false otherwise. If this is true
     * the caller should ensure that {@link #createRenderRequest()}
     * and {@link FrameRenderRequest#syncAndDraw()} is called at the soonest
     * possible time to resume normal operation.
     *
     * TODO Should this be exposed? ViewRootImpl needs it because it destroys the old
     * Surface before getting a new one. However things like SurfaceView will ensure that
     * the old surface remains un-destroyed until after a new frame has been produced with
     * the new surface.
     * @hide
     */
    public boolean pause() {
        return nPause(mNativeProxy);
    }

    /**
     * Hard stops rendering into the surface. If the renderer is stopped it will
     * block any attempt to render. Calls to {@link FrameRenderRequest#syncAndDraw()} will
     * still sync over the latest rendering content, however they will not render and instead
     * {@link #SYNC_CONTEXT_IS_STOPPED} will be returned.
     *
     * <p>If false is passed then rendering will resume as normal. Any pending rendering requests
     * will produce a new frame at the next vsync signal.
     *
     * <p>This is useful in combination with lifecycle events such as {@link Activity#onStop()}
     * and {@link Activity#onStart()}.
     *
     * @param stopped true to stop all rendering, false to resume
     * @hide
     */
    public void setStopped(boolean stopped) {
        nSetStopped(mNativeProxy, stopped);
    }

    /**
     * Hard stops rendering into the surface. If the renderer is stopped it will
     * block any attempt to render. Calls to {@link FrameRenderRequest#syncAndDraw()} will
     * still sync over the latest rendering content, however they will not render and instead
     * {@link #SYNC_CONTEXT_IS_STOPPED} will be returned.
     *
     * <p>This is useful in combination with lifecycle events such as {@link Activity#onStop()}.
     * See {@link #start()} for resuming rendering.
     */
    public void stop() {
        nSetStopped(mNativeProxy, true);
    }

    /**
     * Resumes rendering into the surface. Any pending rendering requests
     * will produce a new frame at the next vsync signal.
     *
     * <p>This is useful in combination with lifecycle events such as {@link Activity#onStart()}.
     * See {@link #stop()} for stopping rendering.
     */
    public void start() {
        nSetStopped(mNativeProxy, false);
    }

    /**
     * Destroys all the display lists associated with the current rendering content.
     * This includes releasing a reference to the current content root RenderNode. It will
     * therefore be necessary to call {@link #setContentRoot(RenderNode)} in order to resume
     * rendering after calling this, along with re-recording the display lists for the
     * RenderNode tree.
     *
     * <p>It is recommended, but not necessary, to use this in combination with lifecycle events
     * such as {@link Activity#onStop()} and {@link Activity#onStart()} or in response to
     * {@link android.content.ComponentCallbacks2#onTrimMemory(int)} signals such as
     * {@link android.content.ComponentCallbacks2#TRIM_MEMORY_UI_HIDDEN}
     *
     * See also {@link #stop()}.
     */
    public void clearContent() {
        nDestroyHardwareResources(mNativeProxy);
    }

    /**
     * Whether or not the force-dark feature should be used for this renderer.
     * @hide
     */
    public boolean setForceDark(boolean enable) {
        if (mForceDark != enable) {
            mForceDark = enable;
            nSetForceDark(mNativeProxy, enable);
            return true;
        }
        return false;
    }

    /**
     * Allocate buffers ahead of time to avoid allocation delays during rendering.
     *
     * <p>Typically a Surface will allocate buffers lazily. This is usually fine and reduces the
     * memory usage of Surfaces that render rarely or never hit triple buffering. However
     * for UI it can result in a slight bit of jank on first launch. This hint will
     * tell the HardwareRenderer that now is a good time to allocate the 3 buffers
     * necessary for typical rendering.
     *
     * <p>Must be called after a {@link Surface} has been set.
     *
     * TODO: Figure out if we even need/want this. Should HWUI just be doing this in response
     * to setSurface anyway? Vulkan swapchain makes this murky, so delay making it public
     * @hide
     */
    public void allocateBuffers() {
        nAllocateBuffers(mNativeProxy);
    }

    /**
     * Notifies the hardware renderer that a call to {@link FrameRenderRequest#syncAndDraw()} will
     * be coming soon. This is used to help schedule when RenderThread-driven animations will
     * happen as the renderer wants to avoid producing more than one frame per vsync signal.
     */
    public void notifyFramePending() {
        nNotifyFramePending(mNativeProxy);
    }

    /**
     * Change the HardwareRenderer's opacity. Will take effect on the next frame produced.
     *
     * <p>If the renderer is set to opaque it is the app's responsibility to ensure that the
     * content renders to every pixel of the Surface, otherwise corruption may result. Note that
     * this includes ensuring that the first draw of any given pixel does not attempt to blend
     * against the destination. If this is false then the hardware renderer will clear to
     * transparent at the start of every frame.
     *
     * @param opaque true if the content rendered is opaque, false if the renderer should clear
     *               to transparent before rendering
     */
    public void setOpaque(boolean opaque) {
        if (mOpaque != opaque) {
            mOpaque = opaque;
            nSetOpaque(mNativeProxy, mOpaque);
        }
    }

    /**
     * Whether or not the renderer is set to be opaque. See {@link #setOpaque(boolean)}
     *
     * @return true if the renderer is opaque, false otherwise
     */
    public boolean isOpaque() {
        return mOpaque;
    }

    /** @hide */
    public void setFrameCompleteCallback(FrameCompleteCallback callback) {
        nSetFrameCompleteCallback(mNativeProxy, callback);
    }

    /**
     * TODO: Public API this?
     *
     * @hide
     */
    public void addObserver(HardwareRendererObserver observer) {
        nAddObserver(mNativeProxy, observer.getNativeInstance());
    }

    /**
     * TODO: Public API this?
     *
     * @hide
     */
    public void removeObserver(HardwareRendererObserver observer) {
        nRemoveObserver(mNativeProxy, observer.getNativeInstance());
    }

    /**
     * Sets the desired color mode on this renderer. Whether or not the actual rendering
     * will use the requested colorMode depends on the hardware support for such rendering.
     *
     * @param colorMode The @{@link ActivityInfo.ColorMode} to request
     * @hide
     */
    public void setColorMode(@ActivityInfo.ColorMode int colorMode) {
        if (mColorMode != colorMode) {
            mColorMode = colorMode;
            nSetColorMode(mNativeProxy, colorMode);
        }
    }

    /**
     * Sets the colormode with the desired SDR white point.
     *
     * The white point only applies if the color mode is an HDR mode
     *
     * @hide
     */
    public void setColorMode(@ActivityInfo.ColorMode int colorMode, float whitePoint) {
        nSetSdrWhitePoint(mNativeProxy, whitePoint);
        mColorMode = colorMode;
        nSetColorMode(mNativeProxy, colorMode);
    }

    /**
     * Blocks until all previously queued work has completed.
     *
     * TODO: Only used for draw finished listeners, but the FrameCompleteCallback does that
     * better
     *
     * @hide
     */
    public void fence() {
        nFence(mNativeProxy);
    }

    /** @hide */
    public void registerAnimatingRenderNode(RenderNode animator) {
        nRegisterAnimatingRenderNode(mRootNode.mNativeRenderNode, animator.mNativeRenderNode);
    }

    /** @hide */
    public void registerVectorDrawableAnimator(NativeVectorDrawableAnimator animator) {
        nRegisterVectorDrawableAnimator(mRootNode.mNativeRenderNode,
                animator.getAnimatorNativePtr());
    }

    /**
     * Prevents any further drawing until {@link FrameRenderRequest#syncAndDraw()} is called.
     * This is a signal that the contents of the RenderNode tree are no longer safe to play back.
     * In practice this usually means that there are Functor pointers in the
     * display list that are no longer valid.
     *
     * TODO: Can we get webview off of this?
     *
     * @hide
     */
    public void stopDrawing() {
        nStopDrawing(mNativeProxy);
    }

    /**
     * Creates a new hardware layer. A hardware layer built by calling this
     * method will be treated as a texture layer, instead of as a render target.
     *
     * @return A hardware layer
     * @hide
     */
    public TextureLayer createTextureLayer() {
        long layer = nCreateTextureLayer(mNativeProxy);
        return TextureLayer.adoptTextureLayer(this, layer);
    }

    /**
     * Detaches the layer's surface texture from the GL context and releases
     * the texture id
     *
     * @hide
     */
    public void detachSurfaceTexture(long hardwareLayer) {
        nDetachSurfaceTexture(mNativeProxy, hardwareLayer);
    }


    /** @hide */
    public void buildLayer(RenderNode node) {
        if (node.hasDisplayList()) {
            nBuildLayer(mNativeProxy, node.mNativeRenderNode);
        }
    }

    /** @hide */
    public boolean copyLayerInto(final TextureLayer layer, final Bitmap bitmap) {
        return nCopyLayerInto(mNativeProxy, layer.getDeferredLayerUpdater(),
            bitmap.getNativeInstance());
    }

    /**
     * Indicates that the specified hardware layer needs to be updated
     * as soon as possible.
     *
     * @param layer The hardware layer that needs an update
     * @hide
     */
    public void pushLayerUpdate(TextureLayer layer) {
        nPushLayerUpdate(mNativeProxy, layer.getDeferredLayerUpdater());
    }

    /**
     * Tells the HardwareRenderer that the layer is destroyed. The renderer
     * should remove the layer from any update queues.
     *
     * @hide
     */
    public void onLayerDestroyed(TextureLayer layer) {
        nCancelLayerUpdate(mNativeProxy, layer.getDeferredLayerUpdater());
    }

    private ASurfaceTransactionCallback mASurfaceTransactionCallback;

    /** @hide */
    public void setASurfaceTransactionCallback(ASurfaceTransactionCallback callback) {
        // ensure callback is kept alive on the java side since weak ref is used in native code
        mASurfaceTransactionCallback = callback;
        nSetASurfaceTransactionCallback(mNativeProxy, callback);
    }

    private PrepareSurfaceControlForWebviewCallback mAPrepareSurfaceControlForWebviewCallback;

    /** @hide */
    public void setPrepareSurfaceControlForWebviewCallback(
            PrepareSurfaceControlForWebviewCallback callback) {
        // ensure callback is kept alive on the java side since weak ref is used in native code
        mAPrepareSurfaceControlForWebviewCallback = callback;
        nSetPrepareSurfaceControlForWebviewCallback(mNativeProxy, callback);
    }

    /** @hide */
    public void setFrameCallback(FrameDrawingCallback callback) {
        nSetFrameCallback(mNativeProxy, callback);
    }

    /**
     * Adds a rendernode to the renderer which can be drawn and changed asynchronously to the
     * rendernode of the UI thread.
     *
     * @param node       The node to add.
     * @param placeFront If true, the render node will be placed in front of the content node,
     *                   otherwise behind the content node.
     * @hide
     */
    public void addRenderNode(RenderNode node, boolean placeFront) {
        nAddRenderNode(mNativeProxy, node.mNativeRenderNode, placeFront);
    }

    /**
     * Only especially added render nodes can be removed.
     *
     * @param node The node which was added via addRenderNode which should get removed again.
     * @hide
     */
    public void removeRenderNode(RenderNode node) {
        nRemoveRenderNode(mNativeProxy, node.mNativeRenderNode);
    }

    /**
     * Draws a particular render node. If the node is not the content node, only the additional
     * nodes will get drawn and the content remains untouched.
     *
     * @param node The node to be drawn.
     * @hide
     */
    public void drawRenderNode(RenderNode node) {
        nDrawRenderNode(mNativeProxy, node.mNativeRenderNode);
    }

    /**
     * Loads system properties used by the renderer. This method is invoked
     * whenever system properties are modified. Implementations can use this
     * to trigger live updates of the renderer based on properties.
     *
     * @return True if a property has changed.
     * @hide
     */
    public boolean loadSystemProperties() {
        return nLoadSystemProperties(mNativeProxy);
    }

    /**
     * @hide
     */
    public void dumpProfileInfo(FileDescriptor fd, @DumpFlags int dumpFlags) {
        nDumpProfileInfo(mNativeProxy, fd, dumpFlags);
    }

    /**
     * To avoid unnecessary overdrawing of the main content all additionally passed render nodes
     * will be prevented to overdraw this area. It will be synchronized with the draw call.
     * This should be updated in the content view's draw call.
     *
     * @param left   The left side of the protected bounds.
     * @param top    The top side of the protected bounds.
     * @param right  The right side of the protected bounds.
     * @param bottom The bottom side of the protected bounds.
     * @hide
     */
    public void setContentDrawBounds(int left, int top, int right, int bottom) {
        nSetContentDrawBounds(mNativeProxy, left, top, right, bottom);
    }

    /** @hide */
    public void setPictureCaptureCallback(@Nullable PictureCapturedCallback callback) {
        nSetPictureCaptureCallback(mNativeProxy, callback);
    }

    /** called by native */
    static void invokePictureCapturedCallback(long picturePtr, PictureCapturedCallback callback) {
        Picture picture = new Picture(picturePtr);
        callback.onPictureCaptured(picture);
    }

   /**
     * Interface used to receive callbacks when Webview requests a surface control.
     *
     * @hide
     */
    public interface PrepareSurfaceControlForWebviewCallback {
        /**
         * Invoked when Webview calls to get a surface control.
         *
         */
        void prepare();
    }

    /**
     * Interface used to receive callbacks when a transaction needs to be merged.
     *
     * @hide
     */
    public interface ASurfaceTransactionCallback {
        /**
         * Invoked during a frame drawing.
         *
         * @param aSurfaceTranactionNativeObj the ASurfaceTransaction native object handle
         * @param aSurfaceControlNativeObj ASurfaceControl native object handle
         * @param frame The id of the frame being drawn.
         */
        boolean onMergeTransaction(long aSurfaceTranactionNativeObj,
                                long aSurfaceControlNativeObj, long frame);
    }

    /**
     * Interface used to receive callbacks when a frame is being drawn.
     *
     * @hide
     */
    public interface FrameDrawingCallback {
        /**
         * Invoked during a frame drawing.
         *
         * @param frame The id of the frame being drawn.
         */
        void onFrameDraw(long frame);
    }

    /**
     * Interface used to be notified when a frame has finished rendering
     *
     * @hide
     */
    public interface FrameCompleteCallback {
        /**
         * Invoked after a frame draw
         *
         * @param frameNr The id of the frame that was drawn.
         */
        void onFrameComplete(long frameNr);
    }

    /**
     * Interface for listening to picture captures
     * @hide
     */
    public interface PictureCapturedCallback {
        /** @hide */
        void onPictureCaptured(Picture picture);
    }

    private static void validateAlpha(float alpha, String argumentName) {
        if (!(alpha >= 0.0f && alpha <= 1.0f)) {
            throw new IllegalArgumentException(argumentName + " must be a valid alpha, "
                    + alpha + " is not in the range of 0.0f to 1.0f");
        }
    }

    private static void validatePositive(float f, String argumentName) {
        if (!(Float.isFinite(f) && f >= 0.0f)) {
            throw new IllegalArgumentException(argumentName
                    + " must be a finite positive, given=" + f);
        }
    }

    private static void validateFinite(float f, String argumentName) {
        if (!Float.isFinite(f)) {
            throw new IllegalArgumentException(argumentName + " must be finite, given=" + f);
        }
    }

    /**
     * b/68769804: For low FPS experiments.
     *
     * @hide
     */
    public static void setFPSDivisor(int divisor) {
        nHackySetRTAnimationsEnabled(divisor <= 1);
    }

    /**
     * Changes the OpenGL context priority if IMG_context_priority extension is available. Must be
     * called before any OpenGL context is created.
     *
     * @param priority The priority to use. Must be one of EGL_CONTEXT_PRIORITY_* values.
     * @hide
     */
    public static void setContextPriority(int priority) {
        nSetContextPriority(priority);
    }

    /**
     * Sets whether or not high contrast text rendering is enabled. The setting is global
     * but only affects content rendered after the change is made.
     *
     * @hide
     */
    public static void setHighContrastText(boolean highContrastText) {
        nSetHighContrastText(highContrastText);
    }

    /**
     * If set RenderThread will avoid doing any IPC using instead a fake vsync & DisplayInfo source
     *
     * @hide
     */
    public static void setIsolatedProcess(boolean isIsolated) {
        nSetIsolatedProcess(isIsolated);
        ProcessInitializer.sInstance.setIsolated(isIsolated);
    }

    /**
     * Sends device configuration changes to the render thread, for rendering profiling views.
     *
     * @hide
     */
    public static void sendDeviceConfigurationForDebugging(Configuration config) {
        if (config.densityDpi != Configuration.DENSITY_DPI_UNDEFINED
                && config.densityDpi != sDensityDpi) {
            sDensityDpi = config.densityDpi;
            nSetDisplayDensityDpi(config.densityDpi);
        }
    }

    /**
     * If set extra graphics debugging abilities will be enabled such as dumping skp
     *
     * @hide
     */
    public static void setDebuggingEnabled(boolean enable) {
        nSetDebuggingEnabled(enable);
    }

    /** @hide */
    public static int copySurfaceInto(Surface surface, Rect srcRect, Bitmap bitmap) {
        if (srcRect == null) {
            // Empty rect means entire surface
            return nCopySurfaceInto(surface, 0, 0, 0, 0, bitmap.getNativeInstance());
        } else {
            return nCopySurfaceInto(surface, srcRect.left, srcRect.top,
                    srcRect.right, srcRect.bottom, bitmap.getNativeInstance());
        }
    }

    /**
     * Creates a {@link android.graphics.Bitmap.Config#HARDWARE} bitmap from the given
     * RenderNode. Note that the RenderNode should be created as a root node (so x/y of 0,0), and
     * not the RenderNode from a View.
     *
     * @hide
     **/
    public static Bitmap createHardwareBitmap(RenderNode node, int width, int height) {
        return nCreateHardwareBitmap(node.mNativeRenderNode, width, height);
    }

    /**
     * Invoke this method when the system is running out of memory. This
     * method will attempt to recover as much memory as possible, based on
     * the specified hint.
     *
     * @param level Hint about the amount of memory that should be trimmed,
     *              see {@link android.content.ComponentCallbacks}
     * @hide
     */
    public static void trimMemory(int level) {
        nTrimMemory(level);
    }

    /** @hide */
    public static void overrideProperty(@NonNull String name, @NonNull String value) {
        if (name == null || value == null) {
            throw new IllegalArgumentException("name and value must be non-null");
        }
        nOverrideProperty(name, value);
    }

    /**
     * Sets the directory to use as a persistent storage for threaded rendering
     * resources.
     *
     * @param cacheDir A directory the current process can write to
     * @hide
     */
    public static void setupDiskCache(File cacheDir) {
        setupShadersDiskCache(new File(cacheDir, CACHE_PATH_SHADERS).getAbsolutePath(),
                new File(cacheDir, CACHE_PATH_SKIASHADERS).getAbsolutePath());
    }

    /** @hide */
    public static void setPackageName(String packageName) {
        ProcessInitializer.sInstance.setPackageName(packageName);
    }

    /**
     * Gets a context for process initialization
     *
     * TODO: Remove this once there is a static method for retrieving an application's context.
     *
     * @hide
     */
    public static void setContextForInit(Context context) {
        ProcessInitializer.sInstance.setContext(context);
    }

    private static final class DestroyContextRunnable implements Runnable {
        private final long mNativeInstance;

        DestroyContextRunnable(long nativeInstance) {
            mNativeInstance = nativeInstance;
        }

        @Override
        public void run() {
            nDeleteProxy(mNativeInstance);
        }
    }

    private static class ProcessInitializer {
        static ProcessInitializer sInstance = new ProcessInitializer();

        // Magic values from android/data_space.h
        private static final int INTERNAL_DATASPACE_SRGB = 142671872;
        private static final int INTERNAL_DATASPACE_DISPLAY_P3 = 143261696;
        private static final int INTERNAL_DATASPACE_SCRGB = 411107328;

        private enum Dataspace {
            DISPLAY_P3(ColorSpace.Named.DISPLAY_P3, INTERNAL_DATASPACE_DISPLAY_P3),
            SCRGB(ColorSpace.Named.EXTENDED_SRGB, INTERNAL_DATASPACE_SCRGB),
            SRGB(ColorSpace.Named.SRGB, INTERNAL_DATASPACE_SRGB);

            private final ColorSpace.Named mColorSpace;
            private final int mNativeDataspace;
            Dataspace(ColorSpace.Named colorSpace, int nativeDataspace) {
                this.mColorSpace = colorSpace;
                this.mNativeDataspace = nativeDataspace;
            }

            static Optional<Dataspace> find(ColorSpace colorSpace) {
                return Stream.of(Dataspace.values())
                        .filter(d -> ColorSpace.get(d.mColorSpace).equals(colorSpace))
                        .findFirst();
            }
        }

        private boolean mInitialized = false;
        private boolean mDisplayInitialized = false;

        private boolean mIsolated = false;
        private Context mContext;
        private String mPackageName;
        private IGraphicsStats mGraphicsStatsService;
        private IGraphicsStatsCallback mGraphicsStatsCallback = new IGraphicsStatsCallback.Stub() {
            @Override
            public void onRotateGraphicsStatsBuffer() throws RemoteException {
                rotateBuffer();
            }
        };

        private ProcessInitializer() {
        }

        synchronized void setPackageName(String name) {
            if (mInitialized) return;
            mPackageName = name;
        }

        synchronized void setIsolated(boolean isolated) {
            if (mInitialized) return;
            mIsolated = isolated;
        }

        synchronized void setContext(Context context) {
            if (mInitialized) return;
            mContext = context;
        }

        synchronized void init(long renderProxy) {
            if (mInitialized) return;
            mInitialized = true;

            initSched(renderProxy);
            initGraphicsStats();
        }

        private void initSched(long renderProxy) {
            try {
                int tid = nGetRenderThreadTid(renderProxy);
                ActivityManager.getService().setRenderThread(tid);
            } catch (Throwable t) {
                Log.w(LOG_TAG, "Failed to set scheduler for RenderThread", t);
            }
        }

        private void initGraphicsStats() {
            if (mPackageName == null) return;

            try {
                IBinder binder = ServiceManager.getService("graphicsstats");
                if (binder == null) return;
                mGraphicsStatsService = IGraphicsStats.Stub.asInterface(binder);
                requestBuffer();
            } catch (Throwable t) {
                Log.w(LOG_TAG, "Could not acquire gfx stats buffer", t);
            }
        }

        synchronized void initUsingContext() {
            if (mContext == null) return;

            initDisplayInfo();

            nSetIsHighEndGfx(ActivityManager.isHighEndGfx());
            // Defensively clear out the context in case we were passed a context that can leak
            // if we live longer than it, e.g. an activity context.
            mContext = null;
        }

        private void initDisplayInfo() {
            if (mDisplayInitialized) return;
            if (mIsolated) {
                mDisplayInitialized = true;
                return;
            }

            DisplayManager dm = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                Log.d(LOG_TAG, "Failed to find DisplayManager for display-based configuration");
                return;
            }

            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                Log.d(LOG_TAG, "Failed to find default display for display-based configuration");
                return;
            }

            Dataspace wideColorDataspace =
                    Optional.ofNullable(display.getPreferredWideGamutColorSpace())
                            .flatMap(Dataspace::find)
                            // Default to SRGB if the display doesn't support wide color
                            .orElse(Dataspace.SRGB);

            // Grab the physical screen dimensions from the active display mode
            // Strictly speaking the screen resolution may not always be constant - it is for
            // sizing the font cache for the underlying rendering thread. Since it's a
            // heuristic we don't need to be always 100% correct.
            Mode activeMode = display.getMode();
            nInitDisplayInfo(activeMode.getPhysicalWidth(), activeMode.getPhysicalHeight(),
                    display.getRefreshRate(), wideColorDataspace.mNativeDataspace,
                    display.getAppVsyncOffsetNanos(), display.getPresentationDeadlineNanos());

            mDisplayInitialized = true;
        }

        private void rotateBuffer() {
            nRotateProcessStatsBuffer();
            requestBuffer();
        }

        private void requestBuffer() {
            try {
                ParcelFileDescriptor pfd = mGraphicsStatsService
                        .requestBufferForProcess(mPackageName, mGraphicsStatsCallback);
                nSetProcessStatsBuffer(pfd.getFd());
                pfd.close();
            } catch (Throwable t) {
                Log.w(LOG_TAG, "Could not acquire gfx stats buffer", t);
            }
        }
    }

    /**
     * @hide
     */
    public static native void disableVsync();

    /**
     * Start render thread and initialize EGL or Vulkan.
     *
     * Initializing EGL involves loading and initializing the graphics driver. Some drivers take
     * several 10s of milliseconds to do this, so doing it on-demand when an app tries to render
     * its first frame adds directly to user-visible app launch latency.
     *
     * Should only be called after GraphicsEnvironment.chooseDriver().
     * @hide
     */
    public static native void preload();

    /**
     * @hide
     */
    public static native boolean isWebViewOverlaysEnabled();

    /** @hide */
    protected static native void setupShadersDiskCache(String cacheFile, String skiaCacheFile);

    private static native void nRotateProcessStatsBuffer();

    private static native void nSetProcessStatsBuffer(int fd);

    private static native int nGetRenderThreadTid(long nativeProxy);

    private static native long nCreateRootRenderNode();

    private static native long nCreateProxy(boolean translucent, long rootRenderNode);

    private static native void nDeleteProxy(long nativeProxy);

    private static native boolean nLoadSystemProperties(long nativeProxy);

    private static native void nSetName(long nativeProxy, String name);

    private static native void nSetSurface(long nativeProxy, Surface window, boolean discardBuffer);

    private static native void nSetSurfaceControl(long nativeProxy, long nativeSurfaceControl);

    private static native boolean nPause(long nativeProxy);

    private static native void nSetStopped(long nativeProxy, boolean stopped);

    private static native void nSetLightGeometry(long nativeProxy,
            float lightX, float lightY, float lightZ, float lightRadius);

    private static native void nSetLightAlpha(long nativeProxy, float ambientShadowAlpha,
            float spotShadowAlpha);

    private static native void nSetOpaque(long nativeProxy, boolean opaque);

    private static native void nSetColorMode(long nativeProxy, int colorMode);

    private static native void nSetSdrWhitePoint(long nativeProxy, float whitePoint);

    private static native void nSetIsHighEndGfx(boolean isHighEndGfx);

    private static native int nSyncAndDrawFrame(long nativeProxy, long[] frameInfo, int size);

    private static native void nDestroy(long nativeProxy, long rootRenderNode);

    private static native void nRegisterAnimatingRenderNode(long rootRenderNode,
            long animatingNode);

    private static native void nRegisterVectorDrawableAnimator(long rootRenderNode, long animator);

    private static native long nCreateTextureLayer(long nativeProxy);

    private static native void nBuildLayer(long nativeProxy, long node);

    private static native boolean nCopyLayerInto(long nativeProxy, long layer, long bitmapHandle);

    private static native void nPushLayerUpdate(long nativeProxy, long layer);

    private static native void nCancelLayerUpdate(long nativeProxy, long layer);

    private static native void nDetachSurfaceTexture(long nativeProxy, long layer);

    private static native void nDestroyHardwareResources(long nativeProxy);

    private static native void nTrimMemory(int level);

    private static native void nOverrideProperty(String name, String value);

    private static native void nFence(long nativeProxy);

    private static native void nStopDrawing(long nativeProxy);

    private static native void nNotifyFramePending(long nativeProxy);

    private static native void nDumpProfileInfo(long nativeProxy, FileDescriptor fd,
            @DumpFlags int dumpFlags);

    private static native void nAddRenderNode(long nativeProxy, long rootRenderNode,
            boolean placeFront);

    private static native void nRemoveRenderNode(long nativeProxy, long rootRenderNode);

    private static native void nDrawRenderNode(long nativeProxy, long rootRenderNode);

    private static native void nSetContentDrawBounds(long nativeProxy, int left,
            int top, int right, int bottom);

    private static native void nSetPictureCaptureCallback(long nativeProxy,
            PictureCapturedCallback callback);

    private static native void nSetASurfaceTransactionCallback(long nativeProxy,
            ASurfaceTransactionCallback callback);

    private static native void nSetPrepareSurfaceControlForWebviewCallback(long nativeProxy,
            PrepareSurfaceControlForWebviewCallback callback);

    private static native void nSetFrameCallback(long nativeProxy, FrameDrawingCallback callback);

    private static native void nSetFrameCompleteCallback(long nativeProxy,
            FrameCompleteCallback callback);

    private static native void nAddObserver(long nativeProxy, long nativeObserver);

    private static native void nRemoveObserver(long nativeProxy, long nativeObserver);

    private static native int nCopySurfaceInto(Surface surface,
            int srcLeft, int srcTop, int srcRight, int srcBottom, long bitmapHandle);

    private static native Bitmap nCreateHardwareBitmap(long renderNode, int width, int height);

    private static native void nSetHighContrastText(boolean enabled);

    // For temporary experimentation b/66945974
    private static native void nHackySetRTAnimationsEnabled(boolean enabled);

    private static native void nSetDebuggingEnabled(boolean enabled);

    private static native void nSetIsolatedProcess(boolean enabled);

    private static native void nSetContextPriority(int priority);

    private static native void nAllocateBuffers(long nativeProxy);

    private static native void nSetForceDark(long nativeProxy, boolean enabled);

    private static native void nSetDisplayDensityDpi(int densityDpi);

    private static native void nInitDisplayInfo(int width, int height, float refreshRate,
            int wideColorDataspace, long appVsyncOffsetNanos, long presentationDeadlineNanos);
}
