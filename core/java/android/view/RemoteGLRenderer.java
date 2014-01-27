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

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface.OutOfResourcesException;

import java.io.PrintWriter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hardware renderer using OpenGL that's used as the remote endpoint
 * of ThreadedRenderer
 *
 * Currently this is mostly a copy of GLRenderer, but with a few modifications
 * to deal with the threading issues. Ideally native-side functionality
 * will replace this, but we need this to bootstrap without risking breaking
 * changes in GLRenderer
 *
 * @hide
 */
public class RemoteGLRenderer extends HardwareRenderer {
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
            OVERDRAW_PROPERTY_COUNT
    };
    private static final int OVERDRAW_TYPE_COUNT = 1;
    private static final int GL_VERSION = 2;

    int mWidth = -1, mHeight = -1;

    HardwareCanvas mCanvas;

    String mName;

    long mFrameCount;
    Paint mDebugPaint;

    boolean mDirtyRegionsEnabled;
    boolean mSurfaceUpdated;

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

    final boolean mTranslucent;

    private final Rect mRedrawClip = new Rect();

    private final int[] mSurfaceSize = new int[2];
    private final FunctorsRunnable mFunctorsRunnable = new FunctorsRunnable();

    private long mDrawDelta = Long.MAX_VALUE;

    private GLES20Canvas mGlCanvas;

    private DisplayMetrics mDisplayMetrics;
    private ThreadedRenderer mOwningRenderer;
    private long mNativeCanvasContext;

    HardwareCanvas createCanvas() {
        return mGlCanvas = new GLES20Canvas(mTranslucent);
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

    boolean canDraw() {
        return mCanvas != null && mGlCanvas != null;
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
            if (mNativeCanvasContext != 0) {
                destroyContext(mNativeCanvasContext);
                mNativeCanvasContext = 0;
            }
            setEnabled(false);
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
    HardwareLayer createHardwareLayer(boolean isOpaque) {
        return new GLES20TextureLayer(isOpaque);
    }

    @Override
    public HardwareLayer createHardwareLayer(int width, int height, boolean isOpaque) {
        return new GLES20RenderLayer(width, height, isOpaque);
    }

    void countOverdraw(HardwareCanvas canvas) {
        ((GLES20Canvas) canvas).setCountOverdrawEnabled(true);
    }

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
            if (!usePBufferSurface()) {
                return false;
            }
        }

        action.run();

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

    RemoteGLRenderer(ThreadedRenderer owningRenderer, boolean translucent) {
        mOwningRenderer = owningRenderer;
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

            if (mDebugOverdraw != OVERDRAW_TYPE_COUNT) {
                if (mDebugOverdrawLayer != null) {
                    mDebugOverdrawLayer.destroy();
                    mDebugOverdrawLayer = null;
                    mDebugOverdrawPaint = null;
                }
            }
        }

        if (GLRenderer.loadProperties()) {
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

    private void triggerSoftwareFallback() {
        destroy(true);
        // we'll try again if it was context lost
        setRequested(false);
        Log.w(LOG_TAG, "Mountain View, we've had a problem here. "
                + "Switching back to software rendering.");
    }

    @Override
    boolean initialize(Surface surface) throws OutOfResourcesException {
        if (isRequested() && !isEnabled()) {
            mNativeCanvasContext = createContext();
            boolean surfaceCreated = createEglSurface(surface);

            if (surfaceCreated) {
                if (mCanvas == null) {
                    mCanvas = createCanvas();
                }
                setEnabled(true);
                initAtlas();
                return true;
            } else {
                destroy(true);
                setRequested(false);
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

    boolean createEglSurface(Surface surface) throws OutOfResourcesException {
        // Create an EGL surface we can render into.
        if (!setSurface(mNativeCanvasContext, surface)) {
            return false;
        }
        makeCurrent(mNativeCanvasContext);
        mSurfaceUpdated = true;

        initCaches();
        return true;
    }

    @Override
    void invalidate(Surface surface) {
        setSurface(mNativeCanvasContext, null);
        setEnabled(false);

        if (surface.isValid()) {
            if (createEglSurface(surface) && mCanvas != null) {
                setEnabled(true);
            }
        }
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
    void setName(String name) {
        mName = name;
    }

    // TODO: Ping pong is fun and all, but this isn't the time or place
    // However we don't yet have the ability for the RenderThread to run
    // independently nor have a way to postDelayed, so this will work for now
    private Runnable mDispatchFunctorsRunnable = new Runnable() {
        @Override
        public void run() {
            ThreadedRenderer.postToRenderThread(mFunctorsRunnable);
        }
    };

    class FunctorsRunnable implements Runnable {
        View.AttachInfo attachInfo;

        @Override
        public void run() {
            final HardwareRenderer renderer = attachInfo.mHardwareRenderer;
            if (renderer == null || !renderer.isEnabled() || renderer != mOwningRenderer) {
                return;
            }

            if (checkRenderContext() != SURFACE_STATE_ERROR) {
                int status = mCanvas.invokeFunctors(mRedrawClip);
                handleFunctorStatus(attachInfo, status);
            }
        }
    }

    /**
     * @param displayList The display list to draw
     * @param attachInfo AttachInfo tied to the specified view.
     * @param callbacks Callbacks invoked when drawing happens.
     * @param dirty The dirty rectangle to update, can be null.
     */
    void drawDisplayList(DisplayList displayList, View.AttachInfo attachInfo,
            HardwareDrawCallbacks callbacks, Rect dirty) {
        if (canDraw()) {
            if (!hasDirtyRegions()) {
                dirty = null;
            }

            final int surfaceState = checkRenderContext();
            if (surfaceState != SURFACE_STATE_ERROR) {
                HardwareCanvas canvas = mCanvas;

                if (mProfileEnabled) {
                    mProfileLock.lock();
                }

                dirty = beginFrame(canvas, dirty, surfaceState);

                int saveCount = 0;
                int status = DisplayList.STATUS_DONE;

                long start = GLRenderer.getSystemTime();
                try {
                    status = prepareFrame(dirty);

                    saveCount = canvas.save();
                    callbacks.onHardwarePreDraw(canvas);

                    status |= doDrawDisplayList(attachInfo, canvas, displayList, status);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "An error has occurred while drawing:", e);
                } finally {
                    callbacks.onHardwarePostDraw(canvas);
                    canvas.restoreToCount(saveCount);

                    mDrawDelta = GLRenderer.getSystemTime() - start;

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

    @Override
    void draw(View view, View.AttachInfo attachInfo, HardwareDrawCallbacks callbacks,
            Rect dirty) {
        throw new IllegalAccessError();
    }

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

    private Rect beginFrame(HardwareCanvas canvas, Rect dirty, int surfaceState) {
        // We had to change the current surface and/or context, redraw everything
        if (surfaceState == SURFACE_STATE_UPDATED) {
            dirty = null;
            GLRenderer.beginFrame(null);
        } else {
            int[] size = mSurfaceSize;
            GLRenderer.beginFrame(size);

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

    private int doDrawDisplayList(View.AttachInfo attachInfo, HardwareCanvas canvas,
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

            if (!swapBuffers(mNativeCanvasContext)) {
                triggerSoftwareFallback();
            }
            mSurfaceUpdated = false;

            if (mProfileEnabled) {
                long now = System.nanoTime();
                float total = (now - eglSwapBuffersStartTime) * 0.000001f;
                mProfileData[mProfileCurrentFrame + 2] = total;
            }
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

    private void handleFunctorStatus(final View.AttachInfo attachInfo, int status) {
        // If the draw flag is set, functors will be invoked while executing
        // the tree of display lists
        if ((status & DisplayList.STATUS_DRAW) != 0) {
            // TODO: Can we just re-queue ourselves up to draw next frame instead
            // of bouncing back to the UI thread?
            // TODO: Respect mRedrawClip - for now just full inval
            attachInfo.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    attachInfo.mViewRootImpl.invalidate();
                }
            });
            mRedrawClip.setEmpty();
        }

        if ((status & DisplayList.STATUS_INVOKE) != 0 ||
                attachInfo.mHandler.hasCallbacks(mDispatchFunctorsRunnable)) {
            attachInfo.mHandler.removeCallbacks(mDispatchFunctorsRunnable);
            mFunctorsRunnable.attachInfo = attachInfo;
            attachInfo.mHandler.postDelayed(mDispatchFunctorsRunnable, FUNCTOR_PROCESS_DELAY);
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
            attachInfo.mHandler.removeCallbacks(mDispatchFunctorsRunnable);
            attachInfo.mHandler.postDelayed(mDispatchFunctorsRunnable,  0);
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
        if (!makeCurrent(mNativeCanvasContext)) {
            triggerSoftwareFallback();
            return SURFACE_STATE_ERROR;
        }
        return mSurfaceUpdated ? SURFACE_STATE_UPDATED : SURFACE_STATE_SUCCESS;
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

    static native long createContext();
    static native boolean usePBufferSurface();
    static native boolean setSurface(long nativeCanvasContext, Surface surface);
    static native boolean swapBuffers(long nativeCanvasContext);
    static native boolean makeCurrent(long nativeCanvasContext);
    static native void destroyContext(long nativeCanvasContext);
}
