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

import static javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_BAD_NATIVE_WINDOW;
import static javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_CONFIG_CAVEAT;
import static javax.microedition.khronos.egl.EGL10.EGL_DEFAULT_DISPLAY;
import static javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_DRAW;
import static javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_HEIGHT;
import static javax.microedition.khronos.egl.EGL10.EGL_NONE;
import static javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT;
import static javax.microedition.khronos.egl.EGL10.EGL_NO_DISPLAY;
import static javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE;
import static javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE;
import static javax.microedition.khronos.egl.EGL10.EGL_SAMPLES;
import static javax.microedition.khronos.egl.EGL10.EGL_SAMPLE_BUFFERS;
import static javax.microedition.khronos.egl.EGL10.EGL_STENCIL_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_SUCCESS;
import static javax.microedition.khronos.egl.EGL10.EGL_SURFACE_TYPE;
import static javax.microedition.khronos.egl.EGL10.EGL_WIDTH;
import static javax.microedition.khronos.egl.EGL10.EGL_WINDOW_BIT;

import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
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

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

/**
 * Hardware renderer using OpenGL
 *
 * @hide
 */
public class GLRenderer extends HardwareRenderer {
    static final int SURFACE_STATE_ERROR = 0;
    static final int SURFACE_STATE_SUCCESS = 1;
    static final int SURFACE_STATE_UPDATED = 2;

    static final int FUNCTOR_PROCESS_DELAY = 4;

    /**
     * Number of frames to profile.
     */
    private static final int PROFILE_MAX_FRAMES = 128;

    /**
     * Number of floats per profiled frame.
     */
    private static final int PROFILE_FRAME_DATA_COUNT = 3;

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
    };
    private static final int GL_VERSION = 2;

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
        sDirtyRegions = "true".equalsIgnoreCase(dirtyProperty);
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

    final boolean mTranslucent;

    private boolean mDestroyed;

    private final Rect mRedrawClip = new Rect();

    private final int[] mSurfaceSize = new int[2];
    private final FunctorsRunnable mFunctorsRunnable = new FunctorsRunnable();

    private long mDrawDelta = Long.MAX_VALUE;

    private GLES20Canvas mGlCanvas;

    private DisplayMetrics mDisplayMetrics;

    private static EGLSurface sPbuffer;
    private static final Object[] sPbufferLock = new Object[0];

    private List<HardwareLayer> mAttachedLayers = new ArrayList<HardwareLayer>();

    private static class GLRendererEglContext extends ManagedEGLContext {
        final Handler mHandler = new Handler();

        public GLRendererEglContext(EGLContext context) {
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

    HardwareCanvas createCanvas() {
        return mGlCanvas = new GLES20Canvas(mTranslucent);
    }

    ManagedEGLContext createManagedContext(EGLContext eglContext) {
        return new GLRendererEglContext(mEglContext);
    }

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

    void initCaches() {
        if (GLES20Canvas.initCaches()) {
            // Caches were (re)initialized, rebind atlas
            initAtlas();
        }
    }

    void initAtlas() {
        IBinder binder = ServiceManager.getService("assetatlas");
        if (binder == null) return;

        IAssetAtlas atlas = IAssetAtlas.Stub.asInterface(binder);
        try {
            if (atlas.isCompatible(android.os.Process.myPpid())) {
                GraphicBuffer buffer = atlas.getBuffer();
                if (buffer != null) {
                    long[] map = atlas.getMap();
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

    boolean canDraw() {
        return mGl != null && mCanvas != null && mGlCanvas != null;
    }

    int onPreDraw(Rect dirty) {
        return mGlCanvas.onPreDraw(dirty);
    }

    void onPostDraw() {
        mGlCanvas.onPostDraw();
    }

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
    void flushLayerUpdates() {
        if (validate()) {
            flushLayerChanges();
            mGlCanvas.flushLayerUpdates();
        }
    }

    @Override
    HardwareLayer createTextureLayer() {
        validate();
        return HardwareLayer.createTextureLayer(this);
    }

    @Override
    public HardwareLayer createDisplayListLayer(int width, int height) {
        validate();
        return HardwareLayer.createDisplayListLayer(this, width, height);
    }

    @Override
    void onLayerCreated(HardwareLayer hardwareLayer) {
        mAttachedLayers.add(hardwareLayer);
    }

    boolean hasContext() {
        return sEgl != null && mEglContext != null
                && mEglContext.equals(sEgl.eglGetCurrentContext());
    }

    @Override
    void onLayerDestroyed(HardwareLayer layer) {
        if (mGlCanvas != null) {
            mGlCanvas.cancelLayerUpdate(layer);
        }
        if (hasContext()) {
            long backingLayer = layer.detachBackingLayer();
            nDestroyLayer(backingLayer);
        }
        mAttachedLayers.remove(layer);
    }

    @Override
    public SurfaceTexture createSurfaceTexture(HardwareLayer layer) {
        return layer.createSurfaceTexture();
    }

    @Override
    boolean copyLayerInto(HardwareLayer layer, Bitmap bitmap) {
        if (!validate()) {
            throw new IllegalStateException("Could not acquire hardware rendering context");
        }
        layer.flushChanges();
        return GLES20Canvas.nCopyLayer(layer.getLayer(), bitmap.mNativeBitmap);
    }

    @Override
    boolean safelyRun(Runnable action) {
        boolean needsContext = !isEnabled() || checkRenderContext() == SURFACE_STATE_ERROR;

        if (needsContext) {
            GLRendererEglContext managedContext =
                    (GLRendererEglContext) sEglContextStorage.get();
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

    static void startTrimMemory(int level) {
        if (sEgl == null || sEglConfig == null) return;

        GLRendererEglContext managedContext =
                (GLRendererEglContext) sEglContextStorage.get();
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

    GLRenderer(boolean translucent) {
        mTranslucent = translucent;

        loadSystemProperties();
    }

    @Override
    boolean loadSystemProperties() {
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
        }

        if (loadProperties()) {
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

    EGLContext createContext(EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig) {
        final int[] attribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, GL_VERSION, EGL_NONE };

        EGLContext context = egl.eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT,
                attribs);
        if (context == null || context == EGL_NO_CONTEXT) {
            //noinspection ConstantConditions
            throw new IllegalStateException(
                    "Could not create an EGL context. eglCreateContext failed with error: " +
                    GLUtils.getEGLErrorString(sEgl.eglGetError()));
        }

        return context;
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
    void setName(String name) {
        mName = name;
    }

    class FunctorsRunnable implements Runnable {
        View.AttachInfo attachInfo;

        @Override
        public void run() {
            final HardwareRenderer renderer = attachInfo.mHardwareRenderer;
            if (renderer == null || !renderer.isEnabled() || renderer != GLRenderer.this) {
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

                if (mProfileEnabled) {
                    mProfileLock.lock();
                }

                dirty = beginFrame(canvas, dirty, surfaceState);

                DisplayList displayList = buildDisplayList(view, canvas);

                flushLayerChanges();

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

    private void flushLayerChanges() {
        // Loop through and apply any pending layer changes
        for (int i = 0; i < mAttachedLayers.size(); i++) {
            HardwareLayer layer = mAttachedLayers.get(i);
            layer.flushChanges();
            if (!layer.isValid()) {
                // The layer was removed from mAttachedLayers, rewind i by 1
                // Note that this shouldn't actually happen as View.getHardwareLayer()
                // is already flushing for error checking reasons
                i--;
            }
        }
    }

    void setDisplayListData(long displayList, long newData) {
        nSetDisplayListData(displayList, newData);
    }
    private static native void nSetDisplayListData(long displayList, long newData);

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
    void detachFunctor(long functor) {
        if (mCanvas != null) {
            mCanvas.detachFunctor(functor);
        }
    }

    @Override
    void attachFunctor(View.AttachInfo attachInfo, long functor) {
        if (mCanvas != null) {
            mCanvas.attachFunctor(functor);
            mFunctorsRunnable.attachInfo = attachInfo;
            attachInfo.mHandler.removeCallbacks(mFunctorsRunnable);
            attachInfo.mHandler.postDelayed(mFunctorsRunnable,  0);
        }
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

    static native boolean loadProperties();

    static native void setupShadersDiskCache(String cacheFile);

    /**
     * Notifies EGL that the frame is about to be rendered.
     * @param size
     */
    static native void beginFrame(int[] size);

    /**
     * Returns the current system time according to the renderer.
     * This method is used for debugging only and should not be used
     * as a clock.
     */
    static native long getSystemTime();

    /**
     * Preserves the back buffer of the current surface after a buffer swap.
     * Calling this method sets the EGL_SWAP_BEHAVIOR attribute of the current
     * surface to EGL_BUFFER_PRESERVED. Calling this method requires an EGL
     * config that supports EGL_SWAP_BEHAVIOR_PRESERVED_BIT.
     *
     * @return True if the swap behavior was successfully changed,
     *         false otherwise.
     */
    static native boolean preserveBackBuffer();

    /**
     * Indicates whether the current surface preserves its back buffer
     * after a buffer swap.
     *
     * @return True, if the surface's EGL_SWAP_BEHAVIOR is EGL_BUFFER_PRESERVED,
     *         false otherwise
     */
    static native boolean isBackBufferPreserved();

    static native void nDestroyLayer(long layerPtr);

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
