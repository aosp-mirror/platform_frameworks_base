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

import com.android.internal.R;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.TimeUtils;
import android.view.Surface.OutOfResourcesException;
import android.view.View.AttachInfo;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
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
public class ThreadedRenderer extends HardwareRenderer {
    private static final String LOGTAG = "ThreadedRenderer";

    // Keep in sync with DrawFrameTask.h SYNC_* flags
    // Nothing interesting to report
    private static final int SYNC_OK = 0;
    // Needs a ViewRoot invalidate
    private static final int SYNC_INVALIDATE_REQUIRED = 1 << 0;
    // Spoiler: the reward is GPU-accelerated drawing, better find that Surface!
    private static final int SYNC_LOST_SURFACE_REWARD_IF_FOUND = 1 << 1;

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
    private boolean mProfilingEnabled;
    private boolean mRootNodeNeedsUpdate;

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

        AtlasInitializer.sInstance.init(context, mNativeProxy);

        // Setup timing
        mChoreographer = Choreographer.getInstance();
        nSetFrameInterval(mNativeProxy, mChoreographer.getFrameIntervalNanos());

        loadSystemProperties();
    }

    @Override
    void destroy() {
        mInitialized = false;
        updateEnabledState(null);
        nDestroy(mNativeProxy);
    }

    private void updateEnabledState(Surface surface) {
        if (surface == null || !surface.isValid()) {
            setEnabled(false);
        } else {
            setEnabled(mInitialized);
        }
    }

    @Override
    boolean initialize(Surface surface) throws OutOfResourcesException {
        mInitialized = true;
        updateEnabledState(surface);
        boolean status = nInitialize(mNativeProxy, surface);
        surface.allocateBuffers();
        return status;
    }

    @Override
    void updateSurface(Surface surface) throws OutOfResourcesException {
        updateEnabledState(surface);
        nUpdateSurface(mNativeProxy, surface);
    }

    @Override
    boolean pauseSurface(Surface surface) {
        return nPauseSurface(mNativeProxy, surface);
    }

    @Override
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

    @Override
    void invalidate(Surface surface) {
        updateSurface(surface);
    }

    @Override
    void detachSurfaceTexture(long hardwareLayer) {
        nDetachSurfaceTexture(mNativeProxy, hardwareLayer);
    }

    @Override
    void setup(int width, int height, Rect surfaceInsets) {
        final float lightX = width / 2.0f;
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
        nSetup(mNativeProxy, mSurfaceWidth, mSurfaceHeight,
                lightX, mLightY, mLightZ, mLightRadius,
                mAmbientShadowAlpha, mSpotShadowAlpha);
    }

    @Override
    void setOpaque(boolean opaque) {
        nSetOpaque(mNativeProxy, opaque && !mHasInsets);
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
    void dumpGfxInfo(PrintWriter pw, FileDescriptor fd) {
        pw.flush();
        nDumpProfileInfo(mNativeProxy, fd);
    }

    private static int search(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return i;
        }
        return -1;
    }

    private static boolean checkIfProfilingRequested() {
        String profiling = SystemProperties.get(HardwareRenderer.PROFILE_PROPERTY);
        int graphType = search(VISUALIZERS, profiling);
        return (graphType >= 0) || Boolean.parseBoolean(profiling);
    }

    @Override
    boolean loadSystemProperties() {
        boolean changed = nLoadSystemProperties(mNativeProxy);
        boolean wantProfiling = checkIfProfilingRequested();
        if (wantProfiling != mProfilingEnabled) {
            mProfilingEnabled = wantProfiling;
            changed = true;
        }
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
        view.getDisplayList();
        view.mRecreateDisplayList = false;
    }

    private void updateRootDisplayList(View view, HardwareDrawCallbacks callbacks) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "Record View#draw()");
        updateViewTreeDisplayList(view);

        if (mRootNodeNeedsUpdate || !mRootNode.isValid()) {
            HardwareCanvas canvas = mRootNode.start(mSurfaceWidth, mSurfaceHeight);
            try {
                final int saveCount = canvas.save();
                canvas.translate(mInsetLeft, mInsetTop);
                callbacks.onHardwarePreDraw(canvas);

                canvas.insertReorderBarrier();
                canvas.drawRenderNode(view.getDisplayList());
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

    @Override
    void invalidateRoot() {
        mRootNodeNeedsUpdate = true;
    }

    @Override
    void draw(View view, AttachInfo attachInfo, HardwareDrawCallbacks callbacks) {
        attachInfo.mIgnoreDirtyState = true;
        long frameTimeNanos = mChoreographer.getFrameTimeNanos();
        attachInfo.mDrawingTime = frameTimeNanos / TimeUtils.NANOS_PER_MS;

        long recordDuration = 0;
        if (mProfilingEnabled) {
            recordDuration = System.nanoTime();
        }

        updateRootDisplayList(view, callbacks);

        if (mProfilingEnabled) {
            recordDuration = System.nanoTime() - recordDuration;
        }

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

        int syncResult = nSyncAndDrawFrame(mNativeProxy, frameTimeNanos,
                recordDuration, view.getResources().getDisplayMetrics().density);
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

    @Override
    HardwareLayer createTextureLayer() {
        long layer = nCreateTextureLayer(mNativeProxy);
        return HardwareLayer.adoptTextureLayer(this, layer);
    }

    @Override
    void buildLayer(RenderNode node) {
        nBuildLayer(mNativeProxy, node.getNativeDisplayList());
    }

    @Override
    boolean copyLayerInto(final HardwareLayer layer, final Bitmap bitmap) {
        return nCopyLayerInto(mNativeProxy,
                layer.getDeferredLayerUpdater(), bitmap.mNativeBitmap);
    }

    @Override
    void pushLayerUpdate(HardwareLayer layer) {
        nPushLayerUpdate(mNativeProxy, layer.getDeferredLayerUpdater());
    }

    @Override
    void onLayerDestroyed(HardwareLayer layer) {
        nCancelLayerUpdate(mNativeProxy, layer.getDeferredLayerUpdater());
    }

    @Override
    void setName(String name) {
    }

    @Override
    void fence() {
        nFence(mNativeProxy);
    }

    @Override
    void stopDrawing() {
        nStopDrawing(mNativeProxy);
    }

    @Override
    public void notifyFramePending() {
        nNotifyFramePending(mNativeProxy);
    }

    @Override
    void registerAnimatingRenderNode(RenderNode animator) {
        nRegisterAnimatingRenderNode(mRootNode.mNativeRenderNode, animator.mNativeRenderNode);
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

    static void trimMemory(int level) {
        nTrimMemory(level);
    }

    private static class AtlasInitializer {
        static AtlasInitializer sInstance = new AtlasInitializer();

        private boolean mInitialized = false;

        private AtlasInitializer() {}

        synchronized void init(Context context, long renderProxy) {
            if (mInitialized) return;
            IBinder binder = ServiceManager.getService("assetatlas");
            if (binder == null) return;

            IAssetAtlas atlas = IAssetAtlas.Stub.asInterface(binder);
            try {
                if (atlas.isCompatible(android.os.Process.myPpid())) {
                    GraphicBuffer buffer = atlas.getBuffer();
                    if (buffer != null) {
                        long[] map = atlas.getMap();
                        if (map != null) {
                            // TODO Remove after fixing b/15425820
                            validateMap(context, map);
                            nSetAtlas(renderProxy, buffer, map);
                            mInitialized = true;
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

        private static void validateMap(Context context, long[] map) {
            Log.d("Atlas", "Validating map...");
            HashSet<Long> preloadedPointers = new HashSet<Long>();

            // We only care about drawables that hold bitmaps
            final Resources resources = context.getResources();
            final LongSparseArray<Drawable.ConstantState> drawables = resources.getPreloadedDrawables();

            final int count = drawables.size();
            ArrayList<Bitmap> tmpList = new ArrayList<Bitmap>();
            for (int i = 0; i < count; i++) {
                drawables.valueAt(i).addAtlasableBitmaps(tmpList);
                for (int j = 0; j < tmpList.size(); j++) {
                    preloadedPointers.add(tmpList.get(j).mNativeBitmap);
                }
                tmpList.clear();
            }

            for (int i = 0; i < map.length; i += 4) {
                if (!preloadedPointers.contains(map[i])) {
                    Log.w("Atlas", String.format("Pointer 0x%X, not in getPreloadedDrawables?", map[i]));
                    map[i] = 0;
                }
            }
        }
    }

    static native void setupShadersDiskCache(String cacheFile);

    private static native void nSetAtlas(long nativeProxy, GraphicBuffer buffer, long[] map);

    private static native long nCreateRootRenderNode();
    private static native long nCreateProxy(boolean translucent, long rootRenderNode);
    private static native void nDeleteProxy(long nativeProxy);

    private static native void nSetFrameInterval(long nativeProxy, long frameIntervalNanos);
    private static native boolean nLoadSystemProperties(long nativeProxy);

    private static native boolean nInitialize(long nativeProxy, Surface window);
    private static native void nUpdateSurface(long nativeProxy, Surface window);
    private static native boolean nPauseSurface(long nativeProxy, Surface window);
    private static native void nSetup(long nativeProxy, int width, int height,
            float lightX, float lightY, float lightZ, float lightRadius,
            int ambientShadowAlpha, int spotShadowAlpha);
    private static native void nSetOpaque(long nativeProxy, boolean opaque);
    private static native int nSyncAndDrawFrame(long nativeProxy,
            long frameTimeNanos, long recordDuration, float density);
    private static native void nDestroy(long nativeProxy);
    private static native void nRegisterAnimatingRenderNode(long rootRenderNode, long animatingNode);

    private static native void nInvokeFunctor(long functor, boolean waitForCompletion);

    private static native long nCreateTextureLayer(long nativeProxy);
    private static native void nBuildLayer(long nativeProxy, long node);
    private static native boolean nCopyLayerInto(long nativeProxy, long layer, long bitmap);
    private static native void nPushLayerUpdate(long nativeProxy, long layer);
    private static native void nCancelLayerUpdate(long nativeProxy, long layer);
    private static native void nDetachSurfaceTexture(long nativeProxy, long layer);

    private static native void nDestroyHardwareResources(long nativeProxy);
    private static native void nTrimMemory(int level);

    private static native void nFence(long nativeProxy);
    private static native void nStopDrawing(long nativeProxy);
    private static native void nNotifyFramePending(long nativeProxy);

    private static native void nDumpProfileInfo(long nativeProxy, FileDescriptor fd);
}
