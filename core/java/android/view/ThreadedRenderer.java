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
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Log;
import android.util.TimeUtils;
import android.view.Surface.OutOfResourcesException;
import android.view.View.AttachInfo;

import java.io.FileDescriptor;
import java.io.PrintWriter;

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
    private static final int SYNC_OK = 0x0;
    // Needs a ViewRoot invalidate
    private static final int SYNC_INVALIDATE_REQUIRED = 0x1;

    private static final String[] VISUALIZERS = {
        PROFILE_PROPERTY_VISUALIZE_BARS,
    };

    private int mWidth, mHeight;
    private long mNativeProxy;
    private boolean mInitialized = false;
    private RenderNode mRootNode;
    private Choreographer mChoreographer;
    private boolean mProfilingEnabled;

    ThreadedRenderer(boolean translucent) {
        AtlasInitializer.sInstance.init();

        long rootNodePtr = nCreateRootRenderNode();
        mRootNode = RenderNode.adopt(rootNodePtr);
        mRootNode.setClipToBounds(false);
        mNativeProxy = nCreateProxy(translucent, rootNodePtr);

        // Setup timing
        mChoreographer = Choreographer.getInstance();
        nSetFrameInterval(mNativeProxy, mChoreographer.getFrameIntervalNanos());

        loadSystemProperties();
    }

    @Override
    void destroy(boolean full) {
        mInitialized = false;
        updateEnabledState(null);
        nDestroyCanvasAndSurface(mNativeProxy);
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
        return nInitialize(mNativeProxy, surface);
    }

    @Override
    void updateSurface(Surface surface) throws OutOfResourcesException {
        updateEnabledState(surface);
        nUpdateSurface(mNativeProxy, surface);
    }

    @Override
    void pauseSurface(Surface surface) {
        nPauseSurface(mNativeProxy, surface);
    }

    @Override
    void destroyHardwareResources(View view) {
        destroyResources(view);
        nFlushCaches(mNativeProxy, GLES20Canvas.FLUSH_CACHES_LAYERS);
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
    boolean safelyRun(Runnable action) {
        nRunWithGlContext(mNativeProxy, action);
        return true;
    }

    @Override
    void setup(int width, int height, float lightX, float lightY, float lightZ, float lightRadius) {
        mWidth = width;
        mHeight = height;
        mRootNode.setLeftTopRightBottom(0, 0, mWidth, mHeight);
        nSetup(mNativeProxy, width, height, lightX, lightY, lightZ, lightRadius);
    }

    @Override
    void setOpaque(boolean opaque) {
        nSetOpaque(mNativeProxy, opaque);
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
        return changed;
    }

    private void updateRootDisplayList(View view, HardwareDrawCallbacks callbacks) {
        view.mPrivateFlags |= View.PFLAG_DRAWN;

        view.mRecreateDisplayList = (view.mPrivateFlags & View.PFLAG_INVALIDATED)
                == View.PFLAG_INVALIDATED;
        view.mPrivateFlags &= ~View.PFLAG_INVALIDATED;

        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "getDisplayList");
        HardwareCanvas canvas = mRootNode.start(mWidth, mHeight);
        try {
            canvas.save();
            callbacks.onHardwarePreDraw(canvas);
            canvas.drawDisplayList(view.getDisplayList());
            callbacks.onHardwarePostDraw(canvas);
            canvas.restore();
        } finally {
            mRootNode.end(canvas);
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }

        view.mRecreateDisplayList = false;
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

        int syncResult = nSyncAndDrawFrame(mNativeProxy, frameTimeNanos,
                recordDuration, view.getResources().getDisplayMetrics().density);
        if ((syncResult & SYNC_INVALIDATE_REQUIRED) != 0) {
            attachInfo.mViewRootImpl.invalidate();
        }
    }

    @Override
    void invokeFunctor(long functor, boolean waitForCompletion) {
        nInvokeFunctor(mNativeProxy, functor, waitForCompletion);
    }

    @Override
    HardwareLayer createDisplayListLayer(int width, int height) {
        long layer = nCreateDisplayListLayer(mNativeProxy, width, height);
        return HardwareLayer.adoptDisplayListLayer(this, layer);
    }

    @Override
    HardwareLayer createTextureLayer() {
        long layer = nCreateTextureLayer(mNativeProxy);
        return HardwareLayer.adoptTextureLayer(this, layer);
    }

    @Override
    SurfaceTexture createSurfaceTexture(final HardwareLayer layer) {
        final SurfaceTexture[] ret = new SurfaceTexture[1];
        nRunWithGlContext(mNativeProxy, new Runnable() {
            @Override
            public void run() {
                ret[0] = layer.createSurfaceTexture();
            }
        });
        return ret[0];
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
    void flushLayerUpdates() {
        // TODO: Figure out what this should do or remove it
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
    public void notifyFramePending() {
        nNotifyFramePending(mNativeProxy);
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

    static void startTrimMemory(int level) {
        // TODO
    }

    static void endTrimMemory() {
        // TODO
    }

    private static class AtlasInitializer {
        static AtlasInitializer sInstance = new AtlasInitializer();

        private boolean mInitialized = false;

        private AtlasInitializer() {}

        synchronized void init() {
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
                            nSetAtlas(buffer, map);
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
    }

    static native void setupShadersDiskCache(String cacheFile);

    private static native void nSetAtlas(GraphicBuffer buffer, long[] map);

    private static native long nCreateRootRenderNode();
    private static native long nCreateProxy(boolean translucent, long rootRenderNode);
    private static native void nDeleteProxy(long nativeProxy);

    private static native void nSetFrameInterval(long nativeProxy, long frameIntervalNanos);
    private static native boolean nLoadSystemProperties(long nativeProxy);

    private static native boolean nInitialize(long nativeProxy, Surface window);
    private static native void nUpdateSurface(long nativeProxy, Surface window);
    private static native void nPauseSurface(long nativeProxy, Surface window);
    private static native void nSetup(long nativeProxy, int width, int height,
            float lightX, float lightY, float lightZ, float lightRadius);
    private static native void nSetOpaque(long nativeProxy, boolean opaque);
    private static native int nSyncAndDrawFrame(long nativeProxy,
            long frameTimeNanos, long recordDuration, float density);
    private static native void nRunWithGlContext(long nativeProxy, Runnable runnable);
    private static native void nDestroyCanvasAndSurface(long nativeProxy);

    private static native void nInvokeFunctor(long nativeProxy, long functor, boolean waitForCompletion);

    private static native long nCreateDisplayListLayer(long nativeProxy, int width, int height);
    private static native long nCreateTextureLayer(long nativeProxy);
    private static native boolean nCopyLayerInto(long nativeProxy, long layer, long bitmap);
    private static native void nPushLayerUpdate(long nativeProxy, long layer);
    private static native void nCancelLayerUpdate(long nativeProxy, long layer);

    private static native void nFlushCaches(long nativeProxy, int flushMode);

    private static native void nFence(long nativeProxy);
    private static native void nNotifyFramePending(long nativeProxy);

    private static native void nDumpProfileInfo(long nativeProxy, FileDescriptor fd);
}
